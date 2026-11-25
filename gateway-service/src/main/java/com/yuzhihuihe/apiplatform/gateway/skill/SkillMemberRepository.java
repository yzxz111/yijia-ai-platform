package com.yuzhihuihe.apiplatform.gateway.skill;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Parameterised R2DBC SQL for {@code gateway.skill_member} (design 2.2 / D3).
 *
 * <p>The roster is replaced as a whole: {@link #replaceAll} deletes the current rows and inserts the
 * new ones, so the caller must wrap it in a transaction (the service does). The primary key is
 * generated here because the sort order of a roster is part of the contract and rows are never
 * returned with a database-generated id.
 *
 * <p>Ordering is {@code (sort_order, member_key)} everywhere so a roster without explicit sort
 * numbers still round-trips deterministically (V7).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class SkillMemberRepository {

    private static final String SELECT_MEMBER = """
            SELECT member.id, member.skill_id, member.member_key, member.name, member.name_en,
                   member.profession, member.profession_en,
                   member.member_role, member.sort_order, member.created_at, member.updated_at
            FROM gateway.skill_member member
            """;

    private static final String ORDER = " ORDER BY member.sort_order ASC, member.member_key ASC";

    private final DatabaseClient databaseClient;

    public SkillMemberRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    Flux<MemberRow> listBySkill(UUID skillId) {
        return databaseClient
                .sql(SELECT_MEMBER + " WHERE member.skill_id = :skill_id" + ORDER)
                .bind("skill_id", skillId)
                .map((row, metadata) -> member(row))
                .all();
    }

    Mono<MemberRow> member(UUID skillId, String memberKey) {
        return databaseClient
                .sql(SELECT_MEMBER + " WHERE member.skill_id = :skill_id AND member.member_key = :member_key")
                .bind("skill_id", skillId)
                .bind("member_key", memberKey)
                .map((row, metadata) -> member(row))
                .one();
    }

    /**
     * E-04/E-05 are validated by the service before this runs. Idempotent: replacing a roster with
     * the same content twice leaves exactly the same rows (V7).
     */
    Mono<Void> replaceAll(UUID skillId, List<Member> members) {
        return databaseClient
                .sql("DELETE FROM gateway.skill_member WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .fetch()
                .rowsUpdated()
                .then()
                .thenMany(Flux.fromIterable(members == null ? List.<Member>of() : members)
                        .concatMap(member -> insert(skillId, member)))
                .then();
    }

    private Mono<Long> insert(UUID skillId, Member member) {
        Instant now = Instant.now();
        return databaseClient
                .sql("""
                        INSERT INTO gateway.skill_member(
                            id, skill_id, member_key, name, name_en, profession, profession_en,
                            member_role, sort_order, created_at, updated_at)
                        VALUES (:id, :skill_id, :member_key, :name, :name_en, :profession,
                            :profession_en, :member_role, :sort_order, :now, :now)
                        """)
                .bind("id", UUID.randomUUID())
                .bind("skill_id", skillId)
                .bind("member_key", member.memberKey())
                .bind("name", nullable(member.name()))
                .bind("name_en", nullable(member.nameEn()))
                .bind("profession", nullable(member.profession()))
                .bind("profession_en", nullable(member.professionEn()))
                .bind("member_role", member.role())
                .bind("sort_order", member.sortOrder())
                .bind("now", now)
                .fetch()
                .rowsUpdated();
    }

    Mono<Long> countBySkill(UUID skillId) {
        return databaseClient
                .sql("SELECT count(*) FROM gateway.skill_member WHERE skill_id = :skill_id")
                .bind("skill_id", skillId)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .map(count -> count == null ? 0L : count);
    }

    /**
     * D5/E-08: the admin and client lists report {@code memberCount} for a whole page, so the count
     * is fetched in one grouped query instead of one query per skill (no N+1).
     */
    Mono<List<MemberCount>> countBySkillBatch(Collection<UUID> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return Mono.just(List.of());
        }
        List<UUID> ids = List.copyOf(skillIds);
        return databaseClient
                .sql("""
                        SELECT skill_id, count(*) AS member_count
                        FROM gateway.skill_member
                        WHERE skill_id = ANY(:skill_ids)
                        GROUP BY skill_id
                        """)
                .bind("skill_ids", ids.toArray(UUID[]::new))
                .map((row, metadata) -> {
                    Long count = row.get("member_count", Long.class);
                    return new MemberCount(
                            row.get("skill_id", UUID.class), count == null ? 0L : count);
                })
                .all()
                .collectList();
    }

    private static MemberRow member(io.r2dbc.spi.Row row) {
        Integer sortOrder = row.get("sort_order", Integer.class);
        return new MemberRow(
                row.get("id", UUID.class),
                row.get("skill_id", UUID.class),
                row.get("member_key", String.class),
                row.get("name", String.class),
                row.get("name_en", String.class),
                row.get("profession", String.class),
                row.get("profession_en", String.class),
                row.get("member_role", String.class),
                sortOrder == null ? 0 : sortOrder,
                instant(row.get("created_at", java.time.OffsetDateTime.class)),
                instant(row.get("updated_at", java.time.OffsetDateTime.class)));
    }

    private static Instant instant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** R2DBC rejects {@code bind(name, null)}; optional columns go through {@link Parameter#empty}. */
    private static Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameter.empty(String.class)
                : Parameter.from(value.strip());
    }

    /**
     * One roster entry as written by the service. A member is an expert package: {@code memberKey}
     * names it, and that package's own icon supplies the member portrait (no per-member image).
     */
    record Member(
            String memberKey,
            String name,
            String nameEn,
            String profession,
            String professionEn,
            String role,
            int sortOrder) {}

    record MemberRow(
            UUID id,
            UUID skillId,
            String memberKey,
            String name,
            String nameEn,
            String profession,
            String professionEn,
            String role,
            int sortOrder,
            Instant createdAt,
            Instant updatedAt) {}

    record MemberCount(UUID skillId, long count) {}
}

