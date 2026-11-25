package com.yuzhihuihe.apiplatform.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ApplicationPersistenceArchitectureTest {

    private static final JavaClasses GATEWAY_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.yuzhihuihe.apiplatform.gateway");
    private static final Pattern SQL_LITERAL = Pattern.compile(
            "(?is)\"{1,3}\\s*(SELECT\\b|INSERT\\s+INTO\\b|UPDATE\\s+[A-Za-z]"
                    + "|DELETE\\s+FROM\\b|WITH\\s+[A-Za-z])");

    @Test
    void servicesAndControllersKeepPersistenceApisBehindRepositories() {
        noClasses()
                .that()
                .haveSimpleNameEndingWith("Service")
                .or()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.r2dbc..",
                        "io.r2dbc..",
                        "org.springframework.jdbc..",
                        "java.sql..",
                        "javax.sql..",
                        "jakarta.persistence..")
                .check(GATEWAY_CLASSES);
    }

    @Test
    void servicesAndControllersDoNotContainSqlLiterals() throws IOException {
        Path sourceRoot = sourceRoot();
        List<String> violations;
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(this::isServiceOrController)
                    .filter(this::containsSqlLiteral)
                    .map(sourceRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
        assertThat(violations).as("SQL literals in Services or Controllers").isEmpty();
    }

    private Path sourceRoot() {
        return Stream.of(
                        Path.of("src", "main", "java"),
                        Path.of("gateway-service", "src", "main", "java"))
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("gateway source root not found"));
    }

    private boolean isServiceOrController(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith("Service.java") || name.endsWith("Controller.java");
    }

    private boolean containsSqlLiteral(Path path) {
        try {
            return SQL_LITERAL.matcher(Files.readString(path)).find();
        } catch (IOException error) {
            throw new IllegalStateException("failed to inspect " + path, error);
        }
    }
}
