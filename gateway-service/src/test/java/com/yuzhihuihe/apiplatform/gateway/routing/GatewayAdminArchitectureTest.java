package com.yuzhihuihe.apiplatform.gateway.routing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class GatewayAdminArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.yuzhihuihe.apiplatform.gateway");

    @Test
    void adminControllerDoesNotAccessR2dbcOrRepositories() {
        noClasses()
                .that()
                .haveSimpleName("AdminGatewayController")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("AdminGatewayController")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void adminApiContractsDoNotDependOnApplicationOrPersistenceLayers() {
        noClasses()
                .that()
                .resideInAPackage("..routing.api..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Service")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..routing.api..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..routing.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void adminServiceKeepsReactivePersistenceBehindRepository() {
        noClasses()
                .that()
                .haveSimpleName("GatewayAdminService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void adminRepositoryDoesNotOwnEncryptionProbingOrTransactions() {
        noClasses()
                .that()
                .haveSimpleName("GatewayAdminRepository")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("CredentialCipher")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("GatewayAdminRepository")
                .should()
                .dependOnClassesThat()
                .haveSimpleName("ChannelConnectivityProbe")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("GatewayAdminRepository")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.transaction..")
                .check(GATEWAY_CLASSES);
    }
}
