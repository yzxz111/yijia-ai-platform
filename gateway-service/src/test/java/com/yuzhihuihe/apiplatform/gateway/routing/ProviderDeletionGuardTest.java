package com.yuzhihuihe.apiplatform.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.image.ImageAdapterRegistry;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderDeletionView;
import com.yuzhihuihe.apiplatform.gateway.routing.api.GatewayAdminResponses.ProviderView;
import com.yuzhihuihe.apiplatform.gateway.video.VideoAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 删除服务商的两条硬边界。
 *
 * <p>删除不可逆，因此断言不只看「有没有报错」，还要看**被拒时一行都没删**：
 * 一个「先删渠道、再发现不能删」的实现会留下半个坏状态，而调用方只看到一个 409。
 *
 * <p>用打桩仓储而不是真实库：这里的判据是「没有用量引用、且调用方明确要级联才允许删」，
 * 在单元层面钉住它比造一批带外键的用量数据更直接。级联删除的 SQL 本身由
 * {@code GatewayAdminServiceTest} 在真实 Postgres 上覆盖。
 */
class ProviderDeletionGuardTest {

    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID PROVIDER_ID = UUID.fromString("81000000-0000-4000-8000-00000000000f");

    private final GatewayAdminRepository repository = mock(GatewayAdminRepository.class);

    private GatewayAdminService service() {
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        return new GatewayAdminService(
                repository, transactions, new CredentialCipher(key),
                mock(PlatformPackageModelClient.class));
    }

    /** 每个用例显式声明「这个服务商存在」，避免共享的默认打桩互相覆盖。 */
    private void existingProvider() {
        when(repository.provider(PROVIDER_ID)).thenReturn(Mono.just(new ProviderView(
                PROVIDER_ID, "junk", "废弃接入", "DEEPSEEK", "https://junk.test/v1", false)));
    }

    private void stubAudit() {
        when(repository.audit(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    void refusesDeletionWhenUsageCostOrTaskRowsReferenceTheProvider() {
        existingProvider();
        when(repository.providerUsageReferences(PROVIDER_ID)).thenReturn(Mono.just(3L));

        assertThatThrownBy(() -> service().deleteProvider(PROVIDER_ID, true, ACTOR).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("provider_has_usage_history");
                });

        // 关键：被拒时不能已经删掉任何东西。
        verify(repository, never()).deleteChannelScopedRows(any());
        verify(repository, never()).deleteChannelsOfProvider(any());
        verify(repository, never()).deleteProviderPrices(any());
        verify(repository, never()).deleteProvider(any());
    }

    @Test
    void refusesDeletionWithChannelsUnlessCascadeIsExplicit() {
        existingProvider();
        when(repository.providerUsageReferences(PROVIDER_ID)).thenReturn(Mono.just(0L));
        when(repository.channelIdsOfProvider(PROVIDER_ID))
                .thenReturn(Flux.just(UUID.randomUUID()));

        assertThatThrownBy(() -> service().deleteProvider(PROVIDER_ID, false, ACTOR).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.getReason()).isEqualTo("provider_has_channels");
                });

        verify(repository, never()).deleteChannelsOfProvider(any());
        verify(repository, never()).deleteProvider(any());
    }

    @Test
    void cascadeRemovesConfigurationAndReportsExactlyWhatItRemoved() {
        UUID channelId = UUID.randomUUID();
        existingProvider();
        stubAudit();
        when(repository.providerUsageReferences(PROVIDER_ID)).thenReturn(Mono.just(0L));
        when(repository.channelIdsOfProvider(PROVIDER_ID)).thenReturn(Flux.just(channelId));
        when(repository.countChannelModels(List.of(channelId))).thenReturn(Mono.just(4L));
        when(repository.deleteChannelScopedRows(List.of(channelId))).thenReturn(Mono.just(6L));
        when(repository.deleteChannelsOfProvider(PROVIDER_ID)).thenReturn(Mono.just(1L));
        when(repository.deleteProviderPrices(PROVIDER_ID)).thenReturn(Mono.just(2L));
        when(repository.deleteProvider(PROVIDER_ID)).thenReturn(Mono.just(1L));

        ProviderDeletionView view = service().deleteProvider(PROVIDER_ID, true, ACTOR).block();

        assertThat(view).isNotNull();
        assertThat(view.code()).isEqualTo("junk");
        assertThat(view.deletedChannels()).isEqualTo(1);
        assertThat(view.deletedRoutes()).isEqualTo(4);
        assertThat(view.deletedPrices()).isEqualTo(2);
        verify(repository).deleteChannelsOfProvider(PROVIDER_ID);
        verify(repository).deleteProvider(PROVIDER_ID);
    }

    @Test
    void missingProviderIsReportedAsNotFound() {
        when(repository.provider(PROVIDER_ID)).thenReturn(Mono.empty());

        assertThatThrownBy(() -> service().deleteProvider(PROVIDER_ID, true, ACTOR).block())
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.getReason()).isEqualTo("provider_not_found");
                });

        verify(repository, never()).deleteProvider(any());
    }

    @Test
    void aProviderWithoutChannelsIsDeletedWithoutCascade() {
        existingProvider();
        stubAudit();
        when(repository.providerUsageReferences(PROVIDER_ID)).thenReturn(Mono.just(0L));
        when(repository.channelIdsOfProvider(PROVIDER_ID)).thenReturn(Flux.empty());
        when(repository.countChannelModels(List.of())).thenReturn(Mono.just(0L));
        when(repository.deleteChannelScopedRows(List.of())).thenReturn(Mono.just(0L));
        when(repository.deleteChannelsOfProvider(PROVIDER_ID)).thenReturn(Mono.just(0L));
        when(repository.deleteProviderPrices(PROVIDER_ID)).thenReturn(Mono.just(0L));
        when(repository.deleteProvider(PROVIDER_ID)).thenReturn(Mono.just(1L));

        ProviderDeletionView view = service().deleteProvider(PROVIDER_ID, false, ACTOR).block();

        assertThat(view).isNotNull();
        assertThat(view.deletedChannels()).isZero();
        assertThat(view.deletedRoutes()).isZero();
        verify(repository).deleteProvider(PROVIDER_ID);
    }

    @Test
    void aNullIdIsRejectedWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service().deleteProvider(null, true, ACTOR).block())
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).provider(any());
    }

    @Test
    void anUnknownProviderTypeIsNeverAssumedCapable() {
        // 同属「清理废弃接入」的边界：清理时若顺带改类型，未知类型一律不可服务。
        ProviderCapabilityCatalog catalog = new ProviderCapabilityCatalog(
                List.of(), mock(ImageAdapterRegistry.class), mock(VideoAdapterRegistry.class),
                // 未知类型永远不可服务，与音频能力无关；null 表示"没有音频适配器"。
                null);
        assertThat(catalog.unavailableReason("JUNK", Set.of("TEXT")))
                .isEqualTo(ProviderCapabilityCatalog.UNAVAILABLE_REASON);
    }
}
