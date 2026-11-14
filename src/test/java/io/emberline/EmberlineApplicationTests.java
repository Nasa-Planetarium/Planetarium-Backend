package io.emberline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmberlineApplicationTests {

    @Test
    void mainClassIsPresent() {
        assertThat(EmberlineApplication.class).isNotNull();
    }
}
