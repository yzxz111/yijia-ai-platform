package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** SQL used only by the bounded Skill retention job. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class SkillRetentionRepository {

    private final DatabaseClient databaseClient;

    SkillRetentionRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Flux<UUID> purgeCandidates(Instant deletedBefore, Instant now, int limit) {
        return databaseClient.sql("""
                        SELECT skill.id
                        FROM gateway.skill skill
                        WHERE skill.deleted_at IS NOT NULL
                          AND skill.deleted_at <= :deleted_before
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.skill_download download
                              WHERE download.skill_id = skill.id
                                AND download.expires_at > :now
                          )
                        ORDER BY skill.deleted_at, skill.id
                        LIMIT :limit
                        """)
                .bind("deleted_before", deletedBefore)
                .bind("now", now)
                .bind("limit", limit)
                .map((row, metadata) -> row.get("id", UUID.class))
                .all();
    }

    Flux<String> objectKeys(UUID skillId) {
        return databaseClient.sql("""
                        SELECT object_key
                        FROM (
                            SELECT icon_object_key AS object_key
                            FROM gateway.skill
                            WHERE id = :skill_id
                            UNION
                            SELECT illustration_object_key AS object_key
                            FROM gateway.skill
                            WHERE id = :skill_id
                            UNION
                            SELECT example_image_object_key AS object_key
                            FROM gateway.skill
                            WHERE id = :skill_id
                            UNION
                            SELECT object_key
                            FROM gateway.skill_version
                            WHERE skill_id = :skill_id
                            UNION
                            SELECT quarantine_key AS object_key
                            FROM gateway.skill_version
                            WHERE skill_id = :skill_id
                            UNION
                            SELECT object_key
                            FROM gateway.skill_download
                            WHERE skill_id = :skill_id
                            UNION
                            SELECT object_key
                            FROM gateway.native_clawhub_source
                            WHERE skill_id = :skill_id
                            UNION
                            SELECT object_key
                            FROM gateway.native_clawhub_release
                            WHERE skill_id = :skill_id
                        ) referenced
                        WHERE object_key IS NOT NULL AND object_key <> ''
                        """)
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get("object_key", String.class))
                .all();
    }

    Mono<Long> deleteVersions(UUID skillId) {
        return databaseClient.sql(
                        "DELETE FROM gateway.skill_version WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .fetch().rowsUpdated();
    }

    Mono<Long> deleteNativeReleases(UUID skillId) {
        return databaseClient.sql(
                        "DELETE FROM gateway.native_clawhub_release WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .fetch().rowsUpdated();
    }

    Mono<Long> deleteNativeSources(UUID skillId) {
        return databaseClient.sql(
                        "DELETE FROM gateway.native_clawhub_source WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .fetch().rowsUpdated();
    }

    Mono<Long> deleteMembers(UUID skillId) {
        return databaseClient.sql(
                        "DELETE FROM gateway.skill_member WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .fetch().rowsUpdated();
    }

    Mono<Long> deleteSkill(UUID skillId, Instant deletedBefore, Instant now) {
        return databaseClient.sql("""
                        DELETE FROM gateway.skill skill
                        WHERE skill.id = :skill_id
                          AND skill.deleted_at IS NOT NULL
                          AND skill.deleted_at <= :deleted_before
                          AND NOT EXISTS (
                              SELECT 1
                              FROM gateway.skill_download download
                              WHERE download.skill_id = skill.id
                                AND download.expires_at > :now
                          )
                        """)
                .bind("skill_id", skillId)
                .bind("deleted_before", deletedBefore)
                .bind("now", now)
                .fetch().rowsUpdated();
    }
}
