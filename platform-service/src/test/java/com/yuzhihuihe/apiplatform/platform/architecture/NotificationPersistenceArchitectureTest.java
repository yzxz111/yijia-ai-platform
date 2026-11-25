package com.yuzhihuihe.apiplatform.platform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class NotificationPersistenceArchitectureTest {

    private static final JavaClasses PLATFORM_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.platform");

    @Test
    void selectedNotificationServicesDependOnTheirRepositories() {
        requireRepository("CommercialNotificationService", "CommercialNotificationRepository");
        requireRepository("NotificationQueryService", "NotificationQueryRepository");
        requireRepository("NotificationPreferenceService", "NotificationPreferenceRepository");
        requireRepository("WalletUsageNotificationService", "WalletUsageNotificationRepository");
        requireRepository("NotificationEventService", "NotificationEventRepository");
        requireRepository("NotificationBroadcastService", "NotificationBroadcastRepository");
    }

    @Test
    void selectedNotificationServicesDoNotDependOnJdbcOrSqlApis() {
        noClasses()
                .that().haveSimpleName("CommercialNotificationService")
                .or().haveSimpleName("NotificationQueryService")
                .or().haveSimpleName("NotificationPreferenceService")
                .or().haveSimpleName("WalletUsageNotificationService")
                .or().haveSimpleName("NotificationEventService")
                .or().haveSimpleName("NotificationBroadcastService")
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
