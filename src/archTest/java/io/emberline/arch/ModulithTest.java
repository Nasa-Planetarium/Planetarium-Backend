package io.emberline.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import io.emberline.EmberlineApplication;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** B0.13 — module boundaries (only {@code <ctx>.api} is public) and the exact module set. io.emberline.shared is the shared kernel, not a module. */
class ModulithTest {

    static final ApplicationModules MODULES = ApplicationModules.of(EmberlineApplication.class,
            JavaClass.Predicates.resideInAnyPackage("io.emberline.shared..", "io.emberline.arch.."));

    @Test
    void verifiesModules() {
        MODULES.verify();
        Set<String> names = MODULES.stream().map(m -> m.getIdentifier().toString()).collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder("catalog", "harmonized", "observation", "provenance", "ingest",
                "external", "platform", "identity", "reporting", "audit", "operations", "hotspots");
    }
}
