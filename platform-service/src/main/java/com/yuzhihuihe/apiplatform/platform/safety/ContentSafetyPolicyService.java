package com.yuzhihuihe.apiplatform.platform.safety;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentSafetyPolicyService {

    private static final HexFormat HEX = HexFormat.of();
    private static final Set<String> RULE_TYPES = Set.of(
            "SENSITIVE_WORD", "REGEX", "PROMPT_INJECTION", "FILE", "SEMANTIC");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> ACTIONS = Set.of("ALLOW", "REVIEW", "BLOCK");
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final ContentSafetyPolicyRepository repository;
    private final ObjectMapper objectMapper;
    private final String signingSecret;

    ContentSafetyPolicyService(
            ContentSafetyPolicyRepository repository, ObjectMapper objectMapper,
            @Value("${content-safety.policy-signing-secret:}") String signingSecret) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signingSecret = signingSecret == null ? "" : signingSecret;
    }

    public static String configSha256(Object config) {
        try {
            byte[] canonical = CANONICAL_MAPPER.writeValueAsBytes(
                    config == null ? Map.of() : config);
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("content_safety_policy_hash_failed", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_policy_hash_failed", exception);
        }
    }

    @Transactional
    public PolicyVersionView createDraft(Map<String, Object> config, UUID actorUserId) {
        long version = repository.nextVersion();
        String hash = configSha256(config);
        repository.insertDraft(version, writeJson(config), hash);
        return new PolicyVersionView(
                version, ContentSafetyEnums.PolicyStatus.DRAFT.name(), config, hash, null);
    }

    @Transactional
    public PolicyVersionView publish(long version, UUID actorUserId) {
        Map<String, Object> config = readJson(repository.lockDraftConfig(version));
        if (config == null) {
            throw new IllegalArgumentException("content_safety_policy_not_draft");
        }
        String hash = configSha256(config);
        repository.publish(version, actorUserId, hash);
        return new PolicyVersionView(
                version, ContentSafetyEnums.PolicyStatus.PUBLISHED.name(),
                config, hash, Instant.now());
    }

    public PolicySnapshotView publishedSnapshot() {
        ContentSafetyPolicyRepository.SnapshotRow row = repository.publishedSnapshot();
        if (row == null) {
            throw new IllegalStateException("content_safety_policy_unavailable");
        }
        Map<String, Object> config = readJson(row.configJson());
        List<RuleView> rules = repository.publishedRules(row.version()).stream()
                .map(rule -> new RuleView(rule.ruleCode(), rule.ruleType(), rule.category(),
                        rule.severity(), rule.action()))
                .toList();
        if (rules.isEmpty()) {
            rules = rulesFromConfig(config);
        }
        String signature = signSnapshot(row.version(), row.configSha256(), row.publishedAt(), rules);
        return new PolicySnapshotView(
                row.version(), config, row.configSha256(),
                row.publishedAt(), rules, signature);
    }

    /**
     * 更早的策略版本只在 config.rules 里存储规则。在规范化规则表
     * 由更新的策略工作流填充之前，保持这些版本可用。
     */
    static List<RuleView> rulesFromConfig(Map<String, Object> config) {
        if (config == null || !(config.get("rules") instanceof Map<?, ?> configuredRules)) {
            return List.of();
        }
        List<RuleView> rules = new ArrayList<>();
        configuredRules.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .forEach(entry -> {
                    String key = String.valueOf(entry.getKey());
                    RuleView rule = ruleFromConfig(key, entry.getValue());
                    if (rule != null) {
                        rules.add(rule);
                    }
                });
        return List.copyOf(rules);
    }

    private static RuleView ruleFromConfig(String key, Object value) {
        Map<?, ?> definition = value instanceof Map<?, ?> map ? map : Map.of();
        String ruleCode = firstText(definition, "ruleCode", key);
        if (ruleCode == null || ruleCode.isBlank()) {
            return null;
        }
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        String ruleType = normalize(definition.get("ruleType"), inferRuleType(normalizedKey), RULE_TYPES);
        String category = firstText(definition, "category", inferCategory(normalizedKey));
        String severity = normalize(definition.get("severity"), "HIGH", SEVERITIES);
        String action = normalize(value instanceof String ? value : definition.get("action"),
                "BLOCK", ACTIONS);
        if (Boolean.FALSE.equals(definition.get("enabled"))) {
            return null;
        }
        return new RuleView(ruleCode, ruleType, category, severity, action);
    }

    private static String firstText(Map<?, ?> map, String field, String fallback) {
        Object value = map.get(field);
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static String normalize(Object value, String fallback, Set<String> allowed) {
        String candidate = value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return allowed.contains(candidate) ? candidate : fallback;
    }

    private static String inferRuleType(String key) {
        if (key.contains("injection")) {
            return "PROMPT_INJECTION";
        }
        if (key.contains("malware") || key.contains("file") || key.contains("unsafe")) {
            return "FILE";
        }
        if (key.contains("regex")) {
            return "REGEX";
        }
        if (key.contains("word") || key.contains("sensitive")) {
            return "SENSITIVE_WORD";
        }
        return "SEMANTIC";
    }

    private static String inferCategory(String key) {
        if (key.contains("injection")) {
            return "PROMPT_INJECTION";
        }
        if (key.contains("malware")) {
            return "MALWARE";
        }
        if (key.contains("file") || key.contains("unsafe")) {
            return "UNSAFE_FILE";
        }
        if (key.contains("word") || key.contains("sensitive") || key.contains("regex")) {
            return "SENSITIVE_CONTENT";
        }
        return "POLICY";
    }

    static String snapshotSigningPayload(
            long version, String configSha256, Instant publishedAt, List<RuleView> rules) {
        StringBuilder value = new StringBuilder()
                .append(version).append('\n').append(configSha256).append('\n')
                .append(publishedAt).append('\n');
        rules.stream().sorted(java.util.Comparator.comparing(RuleView::ruleCode))
                .forEach(rule -> value.append(rule.ruleCode()).append('|')
                        .append(rule.ruleType()).append('|').append(rule.category()).append('|')
                        .append(rule.severity()).append('|').append(rule.action()).append('\n'));
        return value.toString();
    }

    private String signSnapshot(
            long version, String configSha256, Instant publishedAt, List<RuleView> rules) {
        if (signingSecret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(snapshotSigningPayload(
                    version, configSha256, publishedAt, rules).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("content_safety_policy_signature_failed", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid_content_safety_policy", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(String value) {
        try {
            return CANONICAL_MAPPER.readValue(value, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("content_safety_policy_corrupt", exception);
        }
    }

    public record PolicyVersionView(
            long version, String status, Map<String, Object> config,
            String configSha256, Instant publishedAt) {
    }

    public record PolicySnapshotView(
            long version, Map<String, Object> config, String configSha256,
            Instant publishedAt, List<RuleView> rules, String signature) {
    }

    public record RuleView(
            String ruleCode, String ruleType, String category, String severity, String action) {
    }
}
