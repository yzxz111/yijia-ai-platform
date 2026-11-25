package com.yuzhihuihe.apiplatform.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhihuihe.apiplatform.platform.grant.PackageSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderApplicationServiceUnitTest {

    private static final UUID TENANT_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID USER_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ID =
            UUID.fromString("93000000-0000-4000-8000-000000000015");
    private static final String GENERATED_CODE = "PKG_93000000000040008000000000000014";
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");

    private final OrderApplicationRepository repository = mock(OrderApplicationRepository.class);
    private final PackageSnapshotService snapshotService = mock(PackageSnapshotService.class);
    private final PackagePurchasePolicyService purchasePolicyService =
            mock(PackagePurchasePolicyService.class);
    private final PackageInventoryService inventoryService = mock(PackageInventoryService.class);
    private final AtomicReference<OrderApplicationRepository.ExistingOrderRow> insertedOrder =
            new AtomicReference<>();

    private OrderApplicationService service;

    @BeforeEach
    void setUp() {
        when(repository.hasActiveUser(TENANT_ID, USER_ID)).thenReturn(true);
        when(repository.findExisting(USER_ID, "generated-standard-checkout"))
                .thenAnswer(ignored -> Optional.ofNullable(insertedOrder.get()));
        doAnswer(invocation -> {
            insertedOrder.set(new OrderApplicationRepository.ExistingOrderRow(
                    invocation.getArgument(2), invocation.getArgument(4), invocation.getArgument(1),
                    "PENDING", invocation.getArgument(5), invocation.getArgument(6),
                    invocation.getArgument(7), invocation.getArgument(8)));
            return null;
        }).when(repository).insertOrder(
                any(), any(), eq(TENANT_ID), eq(USER_ID), eq(VERSION_ID),
                anyLong(), any(), any(), any(), eq("generated-standard-checkout"));
        service = new OrderApplicationService(
                repository,
                new ObjectMapper().findAndRegisterModules(),
                snapshotService,
                purchasePolicyService,
                inventoryService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsOrderForGeneratedStandardPackageCode() {
        PackageSnapshotService.PackageSnapshot snapshot = snapshot("STANDARD", 49_900L);
        when(snapshotService.loadPublished(VERSION_ID)).thenReturn(snapshot);

        OrderApplicationService.OrderCreated result = service.create(
                TENANT_ID,
                USER_ID,
                "generated-standard-checkout",
                new OrderApplicationService.CreateOrderCommand(VERSION_ID));

        assertThat(result.amount()).isEqualTo(49_900L);
        assertThat(result.packageSnapshot().path("packageCode").asText()).isEqualTo(GENERATED_CODE);
        assertThat(result.packageSnapshot().path("packageKind").asText()).isEqualTo("STANDARD");
        verify(purchasePolicyService).assertCanCreate(TENANT_ID, USER_ID, snapshot);
        verify(inventoryService).reserve(TENANT_ID, USER_ID, VERSION_ID);
    }

    @Test
    void rejectsFreeClaimPackageFromPaidCheckout() {
        when(repository.findExisting(USER_ID, "free-claim-checkout")).thenReturn(Optional.empty());
        when(snapshotService.loadPublished(VERSION_ID)).thenReturn(snapshot("FREE_CLAIM", 100L));

        assertThatThrownBy(() -> service.create(
                TENANT_ID,
                USER_ID,
                "free-claim-checkout",
                new OrderApplicationService.CreateOrderCommand(VERSION_ID)))
                .hasMessageContaining("paid_package_required");

        verify(purchasePolicyService, never()).assertCanCreate(any(), any(), any());
        verify(inventoryService, never()).reserve(any(), any(), any());
        verify(repository, never()).insertOrder(
                any(), any(), any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    private PackageSnapshotService.PackageSnapshot snapshot(String kind, long priceAmount) {
        return new PackageSnapshotService.PackageSnapshot(
                VERSION_ID,
                GENERATED_CODE,
                "Generated Standard",
                kind,
                priceAmount,
                "CNY",
                5_000_000L,
                null,
                30,
                3,
                60,
                100_000L,
                2,
                null,
                null,
                20L * 1024 * 1024,
                false,
                "TOKEN",
                List.of());
    }
}
