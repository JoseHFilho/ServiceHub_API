package com.servicehub;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ServicehubApiApplicationTests extends com.servicehub.JwtTestSupport {

    @Test
    void contextLoads() {
    }
}
