package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ReportingPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void operatingReportControllerUsesAServiceInsteadOfJdbcOrRepositories() {
        noClasses().that().haveSimpleName("OperatingReportController")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
        noClasses().that().haveSimpleName("OperatingReportController")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(PLATFORM_CLASSES);
        classes().that().haveSimpleName("OperatingReportController")
                .should().dependOnClassesThat().haveSimpleName("OperatingAlertQueryService")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void operatingAlertQueryServiceKeepsJdbcBehindItsRepository() {
        noClasses().that().haveSimpleName("OperatingAlertQueryService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
        classes().that().haveSimpleName("OperatingAlertQueryService")
                .should().dependOnClassesThat().haveSimpleName("OperatingAlertQueryRepository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void reportingServicesKeepJdbcAndSqlTypesBehindRepositories() {
        noClasses().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("..reporting..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }
}
