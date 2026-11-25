package com.yuzhihuihe.apiplatform.platform.legal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 带版本的服务条款与隐私政策。
 *
 * <p>管理员编辑草稿；发布则插入一个新的不可变版本。同意台账记录
 * 用户所接受的那个版本，因此已发布的文档此后绝不得变更 —— 更正需作为
 * 新版本发布。同意绑定到*已发布*版本：客户端若提交过期的
 * 版本，将被拒绝，并在响应中返回当前版本，以便其重新展示正确的文本。
 */
@Service
public class LegalDocumentService {

    public static final String KIND_TERMS = "TERMS";
    public static final String KIND_PRIVACY = "PRIVACY";
    private static final List<String> KINDS = List.of(KIND_TERMS, KIND_PRIVACY);
    private static final int MAX_VERSION_LENGTH = 32;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int MAX_CONTENT_LENGTH = 200_000;
    private static final int MAX_SECTIONS = 200;
    private static final int HISTORY_LIMIT = 50;

    private final LegalDocumentRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public LegalDocumentService(LegalDocumentRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    LegalDocumentService(
            LegalDocumentRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 单个文档的已发布版本，供同意校验与公开文本 API 解析使用。 */
    public PublishedDocument published(String kind) {
        String normalizedKind = normalizeKind(kind);
        LegalDocumentRepository.Row row = repository.findPublished(normalizedKind)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "legal_document_unavailable"));
        return new PublishedDocument(
                normalizedKind, row.version(), row.title(), parseContent(row.content()));
    }

    /**
     * 创建账号的请求必须携带的版本字符串；若尚未发布任何内容则为空
     * （此时调用方回退到配置的默认值）。
     */
    public Optional<String> currentVersion(String kind) {
        return repository.findPublished(normalizeKind(kind))
                .map(LegalDocumentRepository.Row::version);
    }

    /**
     * 拒绝与已发布文档不匹配的版本字符串，并给出当前版本，
     * 以便浏览器刷新所展示的文本，而不是自行猜测。
     */
    public void requireCurrentVersion(String kind, String requestedVersion) {
        if (requestedVersion == null || requestedVersion.isBlank()) {
            return;
        }
        String current = currentVersion(kind).orElse(null);
        if (current != null && !current.equals(requestedVersion.strip())) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "legal_document_version_stale: current=" + current);
        }
    }

    /** 管理员视图：已发布文本、可编辑草稿以及两类文档的版本历史。 */
    public AdminState adminState() {
        return new AdminState(KINDS.stream().map(this::kindState).toList());
    }

    @Transactional
    public AdminState saveDraft(String kind, DraftCommand command, UUID actorId) {
        String normalizedKind = normalizeKind(kind);
        String title = requireText(
                command.title(), MAX_TITLE_LENGTH, "invalid_legal_document_title");
        String content = validateContent(command.content());
        LegalDocumentRepository.Row draft = repository.findDraft(normalizedKind)
                .orElseGet(() -> createDraftFromPublished(normalizedKind, actorId));
        repository.saveDraft(draft.id(), title, content, actorId, clock.instant());
        return adminState();
    }

    /**
     * 用给定的文本发布 {@code version}。版本字符串在同意台账中标识该文本，
     * 因此它必须是新的：重复发布已有版本会被拒绝，而不是静默改写
     * 用户已经接受过的内容。
     */
    @Transactional
    public AdminState publish(
            String kind, String requestedVersion, DraftCommand command, UUID actorId) {
        String normalizedKind = normalizeKind(kind);
        String version = requireText(
                requestedVersion, MAX_VERSION_LENGTH, "invalid_legal_document_version");
        String title = requireText(
                command.title(), MAX_TITLE_LENGTH, "invalid_legal_document_title");
        String content = validateContent(command.content());
        if (repository.versionExists(normalizedKind, version)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "legal_document_version_exists");
        }
        repository.publish(
                normalizedKind, version, title, content, actorId, clock.instant(),
                "LEGAL_DOCUMENT_PUBLISHED");
        return adminState();
    }

    /** 原样重新发布此前发布过的版本，用于回滚一次错误的编辑。 */
    @Transactional
    public AdminState restore(String kind, String version, UUID actorId) {
        String normalizedKind = normalizeKind(kind);
        String required = requireText(
                version, MAX_VERSION_LENGTH, "invalid_legal_document_version");
        LegalDocumentRepository.Row source = repository.findByVersion(normalizedKind, required)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "legal_document_version_not_found"));
        if (source.published()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "legal_document_version_already_published");
        }
        repository.restore(source, actorId, clock.instant());
        return adminState();
    }

    private KindState kindState(String kind) {
        LegalDocumentRepository.Row published = repository.findPublished(kind).orElse(null);
        LegalDocumentRepository.Row draft = repository.findDraft(kind).orElse(null);
        List<VersionView> history = repository.findHistory(kind, HISTORY_LIMIT).stream()
                .map(row -> new VersionView(
                        row.version(), row.title(), row.published(), row.publishedAt(),
                        row.publishedBy(), row.unpublishedAt(), row.createdAt(), row.updatedAt()))
                .toList();
        return new KindState(
                kind,
                published == null ? null : toDocumentView(published),
                draft == null ? null : toDocumentView(draft),
                history);
    }

    private DocumentView toDocumentView(LegalDocumentRepository.Row row) {
        return new DocumentView(
                row.kind(), row.version(), row.title(), parseContent(row.content()));
    }

    private LegalDocumentRepository.Row createDraftFromPublished(String kind, UUID actorId) {
        LegalDocumentRepository.Row published = repository.findPublished(kind)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "legal_document_unavailable"));
        repository.insertDraft(
                kind,
                nextDraftVersion(kind, published.version()),
                published.title(),
                published.content(),
                actorId,
                clock.instant());
        return repository.findDraft(kind).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "legal_document_unavailable"));
    }

    private String nextDraftVersion(String kind, String publishedVersion) {
        String candidate = publishedVersion + "-draft";
        int suffix = 2;
        while (repository.versionExists(kind, candidate)) {
            candidate = publishedVersion + "-draft" + suffix;
            suffix += 1;
        }
        return candidate;
    }

    private String normalizeKind(String kind) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalized)) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "invalid_legal_document_kind");
        }
        return normalized;
    }

    private String requireText(String value, int maxLength, String errorCode) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode);
        }
        return trimmed;
    }

    private String validateContent(JsonNode content) {
        if (content == null || !content.isObject()) {
            throw invalidContent();
        }
        String serialized;
        try {
            serialized = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException error) {
            throw invalidContent();
        }
        if (serialized.length() > MAX_CONTENT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "legal_document_too_large");
        }
        JsonNode sections = content.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()
                || sections.size() > MAX_SECTIONS) {
            throw invalidContent();
        }
        for (JsonNode section : sections) {
            JsonNode heading = section.get("heading");
            if (heading == null || !heading.isTextual() || heading.textValue().isBlank()) {
                throw invalidContent();
            }
            if (!section.has("paragraphs") && !section.has("items")) {
                throw invalidContent();
            }
        }
        return serialized;
    }

    private ResponseStatusException invalidContent() {
        return new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "invalid_legal_document_content");
    }

    private JsonNode parseContent(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "legal_document_unavailable");
        }
    }

    public record DraftCommand(String title, JsonNode content) {}

    public record PublishedDocument(String kind, String version, String title, JsonNode content) {}

    public record DocumentView(String kind, String version, String title, JsonNode content) {}

    public record VersionView(
            String version,
            String title,
            boolean published,
            Instant publishedAt,
            UUID publishedBy,
            /** 当更新的版本取代该版本时设置；它仍是当前版本时为 null。 */
            Instant unpublishedAt,
            Instant createdAt,
            Instant updatedAt) {}

    public record KindState(
            String kind, DocumentView published, DocumentView draft, List<VersionView> history) {}

    public record AdminState(List<KindState> documents) {}
}
