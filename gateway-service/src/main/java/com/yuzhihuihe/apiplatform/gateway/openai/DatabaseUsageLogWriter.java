package com.yuzhihuihe.apiplatform.gateway.openai;

import com.yuzhihuihe.apiplatform.gateway.usage.UsageStageWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
final class DatabaseUsageLogWriter implements ChatCompletionService.UsageLogWriter {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(DatabaseUsageLogWriter.class);

    private final UsageStageWriter usageStageWriter;

    @org.springframework.beans.factory.annotation.Autowired
    DatabaseUsageLogWriter(UsageStageWriter usageStageWriter) {
        this.usageStageWriter = usageStageWriter;
    }

    DatabaseUsageLogWriter(DatabaseClient databaseClient) {
        this(new UsageStageWriter(databaseClient));
    }

    @Override
    public Mono<Void> write(ChatCompletionService.UsageEntry entry) {
        return usageStageWriter
                .write(entry)
                .then(
                        Mono.deferContextual(
                                context -> {
                                    String traceId =
                                            context.getOrDefault("traceId", entry.requestId());
                                    String apiKeyPrefix =
                                            context.getOrDefault("apiKeyPrefix", "unavailable");
                                    LOGGER.atInfo()
                                            .addKeyValue("traceId", traceId)
                                            .addKeyValue("requestId", entry.requestId())
                                            .addKeyValue("reservationId", entry.reservationId())
                                            .addKeyValue("userId", entry.userId())
                                            .addKeyValue("apiKeyPrefix", apiKeyPrefix)
                                            .addKeyValue("model", entry.modelCode())
                                            .addKeyValue("channelId", entry.channelId())
                                            .addKeyValue("httpStatus", entry.httpStatus())
                                            .addKeyValue("outcome", entry.outcome())
                                            .log("gateway_request_completed");
                                    return Mono.empty();
                                }));
    }
}
