package com.servicehub.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {
    // Spring starts the container before the datasource and closes it after JPA/Hikari.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() { return PostgresTestContainers.create("spring-integration"); }
}
