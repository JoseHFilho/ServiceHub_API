package com.servicehub;

import com.servicehub.testing.PostgresIntegration;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@PostgresIntegration
class PostgresEnvironmentIT extends JwtTestSupport {
    @Autowired DataSource datasource;
    @Autowired PostgreSQLContainer postgres;
    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory entityManagerFactory;

    @Test
    void serviceConnectionUsesOnlyTheTemporaryPostgresWithFlywayAndValidation() throws Exception {
        try (var connection = datasource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(Integer.parseInt(System.getProperty("postgres.version", "15")));
            assertThat(metadata.getURL()).isEqualTo(postgres.getJdbcUrl());
            assertThat(metadata.getUserName()).isEqualTo(postgres.getUsername());
        }
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDatabaseName()).startsWith("servicehub_it_");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("9");
        assertThat(flyway.info().applied()).hasSize(9);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
    }
}
