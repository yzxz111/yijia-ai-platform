package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FinancialPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void paymentLifecycleKeepsPersistenceBehindItsRepository() {
        classes()
                .that().haveSimpleName("PaymentLifecycleService")
                .should().dependOnClassesThat()
                .haveSimpleName("PaymentLifecycleRepository")
                .check(PLATFORM_CLASSES);
        fields()
                .that().areDeclaredInClassesThat()
                .haveSimpleName("PaymentLifecycleService")
                .should().notHaveRawType(JdbcTemplate.class)
                .check(PLATFORM_CLASSES);
    }

    @Test
    void paymentAvailabilityKeepsPersistenceBehindItsRepository() {
        classes()
                .that().haveSimpleName("PaymentAvailabilityService")
                .should().dependOnClassesThat()
                .haveSimpleName("PaymentAvailabilityRepository")
                .check(PLATFORM_CLASSES);
        noClasses()
                .that().haveSimpleName("PaymentAvailabilityService")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void financialServicesDoNotDependOnDatabaseApis() {
        noClasses()
                .that().resideInAnyPackage(
                        "com.yuzhihuihe.apiplatform.platform.payment..",
                        "com.yuzhihuihe.apiplatform.platform.reconciliation..",
                        "com.yuzhihuihe.apiplatform.platform.refund..")
                .and().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(PLATFORM_CLASSES);
    }

    @Test
    void reconciliationKeepsPersistenceBehindItsRepository() {
        classes()
                .that().haveSimpleName("ReconciliationService")
                .should().dependOnClassesThat()
                .haveSimpleName("ReconciliationRepository")
                .check(PLATFORM_CLASSES);
        fields()
                .that().areDeclaredInClassesThat()
                .haveSimpleName("ReconciliationService")
                .should().notHaveRawType(JdbcTemplate.class)
                .check(PLATFORM_CLASSES);
    }
}
