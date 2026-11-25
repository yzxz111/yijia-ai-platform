package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class PackageCatalogPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void selectedPackageCatalogServicesDependOnTheirRepositories() {
        requireRepository("FreePackageClaimService", "FreePackageClaimRepository");
        requireRepository("PackageVersionService", "PackageVersionRepository");
    }

    @Test
    void selectedPackageCatalogServicesDoNotDependOnJdbcOrSqlApis() {
        noClasses()
                .that().haveSimpleName("FreePackageClaimService")
                .or().haveSimpleName("PackageVersionService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }

    private void requireRepository(String service, String repository) {
        classes()
                .that().haveSimpleName(service)
                .should().dependOnClassesThat()
                .haveSimpleName(repository)
                .check(PLATFORM_CLASSES);
    }
}
