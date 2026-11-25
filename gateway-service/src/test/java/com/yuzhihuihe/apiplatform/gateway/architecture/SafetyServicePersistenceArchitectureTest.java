package com.yuzhihuihe.apiplatform.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class SafetyServicePersistenceArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.yuzhihuihe.apiplatform.gateway");

    @Test
    void refactoredSafetyServicesKeepR2dbcBehindRepositories() {
        assertRepositoryBoundary("AccountEnforcementService", "AccountEnforcementRepository");
        assertRepositoryBoundary("ContentSafetyCheckService", "ContentSafetyCheckRepository");
        assertRepositoryBoundary(
                "ContentSafetyEventOutboxService", "ContentSafetyEventOutboxRepository");
    }

    private static void assertRepositoryBoundary(String serviceName, String repositoryName) {
        noClasses()
                .that()
                .haveSimpleName(serviceName)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        classes()
                .that()
                .haveSimpleName(serviceName)
                .should()
                .dependOnClassesThat()
                .haveSimpleName(repositoryName)
                .check(GATEWAY_CLASSES);
    }
}
