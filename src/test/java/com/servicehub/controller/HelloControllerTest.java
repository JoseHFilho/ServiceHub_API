package com.servicehub.controller;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class HelloControllerTest {

    private final HelloController controller = new HelloController();

    @Test
    void returnsHelloMessage() {
        assertThat(controller.hello().message()).isEqualTo("ServiceHub API está no ar! 🚀");
    }

    @Test
    void returnsApiStatus() {
        assertThat(controller.status().status()).isEqualTo("OK");
        assertThat(controller.status().version()).isEqualTo("1.0.0");
    }
}
