package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.routing.ChannelRouter;
import com.yuzhihuihe.apiplatform.gateway.routing.CredentialCipher;
import org.junit.jupiter.api.Test;

class RouteTargetCredentialTest {

    private static final String ENCRYPTION_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encryptedCredentialIsNotDecryptedUntilTheRouteIsMaterialized() {
        CountingCredentialCipher cipher = new CountingCredentialCipher();
        ChatCompletionService.ChannelCredential credential =
                DatabaseGatewayCatalog.encryptedCredential(new byte[] {1}, cipher);

        assertThat(cipher.resolutions).hasValue(0);
        assertThat(credential.resolve()).isEqualTo("database-secret");
        assertThat(cipher.resolutions).hasValue(1);
    }

    @Test
    void materializesOnlyTheCredentialRequiredByTheAttemptedRoute() {
        AtomicInteger resolutions = new AtomicInteger();
        ProviderAdapter.Channel metadata = channel("");
        ChatCompletionService.RouteTarget target = new ChatCompletionService.RouteTarget(
                candidate(metadata), metadata, 60, 10, () -> {
                    resolutions.incrementAndGet();
                    return "attempted-secret";
                });

        assertThat(resolutions).hasValue(0);
        assertThat(target.channel().apiKey()).isEmpty();
        assertThat(target.materializedChannel().apiKey()).isEqualTo("attempted-secret");
        assertThat(resolutions).hasValue(1);
    }

    private ProviderAdapter.Channel channel(String credential) {
        return new ProviderAdapter.Channel(
                UUID.fromString("93000000-0000-4000-8000-000000000001"),
                UUID.fromString("93000000-0000-4000-8000-000000000002"),
                "OPENAI_COMPATIBLE", "https://provider.example/v1", credential,
                30_000, "upstream-model");
    }

    private ChannelRouter.RouteCandidate candidate(ProviderAdapter.Channel channel) {
        return new ChannelRouter.RouteCandidate(
                channel.id(), channel.providerType(), "text-basic", channel.upstreamModel(),
                Set.of(ChannelRouter.Capability.TEXT), 10, 100,
                true, true, true, true, true);
    }

    private static final class CountingCredentialCipher extends CredentialCipher {
        private final AtomicInteger resolutions = new AtomicInteger();

        private CountingCredentialCipher() {
            super(ENCRYPTION_KEY);
        }

        @Override
        public String decrypt(byte[] encrypted) {
            resolutions.incrementAndGet();
            return "database-secret";
        }
    }
}
