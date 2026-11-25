package com.yuzhihuihe.apiplatform.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ControllerPersistenceArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.gateway");

    @Test
    void refactoredControllersUseApplicationServicesInsteadOfReactiveDatabaseClients() {
        assertNoPersistenceDependency("UsageEconomicsBackfillController");
        assertNoPersistenceDependency("UsageEconomicsOutboxHealthController");
        assertNoPersistenceDependency("ProviderPriceController");
        assertNoPersistenceDependency("ProviderPriceSnapshotController");
    }

    @Test
    void refactoredControllersRetainExplicitApplicationServiceBoundaries() {
        classes().that().haveSimpleName("UsageEconomicsBackfillController")
                .should().dependOnClassesThat().haveSimpleName("UsageEconomicsBackfillService")
                .check(GATEWAY_CLASSES);
        classes().that().haveSimpleName("UsageEconomicsOutboxHealthController")
                .should().dependOnClassesThat().haveSimpleName("UsageEconomicsOutboxHealthService")
                .check(GATEWAY_CLASSES);
        classes()
                .that().haveSimpleName("ProviderPriceController")
                .should().dependOnClassesThat().haveSimpleName("ProviderPriceQueryService")
                .check(GATEWAY_CLASSES);
        classes()
                .that().haveSimpleName("ProviderPriceSnapshotController")
                .should().dependOnClassesThat().haveSimpleName("ProviderPriceQueryService")
                .check(GATEWAY_CLASSES);
    }

    private static void assertNoPersistenceDependency(String controllerName) {
        noClasses().that().haveSimpleName(controllerName)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses().that().haveSimpleName(controllerName)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
    }
}
