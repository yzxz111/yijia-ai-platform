package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class WalletPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void walletServicesKeepJdbcAndSqlTypesBehindRepositories() {
        noClasses().that().haveSimpleNameEndingWith("Service")
                .and().resideInAPackage("..wallet..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void walletRepositoriesDoNotDependOnServices() {
        noClasses().that().haveSimpleNameEndingWith("Repository")
                .and().resideInAPackage("..wallet..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Service")
                .check(PLATFORM_CLASSES);
    }
}
