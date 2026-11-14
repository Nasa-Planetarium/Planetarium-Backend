package io.emberline.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** B0.14–B0.16 — layer rules of the user's backend standard (controller returns only, pure domain, record DTOs). */
class ArchitectureTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("io.emberline");

    @Test
    void controllersOnlyReturn() {
        classes().that().resideInAPackage("..adapter.in.web..").and().areAnnotatedWith(RestController.class)
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "..application.port.in..", "..adapter.in.web.dto..", "..adapter.in.web", "io.emberline.shared..",
                        "java..", "org.springframework.web.bind.annotation..", "org.springframework.http..",
                        "org.springframework.validation.annotation..", "jakarta.validation..", "org.springdoc.core.annotations..")
                .check(MAIN);
        noClasses().that().resideInAPackage("..adapter.in.web..").and().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().areAssignableTo(ResponseEntity.class)
                .check(MAIN);
    }

    @Test
    void domainIsPure() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.fasterxml..",
                        "tools.jackson..", "java.sql..", "jakarta.servlet..")
                .allowEmptyShould(true)
                .check(MAIN);
    }

    @Test
    void dtosAreRecords() {
        classes().that().resideInAnyPackage("..adapter.in.web.dto..", "..application.port.in..")
                .and().areNotInterfaces().and().areNotEnums().and().areNotAnnotations()
                .should(beRecords())
                .allowEmptyShould(true)
                .check(MAIN);
    }

    private static ArchCondition<JavaClass> beRecords() {
        return new ArchCondition<>("be records") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean record = item.isRecord();
                events.add(new SimpleConditionEvent(item, record, item.getName() + (record ? " is" : " is not") + " a record"));
            }
        };
    }
}
