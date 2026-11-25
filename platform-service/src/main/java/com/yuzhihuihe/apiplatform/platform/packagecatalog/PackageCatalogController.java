package com.yuzhihuihe.apiplatform.platform.packagecatalog;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/console/platform")
public class PackageCatalogController {

    /**
     * 控制台可以按维度筛选的取值。
     *
     * <p>它与 {@code PackageVersionService.ALLOWED_CAPABILITIES} 和数据库的
     * 维度词表是同一套枚举的三份副本之一；少一个维度会让控制台**无法按那个
     * 维度查询**，于是运维会以为那个维度没有任何套餐，而不是以为筛选器不认识它。
     */
    private static final Set<String> USAGE_DIMENSIONS =
            Set.of("TOKEN", "VIDEO_SECOND", "IMAGE", "AUDIO_SECOND");
    private static final Set<String> RESOLUTION_TIERS = Set.of("480P", "720P", "1080P");
    /**
     * {@code IMAGE} 维度出售的是尺寸档位，与视频分辨率是两套词表。
     *
     * <p>{@code 1.5K} 与网关的四档同源（{@code ImageGenerationService.SIZE_TIERS}、
     * 网关 V115、平台 V106）：厂商文档里 Seedream 5.0 pro 本来就是
     * 1K/1.5K/2K，而这里少一档的真实后果是"能提交不能上架"——别名在网关可以
     * 出图、可以计成本，却没有任何套餐能登记并发布它。
     */
    private static final Set<String> IMAGE_SIZE_TIERS = Set.of("1K", "1.5K", "2K", "4K");

    private final PackageVersionService packageVersionService;
    private final FreePackageClaimService freePackageClaimService;
    private final ModelUsageDimensionRepository modelUsageDimensions;

    public PackageCatalogController(
            PackageVersionService packageVersionService,
            FreePackageClaimService freePackageClaimService,
            ModelUsageDimensionRepository modelUsageDimensions) {
        this.packageVersionService = packageVersionService;
        this.freePackageClaimService = freePackageClaimService;
        this.modelUsageDimensions = modelUsageDimensions;
    }

    /**
     * 目录所知的每个别名都按此维度计量。
     *
     * <p>此处没有记录的别名即为 TOKEN 别名，因此运维通过网关 console 添加的视频别名
     * 必须先注册，授予它的套餐才能被
     * 发布——否则该版本会作为 token 套餐被发布，网关
     * 会在权益发放时而非发布时拒绝该权益。
     */
    @GetMapping("/admin/model-dimensions")
    List<ModelUsageDimensionRepository.ModelDimensionRow> modelDimensions() {
        return modelUsageDimensions.all();
    }

    @PutMapping("/admin/model-dimensions/{modelCode}")
    ModelUsageDimensionRepository.ModelDimensionRow registerModelDimension(
            @PathVariable String modelCode, @RequestBody ModelDimensionRequest request) {
        String code = modelCode == null ? "" : modelCode.strip();
        if (code.isEmpty() || code.length() > 128) {
            throw invalid("invalid_model_dimension");
        }
        String dimension = request.usageDimension() == null
                ? "" : request.usageDimension().strip();
        if (!USAGE_DIMENSIONS.contains(dimension)) {
            throw invalid("invalid_model_dimension");
        }
        String tier = request.resolutionTier() == null ? "" : request.resolutionTier().strip();
        // 对 token 别名，该 tier 列为空；对按秒或按张计的别名则必填：套餐毛利守卫
        // 会按某一档位的成本为这些版本定价。两个维度的档位词表不同——
        // 视频论分辨率（480P/720P/1080P），图片论尺寸（1K/2K/4K）——
        // 因此共用一个集合会让 2K 被当成非法分辨率拒掉。
        Set<String> tiers = "IMAGE".equals(dimension) ? IMAGE_SIZE_TIERS : RESOLUTION_TIERS;
        if ("TOKEN".equals(dimension) ? !tier.isEmpty() : !tiers.contains(tier)) {
            throw invalid("invalid_model_resolution_tier");
        }
        Long unitCost = request.effectiveUnitCost();
        if (unitCost != null && unitCost < 0) {
            throw invalid("invalid_model_unit_cost");
        }
        Long unitPrice = request.retailUnitPrice();
        if (unitPrice != null && unitPrice <= 0) {
            throw invalid("invalid_model_unit_price");
        }

        ModelUsageDimensionRepository.ModelDimensionRow row =
                new ModelUsageDimensionRepository.ModelDimensionRow(
                        code,
                        dimension,
                        tier,
                        unitCost,
                        unitPrice,
                        "console-" + Instant.now().toEpochMilli());
        modelUsageDimensions.upsert(row);
        return row;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    record ModelDimensionRequest(
            String usageDimension,
            String resolutionTier,
            Long effectiveUnitCost,
            Long retailUnitPrice) {
    }

    @GetMapping("/plans")
    List<PackageVersionService.PackageView> plans() {
        return packageVersionService.listPublishedPlans();
    }

    @GetMapping("/plans/{code}")
    PackageVersionService.PackageView plan(@PathVariable String code) {
        return packageVersionService.getPublishedPlan(code);
    }

    @GetMapping("/admin/package-plans")
    PackageVersionService.PlanCatalogPage packagePlans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status) {
        return packageVersionService.listPlanCatalog(page, size, query, status);
    }

    @GetMapping("/admin/package-model-references/{modelCode}")
    PackageVersionService.ModelReferenceView packageModelReferences(
            @PathVariable String modelCode) {
        return packageVersionService.modelReferences(modelCode);
    }

    @PostMapping("/admin/package-plans")
    ResponseEntity<PackageVersionService.PlanResult> createPlan(
            @RequestBody PackageVersionService.CreatePlanRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                packageVersionService.createPlan(request, actor(jwt)));
    }

    @PostMapping("/plans/free/claim")
    com.yuzhihuihe.apiplatform.platform.grant.GrantService.GrantResult claimFree(
            @AuthenticationPrincipal Jwt jwt) {
        return freePackageClaimService.claim(actor(jwt));
    }

    @GetMapping("/plans/free/claim-status")
    FreeClaimStatus freeClaimStatus(@AuthenticationPrincipal Jwt jwt) {
        return new FreeClaimStatus(freePackageClaimService.hasClaimed(actor(jwt)));
    }

    @PostMapping("/admin/plans/{code}/versions")
    ResponseEntity<PackageVersionService.CreatedVersion> createVersion(
            @PathVariable String code,
            @RequestBody PackageVersionService.CreateVersionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        if (!code.equals(request.planCode())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "package_plan_code_mismatch");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                packageVersionService.createVersion(request, actor(jwt)));
    }

    @PostMapping("/admin/plans/{code}/publish")
    PackageVersionService.CreatedVersion publish(
            @PathVariable String code,
            @RequestBody PublishRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return packageVersionService.publish(code, request.version(), actor(jwt));
    }

    @PatchMapping("/admin/plans/{code}")
    PackageVersionService.PlanResult updatePlan(
            @PathVariable String code,
            @RequestBody PackageVersionService.UpdatePlanRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return packageVersionService.updatePlan(code, request, actor(jwt));
    }

    @PostMapping("/admin/plans/{code}/archive")
    PackageVersionService.PlanResult archivePlan(
            @PathVariable String code,
            @AuthenticationPrincipal Jwt jwt) {
        return packageVersionService.archivePlan(code, actor(jwt));
    }

    @PostMapping("/admin/plans/{code}/restore")
    PackageVersionService.PlanResult restorePlan(
            @PathVariable String code,
            @AuthenticationPrincipal Jwt jwt) {
        return packageVersionService.restorePlan(code, actor(jwt));
    }

    private java.util.UUID actor(Jwt jwt) {
        return java.util.UUID.fromString(jwt.getSubject());
    }

    record PublishRequest(int version) {
    }

    record FreeClaimStatus(boolean claimed) {
    }
}
