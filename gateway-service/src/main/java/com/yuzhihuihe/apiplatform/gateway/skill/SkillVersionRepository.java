package com.yuzhihuihe.apiplatform.gateway.skill;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.SkillVersionView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Parameterised R2DBC SQL for {@code gateway.skill_version}: the package lifecycle
 * ({@code DRAFT → READY/REJECTED/FAILED → PUBLISHED → ARCHIVED}), conditional state updates,
 * revisions and per-version download counters.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillVersionRepository {

    private static final String SELECT_VERSION = """
            SELECT version.id, version.skill_id, version.version, version.revision, version.status,
                   version.object_key, version.quarantine_key, version.file_name, version.size_bytes,
                   version.md5, version.sha256, version.file_count, version.extracted_bytes,
                   version.min_client_version, version.changelog, version.scan_status,
                   version.scan_report, version.download_count, version.created_at,
                   version.package_format, version.ycps_version, version.package_kind,
                   version.plugin_manifest::text AS plugin_manifest,
                   version.runtime_manifest::text AS runtime_manifest,
                   version.manifest_sha256, version.dependency_lock_sha256,
                   version.sealed, version.sealed_at
            """;

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillVersionRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Flux<SkillVersionView> listVersions(UUID skillId, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.skill_id = :skill_id AND version.deleted_at IS NULL
                        ORDER BY version.created_at DESC, version.revision DESC, version.id DESC
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .all();
    }

    Mono<SkillVersionView> version(UUID id, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.id = :id AND version.deleted_at IS NULL
                        """)
                .bind("id", id)
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .one();
    }

    /** {@code SELECT ... FOR UPDATE}; callers must run inside a transaction. */
    Mono<SkillVersionView> lockVersion(UUID id, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.id = :id AND version.deleted_at IS NULL
                        FOR UPDATE
                        """)
                .bind("id", id)
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .one();
    }

    Mono<SkillVersionView> versionByNumber(UUID skillId, String version, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.skill_id = :skill_id AND version.version = :version
                          AND version.deleted_at IS NULL
                        -- The public preview passes the current semantic version here.  Once a
                        -- portable successor is READY, revision alone must not make that
                        -- unpublished row replace the exact PUBLISHED artifact.  Upload's
                        -- duplicate check still sees either row, while preview/published reads
                        -- deterministically prefer the one published row.
                        ORDER BY (version.status = 'PUBLISHED'
                                  AND version.version = :online_version) DESC,
                                 version.revision DESC, version.created_at DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .bind("version", version)
                .bind("online_version", nullable(onlineVersion))
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .one();
    }

    /**
     * The next free revision of one {@code code@version}.
     *
     * <p>The counter spans every skill record that ever carried this code — including soft-deleted
     * ones and their versions. {@code code@version#revision} is the immutable content identity a
     * client stores, so restarting the counter at 1 after a record is deleted and recreated hands
     * out an identity the client already holds with different bytes, and the client refuses the
     * install as tampering. Deleted rows therefore keep reserving their revisions.
     */
    Mono<Integer> nextRevision(String code, String version) {
        return databaseClient
                .sql("""
                        SELECT COALESCE(MAX(version.revision), 0) + 1 AS next_revision
                        FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE skill.code = :code AND version.version = :version
                        """)
                .bind("code", code)
                .bind("version", version)
                .map((row, metadata) -> row.get("next_revision", Integer.class))
                .one();
    }

    /** The package the clients currently download: {@code skill.published_version} (design 6.3). */
    Mono<SkillVersionView> onlineVersion(UUID skillId) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE version.skill_id = :skill_id
                          AND version.deleted_at IS NULL
                          AND version.version = skill.published_version
                          AND version.status = 'PUBLISHED'
                          AND version.package_format = 'YCPS'
                          AND version.ycps_version IN ('1.0.0', '1.1.0')
                          AND version.sealed
                        ORDER BY version.revision DESC, version.created_at DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> SkillRepository.versionView(row, row.get("version", String.class)))
                .one();
    }

    /**
     * Streaming reference for the online package: keeps {@code object_key}, which the admin DTO
     * deliberately never exposes (design 10.5).
     */
    Mono<PackageRef> onlinePackage(UUID skillId) {
        return databaseClient
                .sql("""
                        SELECT version.id, version.skill_id, version.version, version.revision,
                               version.object_key, version.size_bytes, version.md5, version.sha256,
                               version.min_client_version, version.package_format,
                               version.ycps_version, version.package_kind,
                               version.manifest_sha256, version.dependency_lock_sha256,
                               (version.runtime_manifest -> 'dependencies')::text AS dependencies
                        FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE version.skill_id = :skill_id
                          AND version.deleted_at IS NULL
                          AND version.version = skill.published_version
                          AND version.status = 'PUBLISHED'
                          AND version.object_key IS NOT NULL
                          AND version.package_format = 'YCPS'
                          AND version.ycps_version IN ('1.0.0', '1.1.0')
                          AND version.sealed
                        ORDER BY version.revision DESC, version.created_at DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> packageRef(row))
                .one();
    }

    /**
     * The stored package of one exact revision, for the console's admin download (A25).
     *
     * <p>Unlike {@link #onlinePackage(UUID)} this does not require the version to be the online one,
     * so the operator can pull any revision the console lists. It accepts READY as well as
     * PUBLISHED/ARCHIVED — an operator reviewing a package before promoting it is exactly who wants
     * this button — but still refuses DRAFT (the scan has not finished, so there is no vetted body)
     * and REJECTED/FAILED (the body failed the pipeline). The YCPS 1.0.0 requirement mirrors the
     * client path: older formats have no sealed artefact to hand back.
     */
    Mono<PackageRef> packageByVersionId(UUID skillId, UUID versionId) {
        return databaseClient
                .sql("""
                        SELECT version.id, version.skill_id, version.version, version.revision,
                               version.object_key, version.size_bytes, version.md5, version.sha256,
                               version.min_client_version, version.package_format,
                               version.ycps_version, version.package_kind,
                               version.manifest_sha256, version.dependency_lock_sha256,
                               (version.runtime_manifest -> 'dependencies')::text AS dependencies
                        FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE version.id = :version_id
                          AND version.skill_id = :skill_id
                          AND version.deleted_at IS NULL
                          AND skill.deleted_at IS NULL
                          AND version.object_key IS NOT NULL
                          AND version.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                          AND version.package_format = 'YCPS'
                          AND version.ycps_version IN ('1.0.0', '1.1.0')
                        """)
                .bind("version_id", versionId)
                .bind("skill_id", skillId)
                .map((row, metadata) -> packageRef(row))
                .one();
    }

    /**
     * Finds a previously generated portable successor for the same exact inputs.  The provenance
     * is kept in {@code scan_report} because it describes the generated artifact, not the mutable
     * catalog row; querying it also makes retries converge after a profile-metadata write failed.
     */
    Mono<SkillVersionView> portableRevisionByInputs(
            UUID skillId,
            String version,
            String baseArtifactSha256,
            String presentationCanonicalSha256) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.skill_id = :skill_id
                          AND version.version = :version
                          AND version.deleted_at IS NULL
                          AND version.package_format = 'YCPS'
                          AND version.ycps_version = '1.1.0'
                          AND version.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                          AND version.scan_report ->> 'portableBaseArtifactSha256'
                              = :base_artifact_sha256
                          AND version.scan_report ->> 'portablePresentationCanonicalSha256'
                              = :presentation_canonical_sha256
                        ORDER BY version.revision DESC, version.created_at DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .bind("version", version)
                .bind("base_artifact_sha256", baseArtifactSha256)
                .bind("presentation_canonical_sha256", presentationCanonicalSha256)
                .map((row, metadata) -> SkillRepository.versionView(
                        row, row.get("version", String.class)))
                .one();
    }

    /** Resolves only an exact edge from an authorized parent session, including archived targets. */
    Mono<PackageRef> dependencyPackage(
            UUID parentVersionId,
            String code,
            String kind,
            String versionNumber,
            int revision,
            String sha256) {
        return databaseClient.sql("""
                        SELECT version.id, version.skill_id, version.version, version.revision,
                               version.object_key, version.size_bytes, version.md5, version.sha256,
                               version.min_client_version, version.package_format,
                               version.ycps_version, version.package_kind,
                               version.manifest_sha256, version.dependency_lock_sha256,
                               (version.runtime_manifest -> 'dependencies')::text AS dependencies
                        FROM gateway.skill_version_dependency dependency
                        JOIN gateway.skill_version version
                          ON version.id = dependency.target_version_id
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE dependency.owner_version_id = :parent_version_id
                          AND dependency.target_code = :code
                          AND dependency.target_kind = :kind
                          AND dependency.target_version = :version
                          AND dependency.target_revision = :revision
                          AND dependency.target_sha256 = :sha256
                          AND version.object_key IS NOT NULL
                          AND version.deleted_at IS NULL
                          AND version.status IN ('PUBLISHED', 'ARCHIVED')
                          AND version.package_format = 'YCPS'
                          AND version.ycps_version IN ('1.0.0', '1.1.0')
                          AND version.sealed
                          AND skill.deleted_at IS NULL
                        """)
                .bind("parent_version_id", parentVersionId)
                .bind("code", code)
                .bind("kind", kind)
                .bind("version", versionNumber)
                .bind("revision", revision)
                .bind("sha256", sha256)
                .map((row, metadata) -> packageRef(row))
                .one();
    }

    /** Newest publishable package, used by A9 when the request does not name a version. */
    Mono<SkillVersionView> newestReady(UUID skillId, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.skill_id = :skill_id
                          AND version.deleted_at IS NULL
                          AND version.package_format = 'YCPS'
                          AND version.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        ORDER BY version.created_at DESC, version.revision DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .one();
    }

    Mono<SkillVersionView> readyVersionByNumber(UUID skillId, String version, String onlineVersion) {
        return databaseClient
                .sql(SELECT_VERSION
                        + """
                        FROM gateway.skill_version version
                        WHERE version.skill_id = :skill_id AND version.version = :version
                          AND version.deleted_at IS NULL
                          AND version.package_format = 'YCPS'
                          AND version.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        ORDER BY version.revision DESC, version.created_at DESC, version.id DESC
                        LIMIT 1
                        """)
                .bind("skill_id", skillId)
                .bind("version", version)
                .map((row, metadata) -> SkillRepository.versionView(row, onlineVersion))
                .one();
    }

    /** The largest reusable version, used to maintain {@code skill.latest_version}. */
    Mono<String> highestReadyVersion(UUID skillId) {
        return databaseClient
                .sql("""
                        SELECT version FROM gateway.skill_version
                        WHERE skill_id = :skill_id AND deleted_at IS NULL
                          AND package_format = 'YCPS'
                          AND status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get("version", String.class))
                .all()
                .collectList()
                .flatMap(values -> Mono.justOrEmpty(highest(values)));
    }

    /** Exact immutable dependency resolution performed before a YCPS package can become READY. */
    Mono<Boolean> dependencyExists(DependencyRef dependency) {
        return databaseClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM gateway.skill_version version
                            JOIN gateway.skill skill ON skill.id = version.skill_id
                            WHERE skill.code = :code
                              AND skill.kind = :kind
                              AND skill.status = 'PUBLISHED'
                              AND skill.deleted_at IS NULL
                              AND version.version = :version
                              AND version.revision = :revision
                              AND version.sha256 = :sha256
                              AND version.package_kind = :kind
                              AND version.package_format = 'YCPS'
                              AND version.ycps_version IN ('1.0.0', '1.1.0')
                              AND version.sealed
                              AND version.status = 'PUBLISHED'
                              AND version.deleted_at IS NULL
                        ) AS present
                        """)
                .bind("code", dependency.code())
                .bind("kind", dependency.kind())
                .bind("version", dependency.version())
                .bind("revision", dependency.revision())
                .bind("sha256", dependency.sha256())
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one()
                .defaultIfEmpty(false);
    }

    Mono<ResolvedDependency> resolveDependency(DependencyRef dependency) {
        return databaseClient.sql("""
                        SELECT version.id AS target_version_id
                        FROM gateway.skill_version version
                        JOIN gateway.skill skill ON skill.id = version.skill_id
                        WHERE skill.code = :code AND skill.kind = :kind
                          AND skill.status = 'PUBLISHED' AND skill.deleted_at IS NULL
                          AND version.version = :version AND version.revision = :revision
                          AND version.sha256 = :sha256 AND version.package_kind = :kind
                              AND version.package_format = 'YCPS' AND version.ycps_version IN ('1.0.0', '1.1.0')
                          AND version.sealed AND version.status = 'PUBLISHED'
                          AND version.deleted_at IS NULL
                        """)
                .bind("code", dependency.code())
                .bind("kind", dependency.kind())
                .bind("version", dependency.version())
                .bind("revision", dependency.revision())
                .bind("sha256", dependency.sha256())
                .map((row, metadata) -> new ResolvedDependency(
                        dependency.key(), dependency.relation(), dependency.required(),
                        row.get("target_version_id", UUID.class), dependency.code(),
                        dependency.kind(), dependency.version(), dependency.revision(),
                        dependency.sha256()))
                .one();
    }

    Mono<Void> replaceDependencies(UUID ownerVersionId, List<ResolvedDependency> dependencies) {
        Mono<Long> delete = databaseClient.sql("""
                        DELETE FROM gateway.skill_version_dependency
                        WHERE owner_version_id = :owner_version_id
                        """)
                .bind("owner_version_id", ownerVersionId)
                .fetch().rowsUpdated();
        return delete.thenMany(Flux.fromIterable(dependencies)
                        .concatMap(dependency -> databaseClient.sql("""
                                        INSERT INTO gateway.skill_version_dependency(
                                            owner_version_id, dependency_key, relation, required,
                                            target_version_id, target_code, target_kind,
                                            target_version, target_revision, target_sha256)
                                        VALUES (:owner_version_id, :dependency_key, :relation,
                                            :required, :target_version_id, :target_code,
                                            :target_kind, :target_version, :target_revision,
                                            :target_sha256)
                                        """)
                                .bind("owner_version_id", ownerVersionId)
                                .bind("dependency_key", dependency.key())
                                .bind("relation", dependency.relation())
                                .bind("required", dependency.required())
                                .bind("target_version_id", dependency.targetVersionId())
                                .bind("target_code", dependency.code())
                                .bind("target_kind", dependency.kind())
                                .bind("target_version", dependency.version())
                                .bind("target_revision", dependency.revision())
                                .bind("target_sha256", dependency.sha256())
                                .fetch().rowsUpdated()))
                .then();
    }

    Mono<Boolean> hasDependentsForVersion(UUID versionId) {
        return databaseClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM gateway.skill_version_dependency dependency
                            JOIN gateway.skill_version owner ON owner.id = dependency.owner_version_id
                            WHERE dependency.target_version_id = :version_id
                              AND owner.deleted_at IS NULL
                              AND owner.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        ) AS present
                        """)
                .bind("version_id", versionId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    Mono<Boolean> hasActiveDependentsForSkill(UUID skillId) {
        return databaseClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM gateway.skill_version_dependency dependency
                            JOIN gateway.skill_version target ON target.id = dependency.target_version_id
                            JOIN gateway.skill_version owner ON owner.id = dependency.owner_version_id
                            WHERE target.skill_id = :skill_id AND owner.deleted_at IS NULL
                AND owner.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        ) AS present
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> Boolean.TRUE.equals(row.get("present", Boolean.class)))
                .one().defaultIfEmpty(false);
    }

    /**
     * Exact transitive dependency graph used as the publish gate. Depth 33 is emitted solely so
     * callers can reject packages exceeding the frozen YCPS depth-32 activation bound.
     */
    Flux<DependencyGraphEdge> dependencyClosure(UUID rootVersionId, String rootCode) {
        return databaseClient.sql("""
                        WITH RECURSIVE closure AS (
                            SELECT dependency.owner_version_id,
                                   dependency.target_version_id,
                                   dependency.target_code,
                                   dependency.target_kind,
                                   dependency.target_version,
                                   dependency.target_revision,
                                   dependency.target_sha256,
                                   1 AS depth,
                                   ARRAY[:root_code, dependency.target_code]::varchar[] AS code_path,
                                   dependency.target_code = :root_code AS code_cycle
                            FROM gateway.skill_version_dependency dependency
                            WHERE dependency.owner_version_id = :root_version_id
                            UNION ALL
                            SELECT dependency.owner_version_id,
                                   dependency.target_version_id,
                                   dependency.target_code,
                                   dependency.target_kind,
                                   dependency.target_version,
                                   dependency.target_revision,
                                   dependency.target_sha256,
                                   closure.depth + 1,
                                   closure.code_path || dependency.target_code,
                                   dependency.target_code = ANY(closure.code_path)
                            FROM closure
                            JOIN gateway.skill_version_dependency dependency
                              ON dependency.owner_version_id = closure.target_version_id
                            WHERE closure.depth < 33 AND NOT closure.code_cycle
                        )
                        SELECT owner_version_id, target_version_id, target_code, target_kind,
                               target_version, target_revision, target_sha256, depth, code_cycle
                        FROM closure
                        ORDER BY depth, target_code, target_version, target_revision
                        """)
                .bind("root_version_id", rootVersionId)
                .bind("root_code", rootCode)
                .map((row, metadata) -> new DependencyGraphEdge(
                        row.get("owner_version_id", UUID.class),
                        row.get("target_version_id", UUID.class),
                        row.get("target_code", String.class),
                        row.get("target_kind", String.class),
                        row.get("target_version", String.class),
                        row.get("target_revision", Integer.class),
                        row.get("target_sha256", String.class),
                        row.get("depth", Integer.class),
                        Boolean.TRUE.equals(row.get("code_cycle", Boolean.class))))
                .all();
    }

    /**
     * A19: the locked dependency edges of one revision, joined with the live target version and its
     * owning skill. A logically deleted target is reported as {@code DELETED} instead of being
     * hidden, so the review dialog never understates the blast radius of a delete.
     */
    Flux<DependencyRow> dependencies(UUID ownerVersionId) {
        return databaseClient.sql("""
                        SELECT dependency.dependency_key, dependency.relation, dependency.required,
                               dependency.target_code, dependency.target_kind,
                               dependency.target_version, dependency.target_revision,
                               dependency.target_sha256, dependency.target_version_id,
                               target.skill_id AS target_skill_id,
                               CASE WHEN target.deleted_at IS NULL THEN target.status
                                    ELSE 'DELETED' END AS target_status,
                               (target.deleted_at IS NULL
                                 AND target_skill.deleted_at IS NULL
                                 AND target.status = 'PUBLISHED'
                                 AND target_skill.published_version = target.version) AS target_online
                        FROM gateway.skill_version_dependency dependency
                        JOIN gateway.skill_version target
                          ON target.id = dependency.target_version_id
                        JOIN gateway.skill target_skill ON target_skill.id = target.skill_id
                        WHERE dependency.owner_version_id = :owner_version_id
                        ORDER BY dependency.dependency_key
                        """)
                .bind("owner_version_id", ownerVersionId)
                .map((row, metadata) -> new DependencyRow(
                        row.get("dependency_key", String.class),
                        row.get("relation", String.class),
                        Boolean.TRUE.equals(row.get("required", Boolean.class)),
                        row.get("target_code", String.class),
                        row.get("target_kind", String.class),
                        row.get("target_version", String.class),
                        orZero(row.get("target_revision", Integer.class)),
                        row.get("target_sha256", String.class),
                        row.get("target_version_id", UUID.class),
                        row.get("target_skill_id", UUID.class),
                        row.get("target_status", String.class),
                        Boolean.TRUE.equals(row.get("target_online", Boolean.class))))
                .all();
    }

    /**
     * A20: every revision that locks this code, newest first. Only owners that are still usable
     * ({@code READY}/{@code PUBLISHED}/{@code ARCHIVED}, not logically deleted) are listed: those are
     * exactly the rows that block {@code A11} and the version delete gate.
     */
    Flux<DependentRow> dependents(String code) {
        return databaseClient.sql("""
                        SELECT owner.id AS owner_version_id, owner.skill_id AS owner_skill_id,
                               owner_skill.code AS owner_code, owner_skill.kind AS owner_kind,
                               owner.version AS owner_version, owner.revision AS owner_revision,
                               owner_skill.status AS owner_status,
                               (owner_skill.status = 'PUBLISHED'
                                 AND owner_skill.published_version = owner.version) AS owner_online,
                               dependency.dependency_key, dependency.relation, dependency.required,
                               dependency.target_version, dependency.target_revision
                        FROM gateway.skill_version_dependency dependency
                        JOIN gateway.skill_version owner
                          ON owner.id = dependency.owner_version_id
                        JOIN gateway.skill owner_skill ON owner_skill.id = owner.skill_id
                        WHERE dependency.target_code = :code
                          AND owner.deleted_at IS NULL
                          AND owner.status IN ('READY', 'PUBLISHED', 'ARCHIVED')
                        ORDER BY owner.created_at DESC, owner.id DESC, dependency.dependency_key
                        """)
                .bind("code", code)
                .map((row, metadata) -> new DependentRow(
                        row.get("owner_skill_id", UUID.class),
                        row.get("owner_code", String.class),
                        row.get("owner_kind", String.class),
                        row.get("owner_version_id", UUID.class),
                        row.get("owner_version", String.class),
                        orZero(row.get("owner_revision", Integer.class)),
                        row.get("owner_status", String.class),
                        Boolean.TRUE.equals(row.get("owner_online", Boolean.class)),
                        row.get("dependency_key", String.class),
                        row.get("relation", String.class),
                        Boolean.TRUE.equals(row.get("required", Boolean.class)),
                        row.get("target_version", String.class),
                        orZero(row.get("target_revision", Integer.class))))
                .all();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    static String highest(List<String> versions) {
        String best = null;
        for (String candidate : versions) {
            if (candidate == null) {
                continue;
            }
            if (best == null || compareVersion(candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Compares the admin API's numeric-core version shape with SemVer pre-release ordering.
     * ClientVersion intentionally remains separate because the desktop compatibility gate accepts
     * only calendar-style dotted numeric values.
     */
    static int compareVersion(String left, String right) {
        String[] leftMainAndPre = left.strip().split("-", 2);
        String[] rightMainAndPre = right.strip().split("-", 2);
        String[] leftCore = leftMainAndPre[0].split("\\.", -1);
        String[] rightCore = rightMainAndPre[0].split("\\.", -1);
        int coreLength = Math.max(leftCore.length, rightCore.length);
        for (int index = 0; index < coreLength; index++) {
            BigInteger leftValue = index < leftCore.length
                    ? new BigInteger(leftCore[index])
                    : BigInteger.ZERO;
            BigInteger rightValue = index < rightCore.length
                    ? new BigInteger(rightCore[index])
                    : BigInteger.ZERO;
            int compared = leftValue.compareTo(rightValue);
            if (compared != 0) {
                return compared;
            }
        }
        boolean leftPre = leftMainAndPre.length == 2;
        boolean rightPre = rightMainAndPre.length == 2;
        if (leftPre != rightPre) {
            return leftPre ? -1 : 1;
        }
        if (!leftPre) {
            return 0;
        }
        String[] leftParts = leftMainAndPre[1].split("\\.", -1);
        String[] rightParts = rightMainAndPre[1].split("\\.", -1);
        int shared = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < shared; index++) {
            String leftPart = leftParts[index];
            String rightPart = rightParts[index];
            boolean leftNumeric = leftPart.chars().allMatch(Character::isDigit);
            boolean rightNumeric = rightPart.chars().allMatch(Character::isDigit);
            int compared;
            if (leftNumeric && rightNumeric) {
                compared = new BigInteger(leftPart).compareTo(new BigInteger(rightPart));
            } else if (leftNumeric != rightNumeric) {
                compared = leftNumeric ? -1 : 1;
            } else {
                compared = leftPart.compareTo(rightPart);
            }
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(leftParts.length, rightParts.length);
    }

    Mono<UUID> insertVersion(PackageCommand command) {
        UUID id = UUID.randomUUID();
        return databaseClient
                .sql("""
                        INSERT INTO gateway.skill_version(
                            id, skill_id, version, revision, status, quarantine_key, file_name,
                            size_bytes, md5, sha256, min_client_version, changelog,
                            scan_status, download_count, created_by, created_at)
                        VALUES (:id, :skill_id, :version, :revision, 'DRAFT', :quarantine_key,
                            :file_name, :size_bytes, :md5, :sha256, :min_client_version, :changelog,
                            'PENDING', 0, :actor, :now)
                        """)
                .bind("id", id)
                .bind("skill_id", command.skillId())
                .bind("version", command.version())
                .bind("revision", command.revision())
                .bind("quarantine_key", command.quarantineKey())
                .bind("file_name", command.fileName())
                .bind("size_bytes", command.sizeBytes())
                .bind("md5", command.md5())
                .bind("sha256", command.sha256())
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("changelog", nullable(command.changelog()))
                .bind("actor", command.actor())
                .bind("now", command.now())
                .fetch()
                .rowsUpdated()
                .filter(updated -> updated == 1L)
                .switchIfEmpty(Mono.error(new IllegalStateException("gateway_skill_version_not_inserted")))
                .thenReturn(id);
    }

    /** Replacement upload (design 6.2): revision + 1, new object keys, scan reset to PENDING. */
    Mono<Long> replacePackage(UUID id, PackageCommand command, int expectedRevision) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET revision = :revision,
                            status = 'DRAFT',
                            quarantine_key = :quarantine_key,
                            object_key = NULL,
                            file_name = :file_name,
                            size_bytes = :size_bytes,
                            md5 = :md5,
                            sha256 = :sha256,
                            file_count = 0,
                            extracted_bytes = 0,
                            min_client_version = :min_client_version,
                            changelog = :changelog,
                            scan_status = 'PENDING',
                            scan_report = NULL,
                            package_format = NULL,
                            ycps_version = NULL,
                            package_kind = NULL,
                            plugin_manifest = NULL,
                            runtime_manifest = NULL,
                            manifest_sha256 = NULL,
                            dependency_lock_sha256 = NULL
                        WHERE id = :id AND deleted_at IS NULL AND revision = :expected_revision
                          AND NOT sealed
                        """)
                .bind("id", id)
                .bind("revision", command.revision())
                .bind("quarantine_key", command.quarantineKey())
                .bind("file_name", command.fileName())
                .bind("size_bytes", command.sizeBytes())
                .bind("md5", command.md5())
                .bind("sha256", command.sha256())
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("changelog", nullable(command.changelog()))
                .bind("expected_revision", expectedRevision)
                .fetch()
                .rowsUpdated();
    }

    /**
     * Replacement upload (design 6.2): one conditional update that swaps the package, bumps the
     * revision and lands {@code READY} at the same time — the previous package keeps serving until
     * the new one is validated, and a failed replacement leaves the row untouched.
     */
    Mono<Long> replaceReady(
            UUID id,
            PackageCommand command,
            int expectedRevision,
            String cleanObjectKey,
            int fileCount,
            long extractedBytes,
            YcpsMetadata ycps,
            Map<String, Object> report) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET revision = :revision,
                            status = 'READY',
                            object_key = :clean_object_key,
                            quarantine_key = NULL,
                            file_name = :file_name,
                            size_bytes = :size_bytes,
                            md5 = :md5,
                            sha256 = :sha256,
                            file_count = :file_count,
                            extracted_bytes = :extracted_bytes,
                            min_client_version = :min_client_version,
                            changelog = :changelog,
                            scan_status = 'PASSED',
                            scan_report = :report::jsonb,
                            package_format = 'YCPS',
                            ycps_version = :ycps_version,
                            package_kind = :package_kind,
                            plugin_manifest = :plugin_manifest::jsonb,
                            runtime_manifest = :runtime_manifest::jsonb,
                            manifest_sha256 = :manifest_sha256,
                            dependency_lock_sha256 = :dependency_lock_sha256,
                            sealed = false,
                            sealed_at = NULL
                        WHERE id = :id AND deleted_at IS NULL AND revision = :expected_revision
                        """)
                .bind("id", id)
                .bind("revision", command.revision())
                .bind("clean_object_key", cleanObjectKey)
                .bind("file_name", command.fileName())
                .bind("size_bytes", command.sizeBytes())
                .bind("md5", command.md5())
                .bind("sha256", command.sha256())
                .bind("file_count", fileCount)
                .bind("extracted_bytes", extractedBytes)
                .bind("ycps_version", ycps.ycpsVersion())
                .bind("package_kind", ycps.kind())
                .bind("plugin_manifest", ycps.pluginJson())
                .bind("runtime_manifest", ycps.runtimeJson())
                .bind("manifest_sha256", ycps.manifestSha256())
                .bind("dependency_lock_sha256", ycps.dependencyLockSha256())
                .bind("min_client_version", nullable(command.minClientVersion()))
                .bind("changelog", nullable(command.changelog()))
                .bind("report", json(report))
                .bind("expected_revision", expectedRevision)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markReady(
            UUID id,
            String objectKey,
            int fileCount,
            long extractedBytes,
            YcpsMetadata ycps,
            Map<String, Object> report) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET status = 'READY',
                            object_key = :object_key,
                            quarantine_key = NULL,
                            file_count = :file_count,
                            extracted_bytes = :extracted_bytes,
                            scan_status = 'PASSED',
                            scan_report = :report::jsonb,
                            package_format = 'YCPS',
                            ycps_version = :ycps_version,
                            package_kind = :package_kind,
                            plugin_manifest = :plugin_manifest::jsonb,
                            runtime_manifest = :runtime_manifest::jsonb,
                            manifest_sha256 = :manifest_sha256,
                            dependency_lock_sha256 = :dependency_lock_sha256
                        WHERE id = :id AND deleted_at IS NULL AND status = 'DRAFT'
                        """)
                .bind("id", id)
                .bind("object_key", objectKey)
                .bind("file_count", fileCount)
                .bind("extracted_bytes", extractedBytes)
                .bind("ycps_version", ycps.ycpsVersion())
                .bind("package_kind", ycps.kind())
                .bind("plugin_manifest", ycps.pluginJson())
                .bind("runtime_manifest", ycps.runtimeJson())
                .bind("manifest_sha256", ycps.manifestSha256())
                .bind("dependency_lock_sha256", ycps.dependencyLockSha256())
                .bind("report", json(report))
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> markFailed(UUID id, String scanStatus, String report) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET status = 'REJECTED',
                            scan_status = :scan_status,
                            quarantine_key = NULL,
                            scan_report = :report::jsonb
                        WHERE id = :id AND deleted_at IS NULL AND status = 'DRAFT'
                        """)
                .bind("id", id)
                .bind("scan_status", scanStatus)
                .bind("report", json(Map.of("reason", report == null ? "rejected" : report)))
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> sealAndMarkPublished(
            UUID id,
            Instant now) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET status = 'PUBLISHED',
                            sealed = true,
                            sealed_at = :now
                        WHERE id = :id AND deleted_at IS NULL AND status = 'READY'
                          AND package_format = 'YCPS' AND ycps_version IN ('1.0.0', '1.1.0')
                          AND NOT sealed
                        """)
                .bind("id", id)
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    /** Re-publishes the same already-sealed immutable revision after an ordinary unpublish. */
    Mono<Long> republishSealed(UUID id) {
        return databaseClient.sql("""
                        UPDATE gateway.skill_version SET status = 'PUBLISHED'
                        WHERE id = :id AND deleted_at IS NULL AND status = 'ARCHIVED'
                          AND package_format = 'YCPS' AND ycps_version IN ('1.0.0', '1.1.0')
                          AND sealed
                        """)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> archiveOthers(UUID skillId, UUID keepId) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version SET status = 'ARCHIVED'
                        WHERE skill_id = :skill_id AND deleted_at IS NULL
                          AND status = 'PUBLISHED' AND id <> :keep_id
                        """)
                .bind("skill_id", skillId)
                .bind("keep_id", keepId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> archiveAll(UUID skillId) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version SET status = 'ARCHIVED'
                        WHERE skill_id = :skill_id AND deleted_at IS NULL AND status = 'PUBLISHED'
                        """)
                .bind("skill_id", skillId)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> softDelete(UUID id) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version
                        SET deleted_at = now(), status = 'ARCHIVED'
                        WHERE id = :id AND deleted_at IS NULL AND status <> 'PUBLISHED'
                        """)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> incrementDownloadCount(UUID id) {
        return databaseClient
                .sql("""
                        UPDATE gateway.skill_version SET download_count = download_count + 1
                        WHERE id = :id AND deleted_at IS NULL
                        """)
                .bind("id", id)
                .fetch()
                .rowsUpdated();
    }

    /** Serialises de-duplication and counter increments for all sessions of one package version. */
    Mono<Void> lockForDownloadCount(UUID id) {
        return databaseClient.sql("""
                        SELECT id
                        FROM gateway.skill_version
                        WHERE id = :id
                        FOR UPDATE
                        """)
                .bind("id", id)
                .map((row, metadata) -> row.get("id", UUID.class))
                .one()
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "gateway_skill_version_missing_for_download")))
                .then();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("json_serialization_failed", exception);
        }
    }

    /**
     * Same R2DBC trap as {@code SkillRepository#nullable}: {@code bind(name, null)} throws
     * "Value for parameter X must not be null", so optional columns ({@code min_client_version},
     * {@code changelog}) must be bound through {@link Parameter#empty(Class)}.
     */
    private static Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameter.empty(String.class)
                : Parameter.from(value.strip());
    }

    private static PackageRef packageRef(io.r2dbc.spi.Row row) {
        Long size = row.get("size_bytes", Long.class);
        Integer revision = row.get("revision", Integer.class);
        return new PackageRef(
                row.get("skill_id", UUID.class),
                row.get("id", UUID.class),
                row.get("version", String.class),
                row.get("object_key", String.class),
                size == null ? 0L : size,
                row.get("md5", String.class),
                row.get("sha256", String.class),
                row.get("min_client_version", String.class),
                revision == null ? 0 : revision,
                row.get("package_format", String.class),
                row.get("ycps_version", String.class),
                row.get("package_kind", String.class),
                jsonNode(row.get("dependencies", String.class)),
                row.get("manifest_sha256", String.class),
                row.get("dependency_lock_sha256", String.class));
    }

    record PackageCommand(
            UUID skillId,
            String version,
            int revision,
            String quarantineKey,
            String fileName,
            long sizeBytes,
            String md5,
            String sha256,
            String minClientVersion,
            String changelog,
            UUID actor,
            Instant now) {}

    record YcpsMetadata(
            String ycpsVersion,
            String kind,
            String pluginJson,
            String runtimeJson,
            String manifestSha256,
            String dependencyLockSha256) {}

    record DependencyRef(
            String key,
            String relation,
            boolean required,
            String code,
            String kind,
            String version,
            int revision,
            String sha256) {}

    record ResolvedDependency(
            String key,
            String relation,
            boolean required,
            UUID targetVersionId,
            String code,
            String kind,
            String version,
            int revision,
            String sha256) {}

    record DependencyGraphEdge(
            UUID ownerVersionId,
            UUID targetVersionId,
            String code,
            String kind,
            String version,
            int revision,
            String sha256,
            int depth,
            boolean codeCycle) {}

    /** One A19 review row: the stored lock plus the live state of its target. */
    record DependencyRow(
            String key,
            String relation,
            boolean required,
            String targetCode,
            String targetKind,
            String targetVersion,
            int targetRevision,
            String targetSha256,
            UUID targetVersionId,
            UUID targetSkillId,
            String targetVersionStatus,
            boolean targetOnline) {}

    /** One A20 review row: an owner revision that locks a dependency key of the reviewed code. */
    record DependentRow(
            UUID ownerSkillId,
            String ownerCode,
            String ownerKind,
            UUID ownerVersionId,
            String ownerVersion,
            int ownerRevision,
            String ownerStatus,
            boolean ownerOnline,
            String key,
            String relation,
            boolean required,
            String targetVersion,
            int targetRevision) {}

    /** Online package with the object key required for range streaming. */
    record PackageRef(
            UUID skillId,
            UUID versionId,
            String version,
            String objectKey,
            long sizeBytes,
            String md5,
            String sha256,
            String minClientVersion,
            int revision,
            String packageFormat,
            String ycpsVersion,
            String packageKind,
            com.fasterxml.jackson.databind.JsonNode dependencies,
            String manifestSha256,
            String dependencyLockSha256) {}

    private static com.fasterxml.jackson.databind.JsonNode jsonNode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new ObjectMapper().readTree(value);
        } catch (Exception exception) {
            throw new IllegalStateException("invalid_ycps_json_in_database", exception);
        }
    }
}
