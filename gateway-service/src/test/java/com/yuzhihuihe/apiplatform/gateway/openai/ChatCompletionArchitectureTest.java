package com.yuzhihuihe.apiplatform.gateway.openai;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ChatCompletionArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.yuzhihuihe.apiplatform.gateway");

    @Test
    void chatOrchestrationKeepsReactivePersistenceBehindItsPorts() {
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("UsageStageWriter")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void chatOrchestrationDoesNotOwnCatalogCredentialDecryption() {
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("CredentialCipher")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void requestValidationHasADedicatedInfrastructureFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionRequestValidator")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionRequestValidator")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.limit..",
                        "..gateway.quota..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void responseProtocolHasADedicatedOrchestrationFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionResponseProtocol")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionResponseProtocol")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "reactor..",
                        "..gateway.limit..",
                        "..gateway.quota..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionResponseProtocol")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void usageEstimationHasADedicatedOrchestrationFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionUsageEstimator")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionUsageEstimator")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "reactor..",
                        "..gateway.limit..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionUsageEstimator")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("QuotaService")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionUsageEstimator")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void providerExecutionHasADedicatedAccountingFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionProviderExecutor")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionProviderExecutor")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.limit..",
                        "..gateway.quota..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionProviderExecutor")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void usageAccountingHasADedicatedPersistenceFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionUsageAccounting")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionUsageAccounting")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.limit..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionUsageAccounting")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void publicAdmissionHasADedicatedProviderAndPersistenceFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatPublicAdmissionLifecycle")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatPublicAdmissionLifecycle")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.provider..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatPublicAdmissionLifecycle")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void streamSettlementHasADedicatedExecutionAndPersistenceFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatStreamSettlement")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatStreamSettlement")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatStreamSettlement")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionProviderExecutor")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatStreamSettlement")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void completionSettlementHasADedicatedExecutionAndPersistenceFreeBoundary() {
        classes()
                .that()
                .haveSimpleName("ChatCompletionService")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionSettlement")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionSettlement")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "..gateway.routing..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionSettlement")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionProviderExecutor")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("ChatCompletionSettlement")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChatCompletionService")
                .check(GATEWAY_CLASSES);
    }
}
