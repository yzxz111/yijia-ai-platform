package com.yuzhihuihe.apiplatform.gateway.skill;

import java.util.List;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.skill.SkillMemberRepository.MemberRow;
import com.yuzhihuihe.apiplatform.gateway.skill.api.SkillAdminResponses.MemberView;

/**
 * Single place that turns {@code gateway.skill_member} rows into the two documented view shapes, so
 * the admin console and the API-key client can never disagree about a roster entry.
 *
 * <p>A roster entry is a reference to another expert package: {@code memberKey} names that package
 * (validated against the published expert catalog), and the member's portrait is simply that
 * package's own icon. There is no per-member image any more — a team reuses the portraits its
 * member experts already publish, so a roster never carries a second copy of the same face.
 *
 * <p>The only difference between the surfaces is {@code avatarUrl}: the admin preview endpoint is
 * keyed by skill id, the client endpoint by skill code (design 3.1/3.2).
 */
final class SkillMemberViews {

    private SkillMemberViews() {}

    static MemberView admin(MemberRow row) {
        return new MemberView(
                row.id(),
                row.memberKey(),
                row.name(),
                row.nameEn(),
                row.profession(),
                row.professionEn(),
                row.role(),
                row.sortOrder(),
                "/console/gateway/skills/" + row.memberKey() + "/icon");
    }

    static MemberView client(MemberRow row) {
        return new MemberView(
                row.id(),
                row.memberKey(),
                row.name(),
                row.nameEn(),
                row.profession(),
                row.professionEn(),
                row.role(),
                row.sortOrder(),
                "/v1/skills/" + row.memberKey() + "/icon");
    }

    static List<MemberView> admin(List<MemberRow> rows) {
        return rows.stream().map(SkillMemberViews::admin).toList();
    }

    static List<MemberView> client(List<MemberRow> rows) {
        return rows.stream().map(SkillMemberViews::client).toList();
    }
}
