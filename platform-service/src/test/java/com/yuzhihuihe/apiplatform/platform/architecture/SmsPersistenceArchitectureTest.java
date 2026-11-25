package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class SmsPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void smsServicesKeepJdbcAndSqlTypesBehindRepositories() {
        noClasses().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("..sms..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void smsRepositoriesDoNotDependOnServices() {
        noClasses().that().haveSimpleNameEndingWith("Repository")
                .and().resideInAPackage("..sms..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .check(PLATFORM_CLASSES);
    }
}
