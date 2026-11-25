package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ChannelRouterTest {

    private static final UUID DEEPSEEK = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BAILIAN_A = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BAILIAN_B = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final UUID BAILIAN_C = UUID.fromString("10000000-0000-4000-8000-000000000004");

    @Test
    void filtersPermissionsCapabilityStateConcurrencyAndCircuitBeforeRouting() {
        ChannelRouter router = new ChannelRouter(bound -> 0);

        List<ChannelRouter.RouteCandidate> plan = router.plan(
                new ChannelRouter.RouteRequest("file-basic", ChannelRouter.Capability.FILE, 3),
                List.of(
                        candidate(DEEPSEEK, "DEEPSEEK", "file-basic", Set.of(ChannelRouter.Capability.TEXT),
                                100, 100, true, true, true, true, true),
                        candidate(BAILIAN_A, "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                90, 100, false, true, true, true, true),
                        candidate(BAILIAN_B, "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                80, 100, true, false, true, true, true),
                        candidate(BAILIAN_C, "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                70, 100, true, true, false, true, true),
                        candidate(UUID.randomUUID(), "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                60, 100, true, true, true, false, true),
                        candidate(UUID.randomUUID(), "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                50, 100, true, true, true, true, false),
                        candidate(UUID.randomUUID(), "BAILIAN", "file-basic", Set.of(ChannelRouter.Capability.FILE),
                                40, 100, true, true, true, true, true)));

        assertThat(plan).singleElement().satisfies(selected -> {
            assertThat(selected.providerCode()).isEqualTo("BAILIAN");
            assertThat(selected.priority()).isEqualTo(40);
            assertThat(selected.circuitKey()).isEqualTo(selected.channelId() + ":file-basic");
        });
    }

    @Test
    void routesImageRequestsOnlyToImageCapableMappings() {
        ChannelRouter router = new ChannelRouter(bound -> 0);
        ChannelRouter.RouteCandidate textOnly = candidate(DEEPSEEK, "DEEPSEEK", "vision-basic",
                Set.of(ChannelRouter.Capability.TEXT), 100, 100,
                true, true, true, true, true);
        ChannelRouter.RouteCandidate imageCapable = candidate(BAILIAN_A, "BAILIAN", "vision-basic",
                Set.of(ChannelRouter.Capability.TEXT, ChannelRouter.Capability.IMAGE), 90, 100,
                true, true, true, true, true);

        List<ChannelRouter.RouteCandidate> plan = router.plan(
                new ChannelRouter.RouteRequest("vision-basic", ChannelRouter.Capability.IMAGE, 3),
                List.of(textOnly, imageCapable));

        assertThat(plan).containsExactly(imageCapable);
    }

    @Test
    void selectsHigherPriorityFirstAndUsesWeightWithinEqualPriority() {
        ChannelRouter router = new ChannelRouter(bound -> bound - 1);
        ChannelRouter.RouteCandidate highLight = candidate(BAILIAN_A, "BAILIAN", "text-pro",
                Set.of(ChannelRouter.Capability.TEXT), 20, 1, true, true, true, true, true);
        ChannelRouter.RouteCandidate highHeavy = candidate(BAILIAN_B, "BAILIAN", "text-pro",
                Set.of(ChannelRouter.Capability.TEXT), 20, 9, true, true, true, true, true);
        ChannelRouter.RouteCandidate low = candidate(BAILIAN_C, "BAILIAN", "text-pro",
                Set.of(ChannelRouter.Capability.TEXT), 10, 1000, true, true, true, true, true);

        List<ChannelRouter.RouteCandidate> plan = router.plan(
                new ChannelRouter.RouteRequest("text-pro", ChannelRouter.Capability.TEXT, 3),
                List.of(highLight, low, highHeavy));

        assertThat(plan).containsExactly(highHeavy, highLight, low);
    }

    @Test
    void supportsPriorityGroupsWhoseTotalWeightExceedsIntegerRange() {
        ChannelRouter router = new ChannelRouter(bound -> bound - 1);
        ChannelRouter.RouteCandidate first = candidate(BAILIAN_A, "BAILIAN", "text-pro",
                Set.of(ChannelRouter.Capability.TEXT), 20, Integer.MAX_VALUE,
                true, true, true, true, true);
        ChannelRouter.RouteCandidate second = candidate(BAILIAN_B, "BAILIAN", "text-pro",
                Set.of(ChannelRouter.Capability.TEXT), 20, 1,
                true, true, true, true, true);

        List<ChannelRouter.RouteCandidate> plan = router.plan(
                new ChannelRouter.RouteRequest("text-pro", ChannelRouter.Capability.TEXT, 2),
                List.of(first, second));

        assertThat(plan).containsExactly(second, first);
    }

    @Test
    void capsAttemptsAtThreeAndNeverFailsOverAfterFirstByte() {
        ChannelRouter router = new ChannelRouter(bound -> 0);
        List<ChannelRouter.RouteCandidate> candidates = List.of(
                candidate(DEEPSEEK, "DEEPSEEK", "text-pro", Set.of(ChannelRouter.Capability.TEXT),
                        40, 1, true, true, true, true, true),
                candidate(BAILIAN_A, "BAILIAN", "text-pro", Set.of(ChannelRouter.Capability.TEXT),
                        30, 1, true, true, true, true, true),
                candidate(BAILIAN_B, "BAILIAN", "text-pro", Set.of(ChannelRouter.Capability.TEXT),
                        20, 1, true, true, true, true, true),
                candidate(BAILIAN_C, "BAILIAN", "text-pro", Set.of(ChannelRouter.Capability.TEXT),
                        10, 1, true, true, true, true, true));

        ChannelRouter.AttemptCursor cursor = router.start(
                new ChannelRouter.RouteRequest("text-pro", ChannelRouter.Capability.TEXT, 99), candidates);

        assertThat(cursor.current()).isPresent();
        assertThat(cursor.failBeforeFirstByte().current()).isPresent();
        ChannelRouter.AttemptCursor third = cursor.failBeforeFirstByte().failBeforeFirstByte();
        assertThat(third.current()).isPresent();
        assertThat(third.failBeforeFirstByte().current()).isEmpty();
        assertThat(cursor.markFirstByteSent().failBeforeFirstByte().current()).isEmpty();
    }

    private static ChannelRouter.RouteCandidate candidate(
            UUID channelId, String provider, String model, Set<ChannelRouter.Capability> capabilities,
            int priority, int weight, boolean enabled, boolean concurrencyAvailable,
            boolean circuitPermitted, boolean packageAllowed, boolean apiKeyAllowed) {
        return new ChannelRouter.RouteCandidate(
                channelId, provider, model, "upstream-" + model, capabilities,
                priority, weight, enabled, concurrencyAvailable, circuitPermitted,
                packageAllowed, apiKeyAllowed);
    }
}
