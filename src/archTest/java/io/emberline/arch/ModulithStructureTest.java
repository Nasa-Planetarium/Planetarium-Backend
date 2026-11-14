package io.emberline.arch;

import io.emberline.EmberlineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithStructureTest {

    @Test
    void modulesVerify() {
        ApplicationModules.of(EmberlineApplication.class).verify();
    }
}
