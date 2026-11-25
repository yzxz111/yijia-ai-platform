package com.yuzhihuihe.apiplatform.platform.identity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.springframework.jdbc.core.JdbcTemplate;

class IdentityArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void controllersDoNotAccessJdbcOrRepositories() {
        noClasses()
                .that().resideInAPackage("..identity..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..identity..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void identityApiContractsDoNotDependOnServicesOrRepositories() {
        noClasses()
                .that().resideInAPackage("..identity.api..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Service")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..identity.api..")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void terminalSessionServiceKeepsPersistenceBehindItsRepository() {
        noClasses()
                .that().haveSimpleName("TerminalSessionService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void identityServiceUsesDedicatedPersistenceRepositories() {
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityProfileRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("TerminalSessionRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityPasswordRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityAuthenticationRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityContactRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityRegistrationRepository")
                .check(PLATFORM_CLASSES);
        classes()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityConsentService")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().haveSimpleName("IdentityService")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityConsentRepository")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().resideInAPackage("..identity..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat()
                .haveSimpleName("IdentityService")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void identityServiceDoesNotOwnJdbcState() {
        fields()
                .that().areDeclaredInClassesThat()
                .haveSimpleName("IdentityService")
                .should().notHaveRawType(JdbcTemplate.class)
                .check(PLATFORM_CLASSES);
    }
}
