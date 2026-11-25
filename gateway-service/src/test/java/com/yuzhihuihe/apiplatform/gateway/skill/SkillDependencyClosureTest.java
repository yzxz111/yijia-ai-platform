package com.yuzhihuihe.apiplatform.gateway.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class SkillDependencyClosureTest {

    private static final UUID ROOT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String ROOT_SHA = "0".repeat(64);

    @Test
    void acceptsAConsistentDiamond() {
        UUID common = UUID.randomUUID();
        List<SkillVersionRepository.DependencyGraphEdge> edges = List.of(
                edge(UUID.randomUUID(), "left", "1.0.0", 1, "1".repeat(64), 1, false),
                edge(UUID.randomUUID(), "right", "1.0.0", 1, "2".repeat(64), 1, false),
                new SkillVersionRepository.DependencyGraphEdge(
                        UUID.randomUUID(), common, "common", "SKILL", "2.0.0", 3,
                        "3".repeat(64), 2, false),
                new SkillVersionRepository.DependencyGraphEdge(
                        UUID.randomUUID(), common, "common", "SKILL", "2.0.0", 3,
                        "3".repeat(64), 2, false));

        assertThat(validate(edges)).isNull();
    }

    @Test
    void enforcesCycleDepthAndConflictingDiamondLocksAtFrozenBoundary() {
        assertThat(validate(List.of(edge(
                UUID.randomUUID(), "root", "1.0.0", 1, ROOT_SHA, 2, true))))
                .isEqualTo("skill_dependency_cycle");
        assertThat(validate(List.of(edge(
                UUID.randomUUID(), "boundary", "1.0.0", 1, "1".repeat(64), 32, false))))
                .isNull();
        assertThat(validate(List.of(edge(
                UUID.randomUUID(), "deep", "1.0.0", 1, "1".repeat(64), 33, false))))
                .isEqualTo("skill_dependency_depth_exceeded");
        assertThat(validate(List.of(
                edge(UUID.randomUUID(), "common", "1.0.0", 1, "1".repeat(64), 2, false),
                edge(UUID.randomUUID(), "common", "2.0.0", 1, "2".repeat(64), 2, false))))
                .isEqualTo("skill_dependency_lock_conflict");
    }

    @Test
    void enforcesOneHundredTwentyEightPackageClosureBoundary() {
        List<SkillVersionRepository.DependencyGraphEdge> edges = new ArrayList<>();
        for (int index = 0; index < 127; index++) {
            edges.add(edge(UUID.randomUUID(), "dep-" + index, "1.0.0", 1,
                    "%064x".formatted(index + 1), 1, false));
        }
        assertThat(validate(edges)).isNull();
        edges.add(edge(UUID.randomUUID(), "dep-127", "1.0.0", 1,
                "%064x".formatted(128), 1, false));
        assertThat(validate(edges)).isEqualTo("skill_dependency_count_exceeded");
    }

    private static String validate(List<SkillVersionRepository.DependencyGraphEdge> edges) {
        return SkillAdminService.dependencyClosureError(
                ROOT, "root", "TEAM", "1.0.0", 1, ROOT_SHA, edges);
    }

    private static SkillVersionRepository.DependencyGraphEdge edge(
            UUID target,
            String code,
            String version,
            int revision,
            String sha,
            int depth,
            boolean cycle) {
        return new SkillVersionRepository.DependencyGraphEdge(
                ROOT, target, code, "SKILL", version, revision, sha, depth, cycle);
    }
}
