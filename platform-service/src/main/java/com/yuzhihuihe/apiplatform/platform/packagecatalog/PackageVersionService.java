package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PackageVersionService {

    private static final BigDecimal TARGET_MARGIN = new BigDecimal("0.35000");
    // 参见 PackageActivationService.CAPABILITIES：VIDEO 别名由
    // gateway.model_usage_price 按秒计价、IMAGE 别名按张计价、音频别名按
    // AUDIO_SECOND 计价，因此这里也必须允许发布它们。IMAGE_GEN 是**生成**
    // 能力，与输入模态 IMAGE 不同：生图别名携带 ["TEXT","IMAGE","IMAGE_GEN"]，
    // 其中 TEXT/IMAGE 描述输入、IMAGE_GEN 描述产出。音频的三个能力名
    // （AUDIO_TRANSCRIBE / AUDIO_SYNTHESIS / LIP_SYNC）同样是产出描述，
    // 由网关 V93 建立。三个白名单（本类、GatewayAdminService、
    // PackageActivationService）必须同步，否则一个版本会在平台侧发布成功、
    // 在网关侧发放时被拒。
    private static final Set<String> ALLOWED_CAPABILITIES =
            Set.of("TEXT", "IMAGE", "FILE", "VIDEO", "IMAGE_GEN",
                    "AUDIO_TRANSCRIBE", "AUDIO_SYNTHESIS", "LIP_SYNC");
    private static final Set<String> PLAN_KINDS = Set.of("STANDARD", "FREE_CLAIM");
    private static final Set<String> PLAN_STATUSES = Set.of("ACTIVE", "ARCHIVED");
    private static final int MAX_PLAN_NAME_LENGTH = 100;
    private static final int MAX_PLAN_DESCRIPTION_LENGTH = 500;
    private static final int MAX_CATALOG_PAGE_SIZE = 100;
    private static final int MODEL_REFERENCE_PREVIEW_LIMIT = 10;

    private final PackageVersionRepository repository;
    private final ObjectMapper objectMapper;
    private final ProviderPriceClient providerPriceClient;
    private final ProviderUnitCostClient providerUnitCostClient;
    private final ProviderRetailPriceClient providerRetailPriceClient;
    private final ModelUsageDimensionRepository dimensionRepository;
    private final DimensionPricePolicyRepository pricePolicyRepository;
    private final PackageMarginPolicy marginPolicy;

    PackageVersionService(
            PackageVersionRepository repository,
            ObjectMapper objectMapper,
            ProviderPriceClient providerPriceClient,
            ProviderUnitCostClient providerUnitCostClient,
            ProviderRetailPriceClient providerRetailPriceClient,
            ModelUsageDimensionRepository dimensionRepository,
            DimensionPricePolicyRepository pricePolicyRepository,
            PackageMarginPolicy marginPolicy) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.providerPriceClient = providerPriceClient;
        this.providerUnitCostClient = providerUnitCostClient;
        this.providerRetailPriceClient = providerRetailPriceClient;
        this.dimensionRepository = dimensionRepository;
        this.pricePolicyRepository = pricePolicyRepository;
        this.marginPolicy = marginPolicy;
    }

    /**
     * 判定一组权限属于 token 权益还是秒数权益。
     *
     * <p>该维度由模型推导得出，而不是取自
     * 运维提供的标志，因此一个版本不能在列出 token 别名的同时
     * 声称按秒计。同一版本中的每个模型必须一致，因为单个
     * 版本只携带一个需要管控的额度列。
     */
    private String resolveDimension(Set<ModelPermission> models) {
        Map<String, String> dimensions = dimensionRepository.dimensionByModelCode();
        Set<String> resolved = new HashSet<>();
        for (ModelPermission model : models) {
            resolved.add(dimensions.getOrDefault(model.modelCode(), "TOKEN"));
        }
        // 方案 C：视频秒与音频秒可以共存于一个版本。
        //
        // 两者都是"秒"，而且自 V92 起**各自有一列额度**
        // （total_token_quota 是视频秒，audio_seconds_quota 是音频秒），
        // 因此不再需要把音频折进视频池——一次语音识别扣的是音频秒那一列，
        // 不会挪用视频秒。主维度仍然是 VIDEO_SECOND：它承载
        // total_token_quota 的含义，音频秒走自己那一列。
        //
        // 其它组合仍然一律拒绝：token 按个、图片按张、秒按秒，三者的额度列
        // 在结构上不是同一列，混在一起会让一类用量悄悄花掉另一类的额度。
        if (resolved.equals(Set.of("VIDEO_SECOND", "AUDIO_SECOND"))) {
            return "VIDEO_SECOND";
        }
        if (resolved.size() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "mixed_usage_dimensions_in_package");
        }
        return resolved.iterator().next();
    }

    /**
     * 构建毛利率守卫用来为该版本定价的成本基准。
     *
     * <p>token 版本按每百万 token 的提供方成本定价；秒
     * 版本则取自费率卡的有效每秒成本，后者已经
     * 包含了针对上游按失败计费的生成所做的重试余量。
     * 两者存放在不同的表中，因为每秒成本无法
     * 用每百万 token 的列来表达。
     *
     * <p>缩放系数与两个下限均来自
     * {@code platform.package_dimension_price_policy}，在此处读取 —— 创建时读一次、
     * 发布时再读一次 —— 因此运维的编辑在下次发布时生效，而不是
     * 在下次重启时生效。没有对应行的维度保留
     * {@link PackageMarginPolicy#fallbackPolicy} 中记录的兜底值；存在但不可用的行会阻止
     * 发布，而不是回退到更宽松的下限。
     */
    PackageMarginPolicy.DimensionPolicy dimensionPolicy(String dimension, Set<String> modelCodes) {
        Map<String, BigDecimal> unitCosts;
        if ("TOKEN".equals(dimension)) {
            unitCosts = providerPriceClient.nonPromotionalOutputPrices(modelCodes);
        } else if ("VIDEO_SECOND".equals(dimension) || "IMAGE".equals(dimension)
                || "AUDIO_SECOND".equals(dimension)) {
            // 三个生成维度共用同一条按单位定价的读取路径。它们的单位不同
            // （分/秒、分/张、分/秒），而网关的
            // /internal/prices/effective-unit-cost 对三者一视同仁地解析
            // ——它只回答「这个别名的有效单位成本是多少」，单位由维度决定。
            // 因此这里要加的是**维度词**，而不是一条新的取数逻辑。
            unitCosts = providerUnitCostClient.effectiveUnitCosts(modelCodes);
        } else {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "unsupported_usage_dimension");
        }
        return pricePolicyRepository.find(dimension)
                .map(row -> PackageMarginPolicy.fromPolicyRow(
                        dimension, unitCosts, row.quotaUnitsPerCostUnit(),
                        row.minimumMargin(), row.warningMargin(), row.unitLabel()))
                .orElseGet(() -> PackageMarginPolicy.fallbackPolicy(dimension, unitCosts));
    }

    /**
     * 按**每一个**维度各算一次毛利，再合并成一份数字（方案 C）。
     *
     * <p>为什么不能只算主维度：混合版本的守卫只看视频秒的成本，看不见音频那一半
     * ——一个亏损的音频别名会搭着视频套餐发出去。因此每个维度都用自己的费率卡、
     * 用自己的下限判定一次，**任何一个不过就拒绝**。
     *
     * <p>每个维度还必须用**自己的额度**来摊成本，而不是版本主维度的那一个。
     * 用错额度不会让任何东西失败，它只会让守卫变松：一个卖 100 视频秒 + 600 音频秒
     * 的版本若把 100 拿去摊音频那一半的成本，只有 100 秒被计价，另外 500 秒
     * 是守卫看不见的免费额度。这正是"每个维度各算一次"这句话的字面要求
     * ——各自拿自己的额度、自己的费率卡、自己的下限。
     *
     * <p>合并规则刻意与 {@link PackageMarginPolicy#assess} 逐字一致：
     * 各维度成本相加后向上取整到整分，毛利率 = (售价 − 成本) ÷ 售价、保留 5 位
     * HALF_UP。不一致的话，同一个套餐在创建与发布两条路径上会报出两个不同的毛利率。
     *
     * <p>单维度版本的行为与改动前完全相同：只有一个分组，
     * 重算出的数字与当次评估的数字一模一样。
     *
     * @param audioSecondsQuota 第二份额度；为 null 表示这个版本不卖语音。
     *        只有主维度不是 {@code AUDIO_SECOND} 时它才是音频别名的那份额度
     *        ——纯音频版本的音频秒就住在 {@code totalQuota} 里
     *        （与网关侧 {@code LockedPackage#usesSecondBucket} 同一个判据）。
     */
    private PackageMarginPolicy.MarginAssessment assessEveryDimension(
            long priceAmount, long totalQuota, Long audioSecondsQuota,
            Set<ModelPermission> models) {
        Map<String, String> dimensions = dimensionRepository.dimensionByModelCode();
        Map<String, Set<ModelPermission>> byDimension = new java.util.LinkedHashMap<>();
        for (ModelPermission model : models) {
            byDimension
                    .computeIfAbsent(dimensions.getOrDefault(model.modelCode(), "TOKEN"),
                            key -> new java.util.LinkedHashSet<>())
                    .add(model);
        }
        String versionDimension = resolveDimension(models);
        long estimatedCost = 0;
        boolean warning = false;
        for (Map.Entry<String, Set<ModelPermission>> entry : byDimension.entrySet()) {
            Set<String> codes = entry.getValue().stream()
                    .map(ModelPermission::modelCode)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            long quotaForDimension = "AUDIO_SECOND".equals(entry.getKey())
                    && !"AUDIO_SECOND".equals(versionDimension)
                    ? audioSecondsQuota
                    : totalQuota;
            PackageMarginPolicy.MarginAssessment assessed = marginPolicy.assess(
                    priceAmount, quotaForDimension, entry.getValue(),
                    dimensionPolicy(entry.getKey(), codes));
            estimatedCost = Math.addExact(estimatedCost, assessed.estimatedCostAmount());
            warning = warning || assessed.warning();
            if (!assessed.publishable()) {
                throw new PackageMarginPolicy.MarginPolicyException("token_margin_below_minimum");
            }
        }
        if (priceAmount == 0) {
            return new PackageMarginPolicy.MarginAssessment(null, estimatedCost, false, true);
        }
        BigDecimal margin = BigDecimal.valueOf(priceAmount - estimatedCost)
                .divide(BigDecimal.valueOf(priceAmount), 5, java.math.RoundingMode.HALF_UP);
        return new PackageMarginPolicy.MarginAssessment(margin, estimatedCost, warning, true);
    }

    /**
     * 拒绝批准这样的版本：网关会以低于其自身
     * 成本基准的价格出售该版本的别名。
     *
     * <p>毛利率守卫依据提供方<em>成本</em>为版本定价。客户
     * 实际被收取的费用来自网关的 {@code model_billing_price}
     * 表，该表通过单独的管理端点写入，完全可能与前者不一致。
     * 它确实不一致过：{@code text-pro} 与 {@code text-standard} 的标价低于
     * 守卫据以批准该套餐的成本基准，因此一个版本可以
     * 在发布时报告 30-40% 的稳健毛利率，而这些别名的每百万输出 token
     * 都在亏钱。
     *
     * <p>只交叉校验 token 维度。视频别名按设计没有
     * {@code model_billing_price} 行 —— 其标价存在于守卫已经读取的费率卡
     * 上 —— 因此把这一检查应用到秒维度，会因为一行本应缺失的记录
     * 而拒绝每一个正确的视频版本。
     *
     * <p>标价行缺失是失败而不是放行：网关根本无法
     * 针对该别名给请求定价，因此授予它的套餐无法
     * 被诚实地提供。
     */
    private void requireChargeableRetailPrice(String usageDimension, Set<String> modelCodes) {
        if (!"TOKEN".equals(usageDimension)) {
            return;
        }
        Map<String, BigDecimal> costBasis = providerPriceClient.nonPromotionalOutputPrices(modelCodes);
        Map<String, ProviderRetailPriceClient.RetailPrice> retail =
                providerRetailPriceClient.currentRetailPrices(modelCodes);
        for (String modelCode : modelCodes) {
            ProviderRetailPriceClient.RetailPrice price = retail.get(modelCode);
            if (price == null) {
                throw new PackageMarginPolicy.MarginPolicyException(
                        "provider_retail_price_missing:" + modelCode);
            }
            BigDecimal cost = costBasis.get(modelCode);
            if (cost == null) {
                // 成本守卫已经会拒绝这种情况；保留此检查是为了让这两项校验
                // 不会因重排而出现漏洞。
                throw new PackageMarginPolicy.MarginPolicyException(
                        "provider_price_missing:" + modelCode + ":TOKEN");
            }
            if (price.outputAsDecimal().compareTo(cost) < 0) {
                throw new PackageMarginPolicy.MarginPolicyException(
                        "provider_retail_below_cost:" + modelCode
                                + ":retail=" + price.outputPricePerMillion()
                                + ":cost=" + cost.stripTrailingZeros().toPlainString());
            }
        }
    }

    public List<PackageView> listPublishedPlans() {
        List<PackageView> plans = new ArrayList<>();
        repository.findPublishedPlans().forEach(row -> plans.add(packageView(row)));

        List<PackageView> withModels = new ArrayList<>(plans.size());
        for (PackageView plan : plans) {
            withModels.add(plan.withModels(loadModels(plan.versionId())));
        }
        withModels.sort(Comparator.comparingInt(PackageView::displayOrder)
                .thenComparing(PackageView::name, String.CASE_INSENSITIVE_ORDER));
        return withModels;
    }

    public PackageView getPublishedPlan(String code) {
        return listPublishedPlans().stream()
                .filter(plan -> plan.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "package_plan_not_found"));
    }

    public PlanCatalogPage listPlanCatalog(
            int page,
            int size,
            String query,
            String status) {
        if (page < 0 || size <= 0 || size > MAX_CATALOG_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_package_catalog_page");
        }
        String normalizedStatus = normalizeCatalogStatus(status);
        String search = normalizeCatalogSearch(query);
        long total = repository.countCatalogPlans(search, normalizedStatus);
        List<PackageVersionRepository.CatalogPlanRow> planRows = repository.findCatalogPlans(
                search, normalizedStatus, size, Math.multiplyExact((long) page, size));
        List<PackageVersionRepository.CatalogVersionRow> versionRows = repository.findCatalogVersions(
                planRows.stream().map(PackageVersionRepository.CatalogPlanRow::id).toList());
        Map<UUID, List<PlanVersionView>> versionsByPlan = new LinkedHashMap<>();
        for (PackageVersionRepository.CatalogVersionRow version : versionRows) {
            versionsByPlan.computeIfAbsent(version.planId(), ignored -> new ArrayList<>())
                    .add(planVersionView(version));
        }
        List<PlanCatalogItem> items = planRows.stream()
                .map(plan -> new PlanCatalogItem(
                        plan.code(), plan.name(), plan.description(), plan.kind(), plan.status(),
                        plan.displayOrder(), plan.createdAt(), plan.updatedAt(),
                        List.copyOf(versionsByPlan.getOrDefault(plan.id(), List.of()))))
                .toList();
        return new PlanCatalogPage(items, total, page, size);
    }

    public ModelReferenceView modelReferences(String modelCode) {
        if (modelCode == null || modelCode.isBlank() || modelCode.length() > 128) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_code");
        }
        long referenceCount = repository.countModelReferences(modelCode);
        List<PackageModelReference> references = repository
                .findModelReferences(modelCode, MODEL_REFERENCE_PREVIEW_LIMIT)
                .stream()
                .map(row -> new PackageModelReference(
                        row.planCode(), row.planName(), row.version(), row.versionStatus()))
                .toList();
        return new ModelReferenceView(modelCode, referenceCount, references);
    }

    @Transactional
    public PlanResult createPlan(CreatePlanRequest request, UUID actorUserId) {
        requireActor(actorUserId);
        String name = normalizeName(request == null ? null : request.name());
        String description = normalizeDescription(request == null ? null : request.description());
        String kind = normalizeKind(request == null ? null : request.kind());
        if ("FREE_CLAIM".equals(kind)) {
            repository.lockFreeClaimCatalog();
            if (repository.activeFreeClaimExists(null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "active_free_package_exists");
            }
        }
        UUID planId = UUID.randomUUID();
        String code = "PKG_" + planId.toString().replace("-", "").toUpperCase(Locale.ROOT);
        int displayOrder = repository.nextDisplayOrder();
        PackageVersionRepository.PlanRow plan = new PackageVersionRepository.PlanRow(
                planId, code, name, description, kind, "ACTIVE", displayOrder);
        repository.insertPlan(planId, code, name, description, kind, displayOrder);
        repository.insertPlanCreatedAudit(actorUserId, plan);
        return planResult(plan);
    }

    @Transactional
    public CreatedVersion createVersion(CreateVersionRequest request, UUID actorUserId) {
        requireActor(actorUserId);
        validateRequest(request);
        PackageVersionRepository.PlanRow plan = lockPlan(request.planCode());
        validateVersionKind(plan.kind(), request.priceAmount(), request.oncePerUser());
        Set<String> modelCodes = request.models().stream()
                .map(ModelPermission::modelCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String usageDimension = resolveDimension(request.models());
        // 单位检查在价格检查之前：用错单位写下的速率并不是一种
        // 定价意见，把它当作毛利率失败来拒绝会把运维引向
        // 错误的字段。两者都会抛出相同的 422 {code,message} 响应封装。
        PackageRateCeilingPolicy.requireSoldRate(
                usageDimension, request.tokensPerMinute(), request.models());
        PackageMarginPolicy.MarginAssessment margin = assessEveryDimension(
                request.priceAmount(), request.totalTokenQuota(), request.audioSecondsQuota(),
                request.models());
        if (!margin.publishable()) {
            throw new PackageMarginPolicy.MarginPolicyException("token_margin_below_minimum");
        }
        requireChargeableRetailPrice(usageDimension, modelCodes);

        Integer nextVersion = repository.nextVersion(plan.id());
        UUID versionId = UUID.randomUUID();
        repository.insertVersion(
                versionId, plan.id(), nextVersion, request.priceAmount(), request.totalTokenQuota(),
                request.audioSecondsQuota(),
                request.durationDays(), request.maxApiKeys(), request.requestsPerMinute(),
                request.tokensPerMinute(), request.maxConcurrency(), TARGET_MARGIN,
                request.dailyRequestLimit(), request.maxOutputTokens(), request.maxFileSizeBytes(),
                request.oncePerUser(), margin.estimatedCostAmount(), margin.marginRatio(), margin.warning(),
                request.stockTotal(), request.lowStockThreshold(), request.maxPurchasesPerUser(),
                usageDimension);
        insertModels(versionId, request.models());
        repository.insertCreatedAudit(actorUserId, versionId, request.planCode(), nextVersion);
        return new CreatedVersion(versionId, request.planCode(), nextVersion, "DRAFT", margin);
    }

    @Transactional
    public CreatedVersion publish(String planCode, int version, UUID actorUserId) {
        requireActor(actorUserId);
        PackageVersionRepository.DraftRow draft = repository.lockDraft(planCode, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "package_version_not_draft"));
        validateVersionKind(draft.planKind(), draft.priceAmount(), draft.oncePerUser());
        Set<ModelPermission> models = new HashSet<>(loadModels(draft.id()));
        Set<String> codes = models.stream().map(ModelPermission::modelCode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        // 从持久化的模型重新推导维度，而不是信任
        // 存储的列，这样在某个模型的维度变更之前写入的版本
        // 就无法以错误的单位发布。
        String usageDimension = resolveDimension(models);
        if (!usageDimension.equals(draft.usageDimension())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "package_version_dimension_changed");
        }
        // 发布时重新检查单位，理由与重新推导维度相同：在此守卫
        // 存在之前写入的草稿，或经由任何未走 createVersion 的路径
        // 写入的草稿，都不得把 token 速率放进秒数列。
        PackageRateCeilingPolicy.requireSoldRate(
                usageDimension, draft.tokensPerMinute(), models);
        // 第二份额度同理由复检：它必须与发布那一刻的模型集合一致。
        requireAudioSecondsBucketOnPublish(
                usageDimension, models, draft.audioSecondsQuota());
        PackageMarginPolicy.MarginAssessment margin = assessEveryDimension(
                draft.priceAmount(), draft.totalTokenQuota(), draft.audioSecondsQuota(), models);
        if (!margin.publishable()) {
            throw new PackageMarginPolicy.MarginPolicyException("token_margin_below_minimum");
        }
        requireChargeableRetailPrice(usageDimension, codes);
        Instant publishedAt = Instant.now();
        repository.retirePublishedVersions(draft.planId(), draft.id(), publishedAt);
        repository.publishVersion(
                draft.id(), publishedAt, margin.estimatedCostAmount(),
                margin.marginRatio(), margin.warning());
        repository.insertPublishedAudit(actorUserId, draft.id(), planCode, version);
        return new CreatedVersion(draft.id(), planCode, version, "PUBLISHED", margin);
    }

    @Transactional
    public PlanResult updatePlan(String planCode, UpdatePlanRequest request, UUID actorUserId) {
        requireActor(actorUserId);
        String name = normalizeName(request == null ? null : request.name());
        String description = normalizeDescription(request == null ? null : request.description());
        PackageVersionRepository.PlanRow plan = lockPlan(planCode);
        repository.updatePlanDetails(plan.id(), name, description);
        repository.insertPlanAudit(
                actorUserId, plan, name, description, plan.status(), "PACKAGE_PLAN_UPDATED");
        return new PlanResult(
                plan.code(), name, description, plan.kind(), plan.status(), plan.displayOrder());
    }

    @Transactional
    public PlanResult archivePlan(String planCode, UUID actorUserId) {
        return transitionPlan(planCode, actorUserId, "ACTIVE", "ARCHIVED", "PACKAGE_PLAN_ARCHIVED");
    }

    @Transactional
    public PlanResult restorePlan(String planCode, UUID actorUserId) {
        return transitionPlan(planCode, actorUserId, "ARCHIVED", "ACTIVE", "PACKAGE_PLAN_RESTORED");
    }

    private PlanResult transitionPlan(
            String planCode,
            UUID actorUserId,
            String requiredStatus,
            String targetStatus,
            String auditAction) {
        requireActor(actorUserId);
        PackageVersionRepository.PlanRow plan = lockPlan(planCode);
        if (targetStatus.equals(plan.status())) {
            return planResult(plan);
        }
        if (!requiredStatus.equals(plan.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "package_plan_status_conflict");
        }
        if ("ACTIVE".equals(targetStatus) && "FREE_CLAIM".equals(plan.kind())) {
            repository.lockFreeClaimCatalog();
            if (repository.activeFreeClaimExists(plan.id())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "active_free_package_exists");
            }
        }
        repository.updatePlanStatus(plan.id(), targetStatus);
        repository.insertPlanAudit(
                actorUserId, plan, plan.name(), plan.description(), targetStatus, auditAction);
        return new PlanResult(
                plan.code(), plan.name(), plan.description(), plan.kind(), targetStatus,
                plan.displayOrder());
    }

    private PackageVersionRepository.PlanRow lockPlan(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "package_plan_not_found");
        }
        return repository.lockPlan(planCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "package_plan_not_found"));
    }

    private void requireActor(UUID actorUserId) {
        if (actorUserId == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_admin_actor");
        }
    }

    private String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "package_plan_name_required");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_PLAN_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "package_plan_name_too_long");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_PLAN_DESCRIPTION_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "package_plan_description_too_long");
        }
        return normalized;
    }

    private String normalizeKind(String value) {
        if (value == null || value.isBlank()) {
            return "STANDARD";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PLAN_KINDS.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_plan_kind");
        }
        return normalized;
    }

    private String normalizeCatalogStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!PLAN_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_package_plan_status");
        }
        return normalized;
    }

    private String normalizeCatalogSearch(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_package_catalog_query");
        }
        return "%" + normalized
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%";
    }

    private void validateVersionKind(String kind, long priceAmount, boolean oncePerUser) {
        if ("FREE_CLAIM".equals(kind)) {
            if (priceAmount != 0 || !oncePerUser) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "invalid_free_package_version");
            }
            return;
        }
        if (priceAmount <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_version");
        }
    }

    private PlanResult planResult(PackageVersionRepository.PlanRow plan) {
        return new PlanResult(
                plan.code(), plan.name(), plan.description(), plan.kind(), plan.status(),
                plan.displayOrder());
    }

    private void insertModels(UUID versionId, Set<ModelPermission> models) {
        for (ModelPermission model : models) {
            repository.insertModel(
                    UUID.randomUUID(), versionId, model.modelCode(), model.modelTokenQuota(),
                    toJson(model.capabilities()));
        }
    }

    private List<ModelPermission> loadModels(UUID versionId) {
        List<ModelPermission> models = new ArrayList<>();
        repository.findModels(versionId).forEach(row -> models.add(new ModelPermission(
                row.modelCode(), row.modelTokenQuota(), fromJson(row.capabilities()))));
        return models;
    }

    private void validateRequest(CreateVersionRequest request) {
        if (request == null || request.planCode() == null || request.planCode().isBlank()
                || request.priceAmount() < 0 || request.totalTokenQuota() <= 0
                || request.audioSecondsQuota() != null && request.audioSecondsQuota() <= 0
                || request.durationDays() <= 0 || request.maxApiKeys() <= 0
                || request.requestsPerMinute() <= 0 || request.tokensPerMinute() <= 0
                || request.maxConcurrency() <= 0 || request.maxFileSizeBytes() <= 0
                || request.stockTotal() != null && request.stockTotal() < 0
                || request.lowStockThreshold() < 0
                || request.stockTotal() != null && request.lowStockThreshold() > request.stockTotal()
                || request.maxPurchasesPerUser() != null && request.maxPurchasesPerUser() <= 0
                || request.dailyRequestLimit() != null && request.dailyRequestLimit() <= 0
                || request.maxOutputTokens() != null && request.maxOutputTokens() <= 0
                || request.models() == null || request.models().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_package_version");
        }
        // 下面两条都要按 modelCode 查维度，而 resolveDimension 同样假设元素存在，
        // 因此在它们之前先把"元素本身合法"这一层挡掉：否则一个 null 元素会变成
        // 500 而不是它本来的 422 invalid_model_permission。
        for (ModelPermission permission : request.models()) {
            if (permission == null || permission.modelCode() == null || permission.modelCode().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_permission");
            }
        }
        requireAudioSecondsBucketMatchesTheModels(request);
        // 每个模型的自额度必须与**它自己的**额度列比较。用错了列不会失败得很明显：
        // 一个混合版本里音频别名的自额度会去和视频秒比大小，于是要么被一个
        // 与它无关的上限拒绝，要么被放行成一个远超音频总额度、永远无法兑现的承诺。
        Map<String, String> dimensions = dimensionRepository.dimensionByModelCode();
        String versionDimension = resolveDimension(request.models());
        Set<String> modelCodes = new HashSet<>();
        for (ModelPermission permission : request.models()) {
            if (permission == null || permission.modelCode() == null || permission.modelCode().isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_permission");
            }
            String modelDimension = dimensions.getOrDefault(permission.modelCode(), "TOKEN");
            long cap = modelDimension.equals(versionDimension)
                    ? request.totalTokenQuota()
                    : request.audioSecondsQuota();
            if (!modelCodes.add(permission.modelCode())
                    || permission.modelTokenQuota() != null && permission.modelTokenQuota() <= 0
                    || permission.modelTokenQuota() != null
                            && permission.modelTokenQuota() > cap
                    || permission.capabilities() == null || permission.capabilities().isEmpty()
                    || !ALLOWED_CAPABILITIES.containsAll(permission.capabilities())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_model_permission");
            }
        }
    }

    /**
     * 第二份额度必须与版本实际卖的东西一致（方案 C）。
     *
     * <p>两个方向都是失败：
     *
     * <ul>
     *   <li><b>有音频别名却没有这一列</b> —— 网关会给音频别名立一份额度为空的桶，
     *       于是每一次 ASR/TTS/换口型请求都以 {@code quota_exhausted} 结束。
     *       客户付了钱、套餐看起来包含语音、而语音永远不可用。
     *   <li><b>没有音频别名却有这一列</b> —— 一个无人能消费的额度，
     *       而且它会在控制台上显示成一个"包含语音"的承诺（第 6 步要处理显示，
     *       但数据本身就不该存在）。
     *   <li><b>纯音频版本却带着它</b> —— 那种版本的主额度列本身就是音频秒
     *       （total_token_quota 的含义由 usage_dimension 决定，V42:16-22），
     *       再填一份会让"这次请求扣哪一列"出现两个都说得通的答案。
     *       网关侧 V97 的 CHECK 同样拒绝它，但在这里拒绝能给出一个
     *       运维看得懂的 422，而不是一次发放失败。
     * </ul>
     */
    private void requireAudioSecondsBucketMatchesTheModels(CreateVersionRequest request) {
        requireAudioSecondsBucket(
                resolveDimension(request.models()),
                (int) request.models().stream()
                        .filter(model -> "AUDIO_SECOND".equals(
                                dimensionRepository.dimensionByModelCode()
                                        .getOrDefault(model.modelCode(), "TOKEN")))
                        .count() > 0,
                request.audioSecondsQuota());
    }

    /**
     * 发布时的复检入口：与创建时同一条规则，但读的是**已持久化的**草稿。
     *
     * <p>理由与 {@code package_version_dimension_changed} 完全相同——一个
     * 别名的维度可能在草稿写下之后被改动，而"这个版本带不带音频秒那一列"
     * 必须依据发布那一刻的真实模型重新成立，否则一个在该守卫存在之前写下的
     * 草稿会带着缺失或多余的音频额度被发布出去。
     */
    private void requireAudioSecondsBucketOnPublish(String usageDimension, Set<ModelPermission> models,
            Long audioSecondsQuota) {
        Map<String, String> dimensions = dimensionRepository.dimensionByModelCode();
        boolean hasAudioModel = models.stream()
                .anyMatch(model -> "AUDIO_SECOND".equals(
                        dimensions.getOrDefault(model.modelCode(), "TOKEN")));
        requireAudioSecondsBucket(usageDimension, hasAudioModel, audioSecondsQuota);
    }

    private void requireAudioSecondsBucket(
            String versionDimension, boolean hasAudioModel, Long audioSecondsQuota) {
        if ("AUDIO_SECOND".equals(versionDimension)) {
            if (audioSecondsQuota != null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "audio_seconds_quota_on_a_pure_audio_version");
            }
            return;
        }
        if (hasAudioModel && audioSecondsQuota == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "audio_alias_requires_audio_seconds_quota");
        }
        if (!hasAudioModel && audioSecondsQuota != null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "audio_seconds_quota_without_an_audio_alias");
        }
    }

    private String toJson(Set<String> capabilities) {
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("capability_serialization_failed", exception);
        }
    }

    private Set<String> fromJson(String capabilities) {
        try {
            return objectMapper.readValue(capabilities, new TypeReference<Set<String>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("capability_deserialization_failed", exception);
        }
    }

    private PackageView packageView(PackageVersionRepository.PublishedPlanRow row) {
        return new PackageView(
                row.code(), row.name(), row.description(), row.kind(), row.displayOrder(),
                row.versionId(), row.version(),
                row.priceAmount(), row.currency(), row.totalTokenQuota(), row.audioSecondsQuota(),
                row.durationDays(),
                row.maxApiKeys(), row.requestsPerMinute(), row.tokensPerMinute(),
                row.maxConcurrency(), row.dailyRequestLimit(), row.maxOutputTokens(),
                row.maxFileSizeBytes(), row.oncePerUser(), row.estimatedCostAmount(),
                row.estimatedMarginRatio(), row.marginWarning(), row.stockTotal(), row.stockSold(),
                row.stockReserved(), row.lowStockThreshold(), row.maxPurchasesPerUser(),
                row.remainingStock(), row.soldOut(), row.lowStock(), row.usageDimension(),
                List.of());
    }

    private PlanVersionView planVersionView(PackageVersionRepository.CatalogVersionRow row) {
        return new PlanVersionView(
                row.id(), row.version(), row.status(), row.priceAmount(), row.currency(),
                row.totalTokenQuota(), row.audioSecondsQuota(),
                row.durationDays(), row.maxApiKeys(),
                row.requestsPerMinute(), row.tokensPerMinute(), row.maxConcurrency(),
                row.dailyRequestLimit(), row.maxOutputTokens(), row.maxFileSizeBytes(),
                row.oncePerUser(), row.estimatedCostAmount(), row.estimatedMarginRatio(),
                row.marginWarning(), row.stockTotal(), row.stockSold(), row.stockReserved(),
                row.lowStockThreshold(), row.maxPurchasesPerUser(), row.remainingStock(),
                row.soldOut(), row.lowStock(), row.modelCount(), row.publishedAt(),
                row.retiredAt(), row.createdAt(), row.usageDimension());
    }

    public record ModelPermission(String modelCode, Long modelTokenQuota, Set<String> capabilities) {
    }

    public record ModelReferenceView(
            String modelCode, long referenceCount, List<PackageModelReference> references) {
    }

    public record PackageModelReference(
            String planCode, String planName, int version, String versionStatus) {
    }

    /**
     * @param usageDimension TOKEN 或 VIDEO_SECOND。为 null 表示 TOKEN，因此每个
     *        未发送该字段的既有客户端都保持行为不变。
     * @param audioSecondsQuota 方案 C 的第二份额度。只有当本版本既卖视频秒又含
     *        音频别名时才非空；为 null 表示"这个版本不卖语音"。既有客户端不发送
     *        它，因此行为完全不变。
     */
    public record CreateVersionRequest(
            String planCode,
            long priceAmount,
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
            Long stockTotal,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            String usageDimension,
            Set<ModelPermission> models) {

        public CreateVersionRequest {
            if (usageDimension == null || usageDimension.isBlank()) {
                usageDimension = "TOKEN";
            }
        }

        /** 不带维度字段的便捷形式：始终是 token 版本。 */
        public CreateVersionRequest(
                String planCode,
                long priceAmount,
                long totalTokenQuota,
                int durationDays,
                int maxApiKeys,
                int requestsPerMinute,
                long tokensPerMinute,
                int maxConcurrency,
                Integer dailyRequestLimit,
                Integer maxOutputTokens,
                long maxFileSizeBytes,
                boolean oncePerUser,
                Long stockTotal,
                long lowStockThreshold,
                Integer maxPurchasesPerUser,
                Set<ModelPermission> models) {
            this(planCode, priceAmount, totalTokenQuota, null, durationDays, maxApiKeys,
                    requestsPerMinute, tokensPerMinute, maxConcurrency, dailyRequestLimit,
                    maxOutputTokens, maxFileSizeBytes, oncePerUser, stockTotal,
                    lowStockThreshold, maxPurchasesPerUser, "TOKEN", models);
        }

        /** 供测试和简单调用方使用的便捷形式。 */
        public CreateVersionRequest(
                String planCode,
                long priceAmount,
                long totalTokenQuota,
                int durationDays,
                int maxApiKeys,
                int requestsPerMinute,
                long tokensPerMinute,
                int maxConcurrency,
                Integer dailyRequestLimit,
                Integer maxOutputTokens,
                long maxFileSizeBytes,
                boolean oncePerUser,
                Set<ModelPermission> models) {
            this(planCode, priceAmount, totalTokenQuota, null, durationDays, maxApiKeys,
                    requestsPerMinute, tokensPerMinute, maxConcurrency, dailyRequestLimit,
                    maxOutputTokens, maxFileSizeBytes, oncePerUser, null, 0, null,
                    "TOKEN", models);
        }
    }

    public record CreatedVersion(
            UUID id,
            String planCode,
            int version,
            String status,
            PackageMarginPolicy.MarginAssessment margin) {
    }

    public record CreatePlanRequest(String name, String description, String kind) {
    }

    public record UpdatePlanRequest(String name, String description) {
    }

    public record PlanResult(
            String code,
            String name,
            String description,
            String kind,
            String status,
            int displayOrder) {
    }

    public record PlanCatalogPage(
            List<PlanCatalogItem> items,
            long total,
            int page,
            int size) {
    }

    public record PlanCatalogItem(
            String code,
            String name,
            String description,
            String kind,
            String status,
            int displayOrder,
            Instant createdAt,
            Instant updatedAt,
            List<PlanVersionView> versions) {
    }

    public record PlanVersionView(
            UUID versionId,
            int version,
            String status,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            /** 方案 C 的第二份额度（音频秒）；null = 这个版本不卖语音。 */
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
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock,
            int modelCount,
            Instant publishedAt,
            Instant retiredAt,
            Instant createdAt,
            /**
             * {@code totalTokenQuota}、各模型的子额度以及
             * {@code tokensPerMinute} 的计数单位：{@code TOKEN} = token 与
             * token 速率，{@code VIDEO_SECOND} = 秒与每分钟秒数上限。管理
             * 控制台需要它来正确标注速率（面向用户的映射见
             * web-console/src/views/user/PlansView.vue）。
             */
            String usageDimension) {
    }

    public record PackageView(
            String code,
            String name,
            String description,
            String kind,
            int displayOrder,
            UUID versionId,
            int version,
            long priceAmount,
            String currency,
            long totalTokenQuota,
            /** 方案 C 的第二份额度（音频秒）；null = 这个版本不卖语音。 */
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
            Long estimatedCostAmount,
            BigDecimal estimatedMarginRatio,
            boolean marginWarning,
            Long stockTotal,
            long stockSold,
            long stockReserved,
            long lowStockThreshold,
            Integer maxPurchasesPerUser,
            Long remainingStock,
            boolean soldOut,
            boolean lowStock,
            String usageDimension,
            List<ModelPermission> models) {

        PackageView withModels(List<ModelPermission> permissions) {
            return new PackageView(
                    code, name, description, kind, displayOrder, versionId, version,
                    priceAmount, currency,
                    totalTokenQuota, audioSecondsQuota, durationDays, maxApiKeys, requestsPerMinute,
                    tokensPerMinute, maxConcurrency, dailyRequestLimit, maxOutputTokens,
                    maxFileSizeBytes, oncePerUser, estimatedCostAmount,
                    estimatedMarginRatio, marginWarning, stockTotal, stockSold,
                    stockReserved, lowStockThreshold, maxPurchasesPerUser, remainingStock,
                    soldOut, lowStock, usageDimension, permissions);
        }
    }
}
