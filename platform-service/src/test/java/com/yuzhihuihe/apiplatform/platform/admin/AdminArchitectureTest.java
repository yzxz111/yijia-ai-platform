package com.yuzhihuihe.apiplatform.platform.admin;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class AdminArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void adminControllersDoNotAccessJdbcOrRepositories() {
        noClasses()
                .that().resideInAPackage("..admin..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..admin..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void adminApiContractsDoNotDependOnApplicationLayers() {
        noClasses()
                .that().resideInAPackage("..admin.api..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Service")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..admin.api..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..admin.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void adminManagementServiceKeepsPersistenceBehindRepository() {
        noClasses()
                .that().haveSimpleName("AdminManagementService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
    }
}
