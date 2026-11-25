package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class EmailPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void emailVerificationServiceUsesItsRepository() {
        classes()
                .that().haveSimpleName("EmailVerificationService")
                .should().dependOnClassesThat()
                .haveSimpleName("EmailVerificationRepository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void emailVerificationServiceDoesNotDependOnJdbcOrSqlApis() {
        noClasses()
                .that().haveSimpleName("EmailVerificationService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(PLATFORM_CLASSES);
    }
}
