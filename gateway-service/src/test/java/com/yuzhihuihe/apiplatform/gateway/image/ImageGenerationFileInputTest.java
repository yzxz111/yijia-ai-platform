package com.yuzhihuihe.apiplatform.gateway.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import com.yuzhihuihe.apiplatform.gateway.file.FileContentTokenProperties;
import com.yuzhihuihe.apiplatform.gateway.file.FileContentTokenSigner;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectRepository;
import com.yuzhihuihe.apiplatform.gateway.file.FileService;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 图生图里「客户端上传回来的文件 id」这条入口的准入语义。
 *
 * <p>它比 {@code image}（一个 URL）危险得多：URL 由调用方自己提供，而 file id 指向
 * **我们库里的一份文件**。因此这里必须逐条钉住四件事——两个输入源不能同时给、
 * 功能没开时不能静默降级、别人的文件读不到、以及幂等重放不去碰它（否则一张已过
 * 保留期的上传图会把一个有结果的 409 变成 404）。
 */
class ImageGenerationFileInputTest {

    private static final String ALIAS = "image-basic";
    private static final String FILE_ID = "file_0123456789abcdef0123456789abcdef";
    private static final String IMAGE_URL = "https://cdn.example.test/reference.png";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID API_KEY_ID = UUID.fromString("11000000-0000-4000-8000-0000000000aa");
    private static final UUID TENANT_ID = UUID.fromString("61000000-0000-4000-8000-0000000000aa");
    private static final UUID USER_ID = UUID.fromString("71000000-0000-4000-8000-0000000000aa");
    private static final UUID PACKAGE_ID = UUID.fromString("81000000-0000-4000-8000-0000000000aa");

    private FileService fileService;
    private ImageTaskRepository taskRepository;
    private ImageRouteRepository routeRepository;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() {
        fileService = mock(FileService.class);
        taskRepository = mock(ImageTaskRepository.class);
        routeRepository = mock(ImageRouteRepository.class);
        service = service(signer(true));
        when(taskRepository.findByRequestId(anyString())).thenReturn(Mono.empty());
    }

    @Test
    void rejectsARequestThatBringsBothAnImageUrlAndAFileId() {
        // 两个输入源同时给时不猜优先级：猜错的那一次是按张付费的，
        // 而客户拿到的图不是他要的那张。
        assertThatThrownBy(() -> service.generate(new ImageGenerationService.SubmitCommand(
                "req-both", apiKey(), ALIAS, "make it snow",
                java.util.List.of(IMAGE_URL), java.util.List.of(FILE_ID),
                "2K", 1, "url", true, false)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("invalid_image_request");
                });

        verifyNoInteractions(fileService);
    }

    @Test
    void refusesAFileIdWhenTheFeatureIsDisabled() {
        ImageGenerationService disabled = service(signer(false));

        assertThatThrownBy(() -> disabled.generate(command("req-disabled", FILE_ID)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
                    assertThat(error.getReason()).isEqualTo("image_file_input_disabled");
                });

        // 关闭时绝不退化成「把一张没有图的请求发给厂商」——那仍然按张计费，
        // 客户会为一次被忽略的参考图付费。
        verifyNoInteractions(fileService);
    }

    @Test
    void rejectsAFileIdThatDoesNotBelongToTheCaller() {
        when(fileService.requireUsable(any(), eq(FILE_ID)))
                .thenReturn(Mono.error(new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "file_not_found")));

        assertThatThrownBy(() -> service.generate(command("req-foreign", FILE_ID)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo("file_not_found");
                });

        verify(fileService).requireUsable(any(), eq(FILE_ID));
    }

    @Test
    void resolvesAnOwnedFileIdBeforeChoosingAChannel() {
        when(fileService.requireUsable(any(), eq(FILE_ID)))
                .thenReturn(Mono.just(readyImage()));
        when(routeRepository.sizeTier(ALIAS)).thenReturn(Mono.just("2K"));
        when(routeRepository.candidates(any(), eq(ALIAS), anyString())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> service.generate(command("req-owned", FILE_ID)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        // 报「没有可用渠道」说明文件那一段已经过了——它没有被挡在准入上。
                        assertThat(error.getReason()).isEqualTo("image_channel_unavailable"));

        verify(fileService).requireUsable(any(), eq(FILE_ID));
    }

    /**
     * 多参考图：多个 file id 逐个解析、保持调用方顺序，任何一个不过就整体拒绝。
     *
     * <p>2026-10-10 实测上游支持 {@code image} 数组；file id 这条入口因此同样
     * 允许 1~8 个，每个都要做归属校验 + 图片白名单 + 现签短时效地址。
     */
    @Test
    void resolvesMultipleFileIdsInOrderBeforeChoosingAChannel() {
        String secondId = "file_fedcba9876543210fedcba9876543210";
        when(fileService.requireUsable(any(), eq(FILE_ID))).thenReturn(Mono.just(readyImage()));
        when(fileService.requireUsable(any(), eq(secondId)))
                .thenReturn(Mono.just(fileWith(secondId, "image/jpeg")));
        when(routeRepository.sizeTier(ALIAS)).thenReturn(Mono.just("2K"));
        when(routeRepository.candidates(any(), eq(ALIAS), anyString())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> service.generate(new ImageGenerationService.SubmitCommand(
                "req-two-files", apiKey(), ALIAS, "merge these two references",
                java.util.List.of(), java.util.List.of(FILE_ID, secondId),
                "2K", 1, "url", true, false)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        // 报「没有可用渠道」说明两张文件都已经过了准入与解析。
                        assertThat(error.getReason()).isEqualTo("image_channel_unavailable"));

        verify(fileService).requireUsable(any(), eq(FILE_ID));
        verify(fileService).requireUsable(any(), eq(secondId));
    }

    @Test
    void leavesTheUploadedFileUntouchedWhenTheRequestIsAnIdempotentReplay() {
        when(taskRepository.findByRequestId("req-replay"))
                .thenReturn(Mono.just(failedTask("req-replay")));

        assertThatThrownBy(() -> service.generate(command("req-replay", FILE_ID)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("image_generation_previously_failed"));

        // 重放不解析文件：那张上传图可能已经过了 24 小时保留期，
        // 在这里解析会把一个应有的 409 变成 404，等于把一个已交付过的结果弄丢。
        verifyNoInteractions(fileService);
    }

    @Test
    void rejectsAFileIdThatIsNotAnImage() {
        // 这是**线上验证抓到的真实缺陷**：一开始只校验了「归属 + READY」，
        // 于是一个 PDF / 文本文件的 id 会被收下，厂商拿到一个读不到图的地址，
        // 最后以一句泛化的 500 失败——而那是一次按张付费的调用。
        when(fileService.requireUsable(any(), eq(FILE_ID)))
                .thenReturn(Mono.just(fileWith("application/pdf")));

        assertThatThrownBy(() -> service.generate(command("req-pdf", FILE_ID)).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(error.getReason()).isEqualTo("image_file_not_an_image");
                });

        verify(fileService).requireUsable(any(), eq(FILE_ID));
    }

    @Test
    void rejectsAFileIdThatIsNotShapedLikeOneOfOurs() {
        assertThatThrownBy(() -> service.generate(command("req-shape", "not-a-file-id")).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getReason()).isEqualTo("invalid_image_request"));

        verifyNoInteractions(fileService);
    }

    private ImageGenerationService service(FileContentTokenSigner signer) {
        return new ImageGenerationService(
                mock(QuotaService.class),
                taskRepository,
                routeRepository,
                mock(ImageAdapterRegistry.class),
                new ObjectMapper(),
                mock(ImageEconomicsEvidenceWriter.class),
                fileService,
                signer);
    }

    private ImageGenerationService.SubmitCommand command(String requestId, String fileId) {
        return new ImageGenerationService.SubmitCommand(
                requestId, apiKey(), ALIAS, "swap the background for a snowy mountain",
                java.util.List.of(), java.util.List.of(fileId), "2K", 1, "url", true, false);
    }

    private static ApiKeyService.ApiKeyContext apiKey() {
        return new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of(ALIAS));
    }

    private static FileObjectRepository.FileObject readyImage() {
        return fileWith("image/png");
    }

    private static FileObjectRepository.FileObject fileWith(String mimeType) {
        return fileWith(FILE_ID, mimeType);
    }

    private static FileObjectRepository.FileObject fileWith(String externalFileId, String mimeType) {
        return new FileObjectRepository.FileObject(
                UUID.randomUUID(), externalFileId, TENANT_ID, USER_ID,
                "clean/files/" + externalFileId + ".png", "reference.png", "sha256", mimeType,
                1024L, "READY", NOW.plusSeconds(3600), null, NOW);
    }

    /** 一条**已失败**的终态记录：重放它只会得到 409，不会碰配额，也不会碰文件。 */
    private static ImageTaskRepository.TaskRow failedTask(String requestId) {
        return new ImageTaskRepository.TaskRow(
                UUID.randomUUID(), requestId, UUID.randomUUID(), TENANT_ID, USER_ID,
                API_KEY_ID, PACKAGE_ID, ALIAS, UUID.randomUUID(), UUID.randomUUID(),
                "doubao-seedream-5.0-pro", "2K", 1, 1, null, "2K", "url", null, null,
                "FAILED", "upstream_rejected", false, NOW, NOW, NOW.plusSeconds(3600),
                // 单图请求：没有扇出父请求，也没有张序（网关迁移 V114）。
                null, null);
    }

    private static FileContentTokenSigner signer(boolean enabled) {
        FileContentTokenProperties properties = new FileContentTokenProperties();
        if (enabled) {
            properties.setSecret(Base64.getEncoder().encodeToString(
                    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)));
        }
        properties.setPublicBaseUrl(URI.create("https://api.yuzhh.com"));
        return new FileContentTokenSigner(properties);
    }
}
