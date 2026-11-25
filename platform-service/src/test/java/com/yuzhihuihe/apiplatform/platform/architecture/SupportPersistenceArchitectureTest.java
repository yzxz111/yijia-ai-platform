package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class SupportPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void supportServicesKeepJdbcAndSqlTypesBehindRepositories() {
        noClasses().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("..support..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void supportRepositoriesDoNotDependOnServices() {
        noClasses().that().haveSimpleNameEndingWith("Repository")
                .and().resideInAPackage("..support..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .check(PLATFORM_CLASSES);
    }
}
