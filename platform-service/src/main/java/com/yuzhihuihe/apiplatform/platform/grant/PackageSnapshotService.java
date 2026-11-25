package com.yuzhihuihe.apiplatform.platform.grant;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackageSnapshotService {

    private final PackageSnapshotRepository repository;
    private final ObjectMapper objectMapper;

    PackageSnapshotService(PackageSnapshotRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public PackageSnapshot loadPublished(UUID packageVersionId) {
        if (packageVersionId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "published_package_version_not_found");
        }
        PackageSnapshotRepository.PackageVersionRow base = repository.findPublishedVersion(packageVersionId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "published_package_version_not_found"));
        List<ModelPermission> models = repository.findModelPermissions(packageVersionId).stream()
                .map(row -> new ModelPermission(
                        row.modelCode(), row.modelTokenQuota(), capabilities(row.capabilitiesJson()),
                        row.usageDimension()))
                .toList();
        return new PackageSnapshot(
                base.packageVersionId(), base.packageCode(), base.packageName(), base.packageKind(),
                base.priceAmount(),
                base.currency(), base.totalTokenQuota(), base.audioSecondsQuota(),
                base.durationDays(), base.maxApiKeys(),
                base.requestsPerMinute(), base.tokensPerMinute(), base.maxConcurrency(),
                base.dailyRequestLimit(), base.maxOutputTokens(), base.maxFileSizeBytes(),
                base.oncePerUser(), base.usageDimension(), List.copyOf(models));
    }

    private Set<String> capabilities(String json) {
        try {
            Set<String> values = objectMapper.readValue(json, new TypeReference<Set<String>>() { });
            return Collections.unmodifiableSet(new TreeSet<>(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("package_capability_deserialization_failed", exception);
        }
    }

    /**
     * @param usageDimension TOKEN 或 VIDEO_SECOND/IMAGE，会被传递到网关，以便网关
     *        强制要求秒数套餐绝不授权 token 模型（反之亦然）。
     *        下游会把 Null 规范化为 TOKEN，这正是省略它并非显式失败、
     *        而是静默失败的原因：视频套餐就这么到来并声称自己是 token。
     * @param audioSecondsQuota 方案 C 的第二份额度，只有当本版本同时卖了视频秒与
     *        音频秒（usageDimension = VIDEO_SECOND）时才非空。它必须随快照下发，
     *        否则网关会把音频别名当成视频别名，让一次 ASR 请求去扣视频秒——
     *        两者相差三个数量级。
     */
    public record PackageSnapshot(
            UUID packageVersionId,
            String packageCode,
            String packageName,
            String packageKind,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            Long audioSecondsQuota,
            int durationDays,
            int maxApiKeys,
            int requestsPerMinute,
            long tokensPerMinute,
            int maxConcurrency,
            Integer dailyRequestLimit,
            Integer maxOutputTokens,
            long maxFileSizeBytes,
            boolean oncePerUser,
            String usageDimension,
            List<ModelPermission> models) {
        public PackageSnapshot {
            packageKind = packageKind == null || packageKind.isBlank()
                    ? ("FREE".equals(packageCode) ? "FREE_CLAIM" : "STANDARD")
                    : packageKind;
            models = Objects.requireNonNull(models, "models").stream()
                    .map(model -> Objects.requireNonNull(model, "model"))
                    .sorted(Comparator.comparing(ModelPermission::modelCode))
                    .toList();
        }
    }

    /**
     * @param usageDimension 该模型自己的计费单位（platform.model_usage_dimension，
     *        缺省 TOKEN）。逐模型携带而不是从版本推导：混合版本里同一个版本的
     *        视频别名与音频别名单位不同，网关也没有那张表可以自己查。
     *        null 会被网关规范化为 TOKEN（与 usageDimension 同一约定）。
     */
    public record ModelPermission(
            String modelCode,
            Long modelTokenQuota,
            Set<String> capabilities,
            String usageDimension) {
        public ModelPermission {
            Objects.requireNonNull(modelCode, "modelCode");
            capabilities = Collections.unmodifiableSet(
                    new TreeSet<>(Objects.requireNonNull(capabilities, "capabilities")));
        }

        /** 维度存在之前的形式：每个模型都是它所属版本的那一种单位。 */
        public ModelPermission(String modelCode, Long modelTokenQuota, Set<String> capabilities) {
            this(modelCode, modelTokenQuota, capabilities, null);
        }
    }
}
