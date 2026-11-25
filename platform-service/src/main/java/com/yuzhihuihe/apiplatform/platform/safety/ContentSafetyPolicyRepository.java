package com.yuzhihuihe.apiplatform.platform.safety;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContentSafetyPolicyRepository {

    private final JdbcTemplate jdbc;

    ContentSafetyPolicyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long nextVersion() {
        return jdbc.queryForObject(
                "SELECT COALESCE(max(version), 0) + 1 FROM platform.content_safety_policy_version",
                Long.class);
    }

    void insertDraft(long version, String configJson, String hash) {
        jdbc.update("""
                INSERT INTO platform.content_safety_policy_version(
                    version, status, config, config_sha256, created_at, updated_at)
                VALUES (?, 'DRAFT', ?::jsonb, ?, now(), now())
                """, version, configJson, hash);
    }

    String lockDraftConfig(long version) {
        return jdbc.queryForObject("""
                SELECT config::text FROM platform.content_safety_policy_version
                WHERE version = ? AND status = 'DRAFT' FOR UPDATE
                """, String.class, version);
    }

    void publish(long version, UUID actorUserId, String hash) {
        jdbc.update("""
                UPDATE platform.content_safety_policy_version
                SET status = 'PUBLISHED', config_sha256 = ?, published_by = ?,
                    published_at = now(), updated_at = now()
                WHERE version = ? AND status = 'DRAFT'
                """, hash, actorUserId, version);
    }

    SnapshotRow publishedSnapshot() {
        return jdbc.query("""
                SELECT version, config::text, config_sha256, published_at
                FROM platform.content_safety_policy_version
                WHERE status = 'PUBLISHED'
                ORDER BY version DESC LIMIT 1
                """, (result, rowNumber) -> new SnapshotRow(
                result.getLong("version"), result.getString("config"),
                result.getString("config_sha256"),
                result.getObject("published_at", OffsetDateTime.class).toInstant()))
                .stream().findFirst().orElse(null);
    }

    java.util.List<RuleRow> publishedRules(long version) {
        return jdbc.query("""
                SELECT rule_code, rule_type, category, severity, action
                FROM platform.content_safety_rule
                WHERE policy_version = ? AND enabled = true
                ORDER BY rule_code
                """, (result, rowNumber) -> new RuleRow(
                result.getString("rule_code"), result.getString("rule_type"),
                result.getString("category"), result.getString("severity"),
                result.getString("action")), version);
    }

    record SnapshotRow(long version, String configJson, String configSha256, Instant publishedAt) {
    }

    record RuleRow(String ruleCode, String ruleType, String category, String severity, String action) {
    }
}
