package com.yuzhihuihe.apiplatform.gateway.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yuzhihuihe.apiplatform.gateway.provider.ProviderAdapter;
import com.yuzhihuihe.apiplatform.gateway.quota.QuotaService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Mono;

class StructuredGatewayLoggingTest {

    @Test
    void usageCompletionLogsOnlyAllowlistedStructuredContext() {
        DatabaseClient databaseClient = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec execute = mock(
                DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        @SuppressWarnings("rawtypes")
        RowsFetchSpec mappedRows = mock(RowsFetchSpec.class);
        when(databaseClient.sql(anyString())).thenReturn(execute);
        when(execute.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(1L));
        when(execute.map(org.mockito.ArgumentMatchers.any(java.util.function.BiFunction.class)))
                .thenReturn(mappedRows);
        when(mappedRows.one()).thenReturn(Mono.empty());

        Logger logger = (Logger) LoggerFactory.getLogger(DatabaseUsageLogWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatCompletionService.UsageEntry entry = usageEntry();
            new DatabaseUsageLogWriter(databaseClient).write(entry)
                    .contextWrite(context -> context
                            .put("traceId", "trace-123")
                            .put("apiKeyPrefix", "sk-yzh-safe-prefix")
                            .put("rawApiKey", "sk-yzh-raw-api-key-must-not-leak")
                            .put("providerSecret", "provider-secret-must-not-leak")
                            .put("paymentSecret", "payment-secret-must-not-leak")
                            .put("requestBody", "request-body-must-not-leak")
                            .put("responseBody", "response-body-must-not-leak")
                            .put("base64", "QkFTRTY0X01VU1RfTk9UX0xFQUs=")
                            .put("temporaryFileUrl", "https://files.invalid/private?signature=secret"))
                    .block();

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("gateway_request_completed");
            Map<String, String> fields = event.getKeyValuePairs().stream()
                    .collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
            assertThat(fields).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "traceId", "trace-123",
                    "requestId", entry.requestId(),
                    "reservationId", entry.reservationId().toString(),
                    "userId", entry.userId().toString(),
                    "apiKeyPrefix", "sk-yzh-safe-prefix",
                    "model", entry.modelCode(),
                    "channelId", entry.channelId().toString(),
                    "httpStatus", "502",
                    "outcome", "FAILED"));

            String rendered = event.getFormattedMessage() + event.getKeyValuePairs();
            assertThat(rendered)
                    .doesNotContain("sk-yzh-raw-api-key-must-not-leak")
                    .doesNotContain("provider-secret-must-not-leak")
                    .doesNotContain("payment-secret-must-not-leak")
                    .doesNotContain("request-body-must-not-leak")
                    .doesNotContain("response-body-must-not-leak")
                    .doesNotContain("QkFTRTY0X01VU1RfTk9UX0xFQUs=")
                    .doesNotContain("https://files.invalid/private?signature=secret");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private ChatCompletionService.UsageEntry usageEntry() {
        String sensitiveSummary = "sk-yzh-raw-api-key-must-not-leak "
                + "provider-secret-must-not-leak payment-secret-must-not-leak "
                + "request-body-must-not-leak response-body-must-not-leak "
                + "QkFTRTY0X01VU1RfTk9UX0xFQUs= "
                + "https://files.invalid/private?signature=secret";
        return new ChatCompletionService.UsageEntry(
                "req-123", UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("10000000-0000-4000-8000-000000000002"),
                UUID.fromString("10000000-0000-4000-8000-000000000003"),
                UUID.fromString("10000000-0000-4000-8000-000000000004"),
                UUID.fromString("10000000-0000-4000-8000-000000000005"),
                UUID.fromString("10000000-0000-4000-8000-000000000006"),
                "text-pro", "qwen-plus",
                UUID.fromString("10000000-0000-4000-8000-000000000007"),
                UUID.fromString("10000000-0000-4000-8000-000000000008"),
                new ProviderAdapter.TokenUsage(10, 20, 30),
                QuotaService.UsageQuality.ACTUAL, false, null, 125, 0, 502,
                "FAILED", "provider_error", sensitiveSummary);
    }
}
