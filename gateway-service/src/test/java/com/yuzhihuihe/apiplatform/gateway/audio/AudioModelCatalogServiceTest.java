package com.yuzhihuihe.apiplatform.gateway.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.yuzhihuihe.apiplatform.gateway.apikey.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class AudioModelCatalogServiceTest {

    private static final UUID API_KEY_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PACKAGE_ID = UUID.randomUUID();

    private AudioRouteRepository repository;
    private AudioAdapterRegistry adapterRegistry;
    private AudioModelCatalogService catalog;

    @BeforeEach
    void setUp() {
        repository = mock(AudioRouteRepository.class);
        adapterRegistry = mock(AudioAdapterRegistry.class);
        catalog = new AudioModelCatalogService(repository, adapterRegistry);
    }

    @Test
    void returnsKeyAllowedAudioAliasesWithTheirDisplayNamesSoTheSubtitleVariantIsDistinguishable() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)).thenReturn(Flux.just(
                row("audio-asr-basic", "语音识别基础版", "[\"AUDIO_TRANSCRIBE\"]", "SECOND"),
                // 同一个能力名，但展示名不同——这正是客户端区分"带时间戳字幕"的唯一线索。
                row("audio-asr-subtitle", "语音识别·字幕版", "[\"AUDIO_TRANSCRIBE\"]", "SECOND"),
                row("audio-retalk", "口型替换", "[\"LIP_SYNC\"]", "SECOND")));
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID,
                Set.of("audio-asr-basic", "audio-asr-subtitle"));

        assertThat(catalog.availableModels(context).collectList().block())
                .containsExactly(
                        new AudioModelCatalogService.AudioModelView(
                                "audio-asr-basic", "语音识别基础版",
                                Set.of("AUDIO_TRANSCRIBE"), "SECOND", "AUDIO_SECOND", List.of()),
                        new AudioModelCatalogService.AudioModelView(
                                "audio-asr-subtitle", "语音识别·字幕版",
                                Set.of("AUDIO_TRANSCRIBE"), "SECOND", "AUDIO_SECOND", List.of()));
    }

    @Test
    void sortsCapabilitiesAlphabeticallySoTheJsonArrayOrderIsDeterministic() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)).thenReturn(Flux.just(
                row("audio-probe", "探测", "[\"LIP_SYNC\",\"AUDIO_SYNTHESIS\",\"AUDIO_TRANSCRIBE\"]", "SECOND")));
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID, Set.of("audio-probe"));

        var view = catalog.availableModels(context).single().block();

        assertThat(view.capabilities()).containsExactly(
                "AUDIO_SYNTHESIS", "AUDIO_TRANSCRIBE", "LIP_SYNC");
    }

    @Test
    void attachesTheVendorVoicesToSynthesisAliasesOnly() {
        when(repository.availableModels(PACKAGE_ID, TENANT_ID, USER_ID)).thenReturn(Flux.just(
                row("audio-tts-basic", "配音", "[\"AUDIO_SYNTHESIS\"]", "SECOND"),
                row("audio-asr-basic", "语音识别基础版", "[\"AUDIO_TRANSCRIBE\"]", "SECOND")));
        AudioSynthesisAdapter synthesis = mock(AudioSynthesisAdapter.class);
        // 注册序与声明序都故意打乱：目录必须按名字去重、排序后才下发。
        when(synthesis.voices()).thenReturn(List.of(
                new AudioSynthesisAdapter.Voice("Serena", "温柔的小姐姐"),
                new AudioSynthesisAdapter.Voice("Cherry", "阳光积极的小姐姐"),
                new AudioSynthesisAdapter.Voice("Cherry", "重复项，应被去重")));
        when(adapterRegistry.synthesisAdapters()).thenReturn(List.of(synthesis));
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, PACKAGE_ID,
                Set.of("audio-tts-basic", "audio-asr-basic"));

        var views = catalog.availableModels(context).collectList().block();

        assertThat(views).hasSize(2);
        var tts = views.stream().filter(view -> view.id().equals("audio-tts-basic")).findFirst().orElseThrow();
        var asr = views.stream().filter(view -> view.id().equals("audio-asr-basic")).findFirst().orElseThrow();
        // 只给合成别名下发，且按名字排序、去重。
        assertThat(tts.voices()).extracting(AudioSynthesisAdapter.Voice::name)
                .containsExactly("Cherry", "Serena");
        assertThat(asr.voices()).isEmpty();
    }

    @Test
    void aKeyWithoutAnActivePackageHasNoAudioCatalogue() {
        ApiKeyService.ApiKeyContext context = new ApiKeyService.ApiKeyContext(
                API_KEY_ID, TENANT_ID, USER_ID, null, Set.of("audio-asr-basic"));

        assertThat(catalog.availableModels(context).collectList().block()).isEmpty();
        verifyNoInteractions(repository);
    }

    private static AudioRouteRepository.AudioModelCatalogRow row(
            String alias, String displayName, String capabilities, String billingUnit) {
        return new AudioRouteRepository.AudioModelCatalogRow(
                alias, displayName, capabilities, billingUnit);
    }
}
