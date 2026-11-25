package com.yuzhihuihe.apiplatform.gateway.skill;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Same four dependency rules {@code GatewayAdminArchitectureTest} enforces for the routing package,
 * applied to the skill management boundary (docs/java-architecture-guidelines-zh-CN.md).
 */
class SkillAdminArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES =
            new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.yuzhihuihe.apiplatform.gateway");

    @Test
    void adminControllerDoesNotAccessR2dbcOrRepositories() {
        noClasses()
                .that()
                .haveSimpleName("SkillAdminController")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillAdminController")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillDownloadsController")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillDownloadsController")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void skillApiContractsDoNotDependOnApplicationOrPersistenceLayers() {
        noClasses()
                .that()
                .resideInAPackage("..skill.api..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Service")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..skill.api..")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .resideInAPackage("..skill.api..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void skillServicesKeepReactivePersistenceBehindRepositories() {
        noClasses()
                .that()
                .haveSimpleName("SkillAdminService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillDownloadService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillCatalogService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
        noClasses()
                .that()
                .haveSimpleName("SkillPackageIngestService")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.r2dbc..", "io.r2dbc..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void skillRepositoriesDoNotOwnTransactions() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Repository")
                .and()
                .resideInAPackage("..gateway.skill..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("org.springframework.transaction..")
                .check(GATEWAY_CLASSES);
    }
}

