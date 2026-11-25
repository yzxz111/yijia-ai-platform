package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class GrantPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void grantServicesDependOnTheirRepositories() {
        classes()
                .that().haveSimpleName("GrantService")
                .should().dependOnClassesThat()
                .haveSimpleName("GrantRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("PackageSnapshotService")
                .should().dependOnClassesThat()
                .haveSimpleName("PackageSnapshotRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("PackageFulfillmentService")
                .should().dependOnClassesThat()
                .haveSimpleName("PackageFulfillmentRepository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void grantServicesDoNotDependOnJdbcOrSqlApis() {
        noClasses()
                .that().resideInAPackage("..platform.grant")
                .and().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }
}
