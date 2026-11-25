package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.node.NullNode;
import com.yuzhihuihe.apiplatform.gateway.file.FileObjectStore;
import com.yuzhihuihe.apiplatform.gateway.file.StoredObjectMetadata;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillView;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ExpertAggregateCommand;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileRequests.ImageAction;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.AdminExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertAggregateResponse;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPresentation;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillExpertProfileViews.ExpertPreviewContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Atomicity and full-replacement tests for the multipart Expert aggregate boundary. */
class SkillExpertAggregateServiceTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final byte[] ICON = png(1, 1);
    private static final byte[] ILLUSTRATION = png(3, 1);
    private static final byte[] EXAMPLE = png(2, 1);

    private SkillRepository skills;
    private SkillExpertAggregateRepository aggregates;
    private SkillExpertProfileService profiles;
    private SkillAdminService admin;
    private FileObjectStore objects;
    private TransactionalOperator transactions;
    private SkillExpertAggregateService service;

    @BeforeEach
    void setUp() {
        skills = org.mockito.Mockito.mock(SkillRepository.class);
        aggregates = org.mockito.Mockito.mock(SkillExpertAggregateRepository.class);
        profiles = org.mockito.Mockito.mock(SkillExpertProfileService.class);
        admin = org.mockito.Mockito.mock(SkillAdminService.class);
        objects = org.mockito.Mockito.mock(FileObjectStore.class);
        transactions = passThroughTransactions();

        when(objects.put(anyString(), any(byte[].class), anyString())).thenReturn(Mono.empty());
        when(objects.copy(anyString(), anyString())).thenReturn(Mono.empty());
        when(objects.delete(anyString())).thenReturn(Mono.empty());

        service = new SkillExpertAggregateService(
                skills, aggregates, profiles, admin, objects, transactions);
    }

    @Test
    void createWritesParentAndProfileInOneTransactionAndOnlyRemovesQuarantine() {
        ExpertAggregateCommand request = command(
                null, "new-expert", ImageAction.REPLACE, ImageAction.CLEAR, ImageAction.CLEAR);
        SkillView resultSkill = skill(UUID.randomUUID(), "new-expert", 1, 0);
        when(aggregates.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(any())).thenReturn(Mono.just(resultSkill));
        when(profiles.admin(any())).thenReturn(Mono.just(presentation(resultSkill.id())));

        var result = service.create(request, part("icon.png", ICON, MediaType.IMAGE_PNG), null,
                null, ACTOR).block();

        assertThat(result).isNotNull();
        ArgumentCaptor<ExpertAggregateCommand> command =
                ArgumentCaptor.forClass(ExpertAggregateCommand.class);
        ArgumentCaptor<String> pointers = ArgumentCaptor.forClass(String.class);
        verify(aggregates).insert(
                any(UUID.class), command.capture(), pointers.capture(), pointers.capture(),
                pointers.capture(), eq(ACTOR), any(Instant.class));
        assertThat(command.getValue().code()).isEqualTo("new-expert");
        assertThat(command.getValue().name()).isEqualTo("专家名称");
        assertThat(command.getValue().nameEn()).isEqualTo("Expert name");
        assertThat(command.getValue().tags()).containsExactly("tag-a", "tag-b");
        assertThat(command.getValue().taskTypes()).containsExactly("写作");
        assertThat(command.getValue().usageGuide()).isEqualTo("先填写主题。");
        assertThat(command.getValue().preview().outputParagraphs())
                .containsExactly("第一段", "第二段");
        assertThat(pointers.getAllValues().get(0)).startsWith("clean/skills/");
        assertThat(pointers.getAllValues().get(1)).isNull();
        assertThat(pointers.getAllValues().get(2)).isNull();

        verify(transactions).transactional(any(Mono.class));
        verify(objects).put(startsWith("quarantine/skill-experts/"), any(byte[].class),
                eq("image/png"));
        verify(objects).copy(startsWith("quarantine/skill-experts/"), startsWith("clean/skills/"));
        verify(objects).delete(startsWith("quarantine/skill-experts/"));
        verify(objects, never()).delete(startsWith("clean/"));
    }

    @Test
    void copyIsNotInvokedUntilPutMonoHasCompleted() throws Exception {
        EagerStartObjectStore eagerObjects = new EagerStartObjectStore();
        objects = eagerObjects;
        service = new SkillExpertAggregateService(
                skills, aggregates, profiles, admin, objects, transactions);

        SkillView resultSkill = skill(UUID.randomUUID(), "copy-order", 1, 0);
        when(aggregates.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(any())).thenReturn(Mono.just(resultSkill));
        when(profiles.admin(any())).thenReturn(Mono.just(presentation(resultSkill.id())));

        CompletableFuture<ExpertAggregateResponse> result =
                CompletableFuture.supplyAsync(() -> service.create(
                        command(null, "copy-order", ImageAction.REPLACE, ImageAction.CLEAR,
                                ImageAction.CLEAR),
                        part("icon.png", ICON, MediaType.IMAGE_PNG), null, null, ACTOR).block());

        assertThat(eagerObjects.putInvoked.await(5, TimeUnit.SECONDS)).isTrue();

        eagerObjects.completePut();

        assertThat(result.get(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(eagerObjects.copyInvoked).isTrue();
        assertThat(eagerObjects.copyInvokedBeforePutCompletion).isFalse();
    }

    @Test
    void updateTreatsNullableFieldsAsExplicitClearsInsteadOfMergingStoredValues() {
        UUID id = UUID.randomUUID();
        SkillView current = skill(id, "existing-expert", 7, 4);
        when(skills.skill(id)).thenReturn(Mono.just(current));
        when(skills.iconObjectKey(id)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(id)).thenReturn(Mono.empty());
        when(skills.exampleImageObjectKey(id)).thenReturn(Mono.empty());
        when(aggregates.update(any(), any(), eq(7), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(id)).thenReturn(Mono.just(current));
        when(profiles.admin(id)).thenReturn(Mono.just(presentation(id)));

        ExpertAggregateCommand request = new ExpertAggregateCommand(
                7,
                "existing-expert",
                "新名称",
                null,
                null,
                List.of(),
                "新简介",
                null,
                "新详情",
                null,
                null,
                null,
                null,
                "DRAFT",
                List.of("新任务"),
                List.of(),
                "新指南",
                null,
                List.of("新提示"),
                List.of(),
                preview(),
                null,
                null,
                null,
                ImageAction.CLEAR.name(),
                ImageAction.CLEAR.name(),
                ImageAction.CLEAR.name());

        service.update(id, request, null, null, null, ACTOR).block();

        ArgumentCaptor<ExpertAggregateCommand> command =
                ArgumentCaptor.forClass(ExpertAggregateCommand.class);
        verify(aggregates).update(
                eq(id), command.capture(), eq(7), isNull(String.class), isNull(String.class),
                isNull(String.class), eq(ACTOR), any(Instant.class));
        assertThat(command.getValue().name()).isEqualTo("新名称");
        assertThat(command.getValue().nameEn()).isNull();
        assertThat(command.getValue().category()).isNull();
        assertThat(command.getValue().tags()).isEmpty();
        assertThat(command.getValue().summaryEn()).isNull();
        assertThat(command.getValue().descriptionEn()).isNull();
        assertThat(command.getValue().icon()).isNull();
        assertThat(command.getValue().minClientVersion()).isNull();
        // sortWeight is the one operational field whose omitted value intentionally preserves
        // the existing ordering; all nullable text/list fields above are replacement semantics.
        assertThat(command.getValue().sortWeight()).isEqualTo(4);
        assertThat(command.getValue().taskTypes()).containsExactly("新任务");
        assertThat(command.getValue().usageGuide()).isEqualTo("新指南");
        assertThat(command.getValue().examplePrompts()).containsExactly("新提示");
        assertThat(command.getValue().previewEn()).isNull();
    }

    @Test
    void updateUsesKeepReplaceClearAndIncrementsTheAggregateOnce() {
        UUID id = UUID.randomUUID();
        SkillView current = skill(id, "existing-expert", 7, 4);
        when(skills.skill(id)).thenReturn(Mono.just(current));
        when(skills.iconObjectKey(id)).thenReturn(Mono.just("old-icon"));
        when(skills.illustrationObjectKey(id)).thenReturn(Mono.just("old-illustration"));
        when(skills.exampleImageObjectKey(id)).thenReturn(Mono.just("old-example"));
        when(objects.head("old-icon"))
                .thenReturn(Mono.just(metadata("old-icon", ICON.length, "image/png")));
        when(objects.get(eq("old-icon"), anyLong())).thenReturn(Mono.just(ICON));
        when(aggregates.update(any(), any(), eq(7), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(id)).thenReturn(Mono.just(current));
        when(profiles.admin(id)).thenReturn(Mono.just(presentation(id)));

        ExpertAggregateCommand request = command(
                7, "existing-expert", ImageAction.KEEP, ImageAction.REPLACE, ImageAction.CLEAR);
        service.update(id, request, null,
                part("illustration.png", ILLUSTRATION, MediaType.IMAGE_PNG), null, ACTOR).block();

        ArgumentCaptor<ExpertAggregateCommand> command =
                ArgumentCaptor.forClass(ExpertAggregateCommand.class);
        ArgumentCaptor<String> pointers = ArgumentCaptor.forClass(String.class);
        verify(aggregates).update(
                eq(id), command.capture(), eq(7), pointers.capture(), pointers.capture(),
                pointers.capture(), eq(ACTOR), any(Instant.class));
        assertThat(command.getValue().expectedRowVersion()).isEqualTo(7);
        assertThat(pointers.getAllValues().get(0)).isEqualTo("old-icon");
        assertThat(pointers.getAllValues().get(1)).startsWith("clean/skills/");
        assertThat(pointers.getAllValues().get(2)).isNull();
        verify(transactions).transactional(any(Mono.class));

        verify(objects).delete("old-illustration");
        verify(objects).delete("old-example");
        verify(objects).delete(startsWith("quarantine/skill-experts/"));
        verify(objects, never()).delete("old-icon");
        verify(objects, never()).delete(startsWith("clean/"));
    }

    @Test
    void keepAllowsLegacyOversizedObjectsWithoutStreamingThem() {
        UUID id = UUID.randomUUID();
        SkillView current = skill(id, "legacy-expert", 7, 0);
        when(skills.skill(id)).thenReturn(Mono.just(current));
        when(skills.iconObjectKey(id)).thenReturn(Mono.just("legacy-icon"));
        when(skills.illustrationObjectKey(id)).thenReturn(Mono.just("legacy-illustration"));
        when(skills.exampleImageObjectKey(id)).thenReturn(Mono.just("legacy-example"));
        when(objects.head("legacy-icon")).thenReturn(Mono.just(metadata(
                "legacy-icon", ExpertImageValidator.maxBytes(SkillExpertAggregateService.ROLE_ICON) + 1,
                "image/png")));
        when(objects.head("legacy-illustration")).thenReturn(Mono.just(metadata(
                "legacy-illustration",
                ExpertImageValidator.maxBytes(SkillExpertAggregateService.ROLE_ILLUSTRATION) + 1,
                "image/png")));
        when(objects.head("legacy-example")).thenReturn(Mono.just(metadata(
                "legacy-example",
                ExpertImageValidator.maxBytes(SkillExpertAggregateService.ROLE_EXAMPLE) + 1,
                "image/png")));
        when(aggregates.update(any(), any(), eq(7), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(id)).thenReturn(Mono.just(current));
        when(profiles.admin(id)).thenReturn(Mono.just(presentation(id)));

        service.update(
                id,
                command(7, "legacy-expert", ImageAction.KEEP, ImageAction.KEEP, ImageAction.KEEP),
                null,
                null,
                null,
                ACTOR).block();

        verify(objects, never()).get(anyString(), anyLong());
        verify(aggregates).update(
                eq(id), any(), eq(7), eq("legacy-icon"), eq("legacy-illustration"),
                eq("legacy-example"), eq(ACTOR), any(Instant.class));
        verify(objects, never()).delete(anyString());
    }

    @Test
    void staleRowVersionMapsTo409AndDoesNotRetryOrLeaveObjects() {
        UUID id = UUID.randomUUID();
        SkillView current = skill(id, "existing-expert", 7, 0);
        when(skills.skill(id)).thenReturn(Mono.just(current));
        when(skills.iconObjectKey(id)).thenReturn(Mono.empty());
        when(skills.illustrationObjectKey(id)).thenReturn(Mono.empty());
        when(skills.exampleImageObjectKey(id)).thenReturn(Mono.empty());
        when(aggregates.update(any(), any(), eq(7), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(new SkillExpertAggregateRepository.ConcurrentAggregateException()));

        Throwable thrown = catchThrowable(() -> service.update(
                id,
                command(7, "existing-expert", ImageAction.REPLACE, ImageAction.REPLACE,
                        ImageAction.REPLACE),
                part("icon.png", ICON, MediaType.IMAGE_PNG),
                part("illustration.png", ILLUSTRATION, MediaType.IMAGE_PNG),
                part("example.png", EXAMPLE, MediaType.IMAGE_PNG),
                ACTOR).block());

        assertThat(thrown).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getReason()).isEqualTo("skill_concurrent_modification");
        });
        verify(aggregates, times(1)).update(
                any(), any(), eq(7), any(), any(), any(), any(), any());
        verify(transactions).transactional(any(Mono.class));
        verify(objects, times(6)).delete(anyString());
        verifyNoInteractions(admin, profiles);
    }

    @Test
    void copyFailureCleansBothQuarantineAndCleanKeysBeforeAnyDatabaseWrite() {
        RuntimeException failure = new IllegalStateException("copy failed");
        when(objects.copy(anyString(), anyString())).thenReturn(Mono.error(failure));

        Throwable thrown = catchThrowable(() -> service.create(
                command(null, "copy-failure", ImageAction.REPLACE, ImageAction.CLEAR,
                        ImageAction.CLEAR),
                part("icon.png", ICON, MediaType.IMAGE_PNG), null, null, ACTOR).block());

        assertThat(thrown).isSameAs(failure);
        verify(aggregates, never()).insert(any(), any(), any(), any(), any(), any(), any());
        verify(objects, times(2)).delete(anyString());
        verifyNoInteractions(admin, profiles);
        verify(transactions, never()).transactional(any(Mono.class));
    }

    @Test
    void databaseFailureCleansBothObjectKeysAndLeavesNoAggregate() {
        RuntimeException failure = new IllegalStateException("database failed");
        when(aggregates.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.error(failure));

        Throwable thrown = catchThrowable(() -> service.create(
                command(null, "database-failure", ImageAction.REPLACE, ImageAction.CLEAR,
                        ImageAction.CLEAR),
                part("icon.png", ICON, MediaType.IMAGE_PNG), null, null, ACTOR).block());

        assertThat(thrown).isSameAs(failure);
        verify(aggregates).insert(any(), any(), any(), any(), any(), any(), any());
        verify(transactions).transactional(any(Mono.class));
        verify(objects, times(2)).delete(anyString());
        verifyNoInteractions(admin, profiles);
    }

    @Test
    void anyLaterImageFailureCleansAllEarlierAndCurrentStagedKeysWithoutSaving() {
        when(objects.put(anyString(), any(byte[].class), anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return key.contains("/illustration/")
                    ? Mono.error(new IllegalStateException("illustration put failed"))
                    : Mono.empty();
        });

        Throwable thrown = catchThrowable(() -> service.create(
                command(null, "multi-image-failure", ImageAction.REPLACE, ImageAction.REPLACE,
                        ImageAction.REPLACE),
                part("icon.png", ICON, MediaType.IMAGE_PNG),
                part("illustration.png", ILLUSTRATION, MediaType.IMAGE_PNG),
                part("example.png", EXAMPLE, MediaType.IMAGE_PNG),
                ACTOR).block());

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage("illustration put failed");
        verify(aggregates, never()).insert(any(), any(), any(), any(), any(), any(), any());
        // The first role has already been copied and the second role was registered before put.
        verify(objects, times(4)).delete(anyString());
        verifyNoInteractions(admin, profiles);
        verify(transactions, never()).transactional(any(Mono.class));
    }

    @Test
    void responseFailureDoesNotDeleteCommittedCleanPointer() {
        RuntimeException failure = new IllegalStateException("projection failed");
        when(aggregates.insert(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(1L));
        when(admin.detail(any())).thenReturn(Mono.error(failure));
        when(profiles.admin(any())).thenReturn(Mono.just(presentation(UUID.randomUUID())));

        ArgumentCaptor<String> clean = ArgumentCaptor.forClass(String.class);
        Throwable thrown = catchThrowable(() -> service.create(
                command(null, "response-failure", ImageAction.REPLACE, ImageAction.CLEAR,
                        ImageAction.CLEAR),
                part("icon.png", ICON, MediaType.IMAGE_PNG), null, null, ACTOR).block());

        assertThat(thrown).isSameAs(failure);
        verify(objects).copy(anyString(), clean.capture());
        verify(objects).delete(startsWith("quarantine/skill-experts/"));
        verify(objects, never()).delete(eq(clean.getValue()));
    }

    private static ExpertAggregateCommand command(
            Integer expectedRowVersion,
            String code,
            ImageAction icon,
            ImageAction illustration,
            ImageAction exampleImage) {
        return new ExpertAggregateCommand(
                expectedRowVersion,
                code,
                "专家名称",
                "Expert name",
                "内容创作",
                List.of("tag-a", "tag-b"),
                "专家简介",
                "Expert summary",
                "专家详细说明",
                "Expert description",
                "Aa",
                "2026.9.0",
                3,
                "DRAFT",
                List.of("写作"),
                List.of("Writing"),
                "先填写主题。",
                "Start with a topic.",
                List.of("请写一段介绍。"),
                List.of("Write an introduction."),
                preview(),
                previewEn(),
                null,
                null,
                icon.name(),
                illustration.name(),
                exampleImage.name());
    }

    private static ExpertPreviewContent preview() {
        return new ExpertPreviewContent(
                "示例标题", "营销场景", "请给出产品介绍", "产品资料", "输出", List.of("第一段", "第二段"));
    }

    private static ExpertPreviewContent previewEn() {
        return new ExpertPreviewContent(
                "Example", "Marketing", "Write an introduction", "Product notes", "Output",
                List.of("Paragraph one"));
    }

    private static AdminExpertPresentation presentation(UUID id) {
        return new AdminExpertPresentation(
                id,
                "expert",
                "EXPERT",
                new ExpertPresentation(
                        "专家名称", "Expert name", "专家简介", "Expert summary", "专家详细说明",
                        "Expert description", List.of("写作"), List.of("Writing"), "先填写主题。",
                        "Start with a topic.", List.of("请写一段介绍。"), List.of("Write an introduction."),
                        preview(), previewEn(), Map.of(), 0, 1, Instant.now()));
    }

    private static SkillView skill(UUID id, String code, int rowVersion, int sortWeight) {
        Instant now = Instant.parse("2026-09-20T00:00:00Z");
        return new SkillView(
                id, code, "专家名称", "Expert name", "内容创作", List.of("tag-a"), "专家简介",
                "Expert summary", "专家详细说明", "Expert description", "Aa",
                null, null, null, null, null, null,
                "EXPERT", "DRAFT", null, null, "2026.9.0", null, null, null, 0L, sortWeight,
                rowVersion, now, now, null, null, 0, 0, 0, NullNode.getInstance(), List.of(), List.of(),
                "YCPS", false, null, null);
    }

    private static StoredObjectMetadata metadata(String key, long size, String contentType) {
        return new StoredObjectMetadata(key, size, contentType, null, Instant.now());
    }

    /**
     * The PUT publisher stays pending until the test releases it, while COPY records invocation
     * eagerly. This catches constructing copy() as a .then(...) argument before PUT completes.
     */
    private static final class EagerStartObjectStore implements FileObjectStore {

        private final CompletableFuture<Void> putCompletion = new CompletableFuture<>();
        private final CountDownLatch putInvoked = new CountDownLatch(1);
        private volatile boolean copyInvoked;
        private volatile boolean copyInvokedBeforePutCompletion;

        @Override
        public Mono<Void> put(String objectKey, byte[] content, String mimeType) {
            putInvoked.countDown();
            return Mono.fromFuture(putCompletion);
        }

        @Override
        public Mono<Void> copy(String sourceObjectKey, String targetObjectKey) {
            copyInvoked = true;
            copyInvokedBeforePutCompletion = !putCompletion.isDone();
            if (copyInvokedBeforePutCompletion) {
                throw new AssertionError("copy invoked before put completed");
            }
            return Mono.empty();
        }

        @Override
        public Mono<Void> delete(String objectKey) {
            return Mono.empty();
        }

        void completePut() {
            putCompletion.complete(null);
        }

        @Override
        public Mono<Void> putFile(
                String objectKey, java.nio.file.Path file, String mimeType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<byte[]> get(String objectKey, long maximumBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Flux<java.nio.ByteBuffer> getRange(String prefix, long offset, long length) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<StoredObjectMetadata> head(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<com.yuzhihuihe.apiplatform.gateway.file.StoredObjectPage> list(
                String prefix, String continuationToken, int limit) {
            throw new UnsupportedOperationException();
        }
    }

    private static FilePart part(String filename, byte[] bytes, MediaType mediaType) {
        FilePart part = org.mockito.Mockito.mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentLength(bytes.length);
        when(part.filename()).thenReturn(filename);
        when(part.headers()).thenReturn(headers);
        DataBuffer buffer = DefaultDataBufferFactory.sharedInstance.wrap(bytes);
        when(part.content()).thenReturn(Flux.just(buffer));
        return part;
    }

    private static byte[] png(int width, int height) {
        byte[] bytes = new byte[24];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4e;
        bytes[3] = 0x47;
        bytes[4] = 0x0d;
        bytes[5] = 0x0a;
        bytes[6] = 0x1a;
        bytes[7] = 0x0a;
        bytes[12] = 0x49;
        bytes[13] = 0x48;
        bytes[14] = 0x44;
        bytes[15] = 0x52;
        bigEndian(bytes, 16, width);
        bigEndian(bytes, 20, height);
        return bytes;
    }

    private static void bigEndian(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TransactionalOperator passThroughTransactions() {
        TransactionalOperator transactions = org.mockito.Mockito.mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return transactions;
    }
}
