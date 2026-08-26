package com.servicehub.controller;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HelloControllerTest {

    private final HelloController controller = new HelloController();

    @Test
    void returnsHelloMessage() {
        assertThat(controller.hello()).isEqualTo("ServiceHub API está no ar! 🚀");
    }

    @Test
    void returnsApiStatus() {
        assertThat(controller.status()).isEqualTo("OK - v1.0");
    }
}
