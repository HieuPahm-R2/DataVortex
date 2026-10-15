package io.datavortex.adinsight.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
    private static final ArchRule DOMAIN =
            classes()
                    .that()
                    .resideInAPackage("..domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "..domain..")
                    .because(
                            "DDD domain is plain Java; move framework/persistence concerns to adapters (docs/architecture.md)");
    private static final ArchRule APPLICATION =
            classes()
                    .that()
                    .resideInAPackage("..application..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "..domain..", "..application..")
                    .because(
                            "application depends on domain and ports, with wiring in infrastructure (docs/architecture.md)");
    private static final JavaClasses PRODUCTION =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("io.datavortex.adinsight");

    @Test
    void domainAndApplicationDependenciesPointInward() {
        assertThat(PRODUCTION.size()).isGreaterThan(30);
        DOMAIN.check(PRODUCTION);
        APPLICATION.check(PRODUCTION);
    }

    @Test
    void httpAdaptersDoNotBypassUseCasesForDatabaseAccess() {
        noClasses()
                .that()
                .resideInAPackage("..interfaces..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..infrastructure..",
                        "jakarta.persistence..",
                        "org.springframework.jdbc..",
                        "org.springframework.data..")
                .because("HTTP adapters use application interfaces (docs/architecture.md)")
                .check(PRODUCTION);
    }

    @Test
    void boundedContextsHaveNoDependencyCycles() {
        slices().matching("io.datavortex.adinsight.(*)..")
                .should()
                .beFreeOfCycles()
                .check(PRODUCTION);
    }

    @Test
    void rulesAcceptPlainJavaAndRejectFrameworkDependenciesInIsolatedFixtures() {
        var importer = new ClassFileImporter();
        var allowed =
                importer.importClasses(
                        io.datavortex.adinsight.architecture.fixtures.domain.PlainValue.class);
        DOMAIN.check(allowed);
        var badDomain =
                DOMAIN.evaluate(
                        importer.importClasses(
                                io.datavortex.adinsight.architecture.fixtures.domain.PersistenceLeak
                                        .class));
        assertThat(badDomain.hasViolation()).isTrue();
        assertThat(badDomain.getFailureReport().toString()).contains("jakarta.persistence.Entity");
        var badApplication =
                APPLICATION.evaluate(
                        importer.importClasses(
                                io.datavortex.adinsight.architecture.fixtures.application
                                        .DatabaseLeak.class));
        assertThat(badApplication.hasViolation()).isTrue();
        assertThat(badApplication.getFailureReport().toString())
                .contains("org.springframework.jdbc.core.JdbcTemplate");
    }
}
