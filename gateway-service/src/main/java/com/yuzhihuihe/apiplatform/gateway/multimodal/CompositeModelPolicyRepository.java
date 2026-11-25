package com.yuzhihuihe.apiplatform.gateway.multimodal;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class CompositeModelPolicyRepository implements CompositeModelPolicyService.PolicyStore {

    private final DatabaseClient database;

    CompositeModelPolicyRepository(DatabaseClient database) {
        this.database = database;
    }

    @Override
    public Mono<CompositeModelPolicyService.PolicyRow> load(String publicModel) {
        return database.sql("""
                        SELECT model.routing_strategy, model.routing_policy::text
                        FROM gateway.model_alias alias
                        JOIN gateway.model model ON model.id = alias.model_id AND model.enabled
                        WHERE alias.alias = :model_alias
                        """)
                .bind("model_alias", publicModel)
                .map((row, metadata) -> new CompositeModelPolicyService.PolicyRow(
                        row.get("routing_strategy", String.class),
                        row.get("routing_policy", String.class)))
                .one();
    }

    @Override
    public Mono<Set<String>> enabledAliases(Set<String> aliases) {
        return Flux.fromIterable(aliases)
                .concatMap(alias -> database.sql("""
                                SELECT EXISTS(
                                    SELECT 1
                                    FROM gateway.model_alias alias
                                    JOIN gateway.model model
                                      ON model.id = alias.model_id AND model.enabled
                                    WHERE alias.alias = :model_alias
                                ) AS enabled
                                """)
                        .bind("model_alias", alias)
                        .map((row, metadata) -> Boolean.TRUE.equals(
                                row.get("enabled", Boolean.class)))
                        .one()
                        .filter(Boolean::booleanValue)
                        .map(ignored -> alias))
                .collect(LinkedHashSet<String>::new, Set::add)
                .map(Set::copyOf);
    }
}
