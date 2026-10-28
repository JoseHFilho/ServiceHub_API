package com.servicehub;

import com.servicehub.testing.PostgresTestContainers;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles({"test", "prod"})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Testcontainers(disabledWithoutDocker = false)
@Tag("postgres")
class PostgresRestrictedRuntimeIT extends JwtTestSupport {
    private static final String RUNTIME = "restricted_api", MIGRATOR = "restricted_migrator";
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATOR_PASSWORD = UUID.randomUUID().toString();
    @Container static final PostgreSQLContainer postgres = PostgresTestContainers.create("least-privilege")
            .withEnv("DB_RUNTIME_USERNAME", RUNTIME).withEnv("DB_RUNTIME_PASSWORD", RUNTIME_PASSWORD)
            .withEnv("DB_MIGRATION_USERNAME", MIGRATOR).withEnv("DB_MIGRATION_PASSWORD", MIGRATOR_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/10-provision.sh", 0755),
                    "/docker-entrypoint-initdb.d/10-provision.sh");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", () -> RUNTIME);
        properties.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.flyway.url", postgres::getJdbcUrl);
        properties.add("spring.flyway.user", () -> MIGRATOR);
        properties.add("spring.flyway.password", () -> MIGRATOR_PASSWORD);
        properties.add("servicehub.database.manage-runtime-grants", () -> true);
    }
    @Autowired DataSource datasource;
    @Autowired Flyway flyway;
    @Autowired MockMvc mvc;
    @Autowired com.servicehub.security.JwtProperties jwtProperties;

    @Test void migratesAsSeparateOwnerAndRunsWithRestrictedIdentity() throws Exception {
        try (var connection = datasource.getConnection(); var statement = connection.createStatement()) {
            assertThat(connection.getMetaData().getUserName()).isEqualTo(RUNTIME);
            try (var row = statement.executeQuery("SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertThat(row.next()).isTrue();
                for (int column = 1; column <= 5; column++) assertThat(row.getBoolean(column)).isFalse();
            }
        }
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
                var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT bool_and(installed_by='" + MIGRATOR + "') FROM flyway_schema_history")) {
            assertThat(row.next()).isTrue(); assertThat(row.getBoolean(1)).isTrue();
        }
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied().length).isGreaterThanOrEqualTo(6);
    }

    @Test void deniesAdministrativeDdlAndFlywayHistoryWrites() throws Exception {
        for (String sql : new String[]{"CREATE ROLE forbidden_role", "CREATE DATABASE forbidden_database",
                "CREATE TABLE public.forbidden_table(id int)", "DROP TABLE public.users",
                "UPDATE public.flyway_schema_history SET checksum=0"}) {
            try (var connection = datasource.getConnection(); var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
                        .satisfies(exception -> assertThat(((SQLException) exception).getSQLState()).isEqualTo("42501"));
            }
        }
    }

    @Test void allowsDomainDmlInsideARolledBackTransaction() throws Exception {
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var row = statement.executeQuery("INSERT INTO users(full_name,email,password_hash) VALUES ('DML Fixture','dml@restricted.invalid','fixture-only') RETURNING id");
                assertThat(row.next()).isTrue(); long id = row.getLong(1); row.close();
                assertThat(statement.executeUpdate("UPDATE users SET full_name='Updated Fixture' WHERE id=" + id)).isEqualTo(1);
                assertThat(statement.executeUpdate("DELETE FROM users WHERE id=" + id)).isEqualTo(1);
            } finally { connection.rollback(); }
        }
    }

    @Test void registersAndAuthenticatesThroughTheApiWithRuntimePrivileges() throws Exception {
        String email = UUID.randomUUID() + "@restricted.invalid";
        String password = UUID.randomUUID().toString();
        mvc.perform(post("/api/users").contentType("application/json").content("{\"fullName\":\"Restricted Fixture\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CLIENT"));
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    private com.servicehub.security.AuthAbuseLimiter limiter(com.servicehub.security.AuthGuardProperties properties) throws Exception {
        return new com.servicehub.security.AuthAbuseLimiter(properties, jwtProperties, datasource,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }
    @Test void sharesAccountLimitsBetweenIndependentInstancesAndDoesNotStoreRawIdentifiers() throws Exception {
        var properties = new com.servicehub.security.AuthGuardProperties(); properties.setLoginAccountLimit(1);
        var first = limiter(properties); var second = limiter(properties);
        String email = UUID.randomUUID() + "@shared.invalid";
        try (var permit = first.admit(com.servicehub.security.AuthAbuseLimiter.Operation.LOGIN, "192.0.2.100", email)) { }
        assertThatThrownBy(() -> second.admit(com.servicehub.security.AuthAbuseLimiter.Operation.LOGIN,
                "192.0.2.101", email.toUpperCase(java.util.Locale.ROOT)))
                .hasMessage("Limite temporário de autenticação excedido.");
        try (var connection = datasource.getConnection(); var statement = connection.createStatement();
                var row = statement.executeQuery("SELECT count(*) FROM auth_rate_buckets WHERE bucket_key !~ '^[0-9a-f]{64}$'")) {
            row.next(); assertThat(row.getInt(1)).isZero();
        }
    }
    @Test void sharesConcurrencySlotsAndReleasesTheTransactionLease() throws Exception {
        var properties = new com.servicehub.security.AuthGuardProperties(); properties.setMaxConcurrent(1);
        var first = limiter(properties); var second = limiter(properties);
        String suffix = UUID.randomUUID().toString();
        try (var permit = first.admit(com.servicehub.security.AuthAbuseLimiter.Operation.LOGIN, "origin-"+suffix, suffix+"@first.invalid")) {
            assertThatThrownBy(() -> second.admit(com.servicehub.security.AuthAbuseLimiter.Operation.LOGIN,
                    "other-"+suffix, suffix+"@second.invalid")).hasMessage("Limite temporário de autenticação excedido.");
        }
        try (var permit = second.admit(com.servicehub.security.AuthAbuseLimiter.Operation.LOGIN,
                "other-"+suffix, suffix+"@second.invalid")) { }
    }
    @Test void failsClosedOnStorePermissionFailureAndRecoversAfterRestoringTheGrant() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), MIGRATOR, MIGRATOR_PASSWORD);
                var statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON auth_rate_buckets FROM " + RUNTIME);
            try {
                mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"email\":\"unavailable@store.invalid\",\"password\":\"InvalidCandidate\"}"))
                        .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"));
            } finally { statement.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON auth_rate_buckets TO " + RUNTIME); }
        }
        mvc.perform(post("/api/auth/login").contentType("application/json")
                .content("{\"email\":\"unavailable@store.invalid\",\"password\":\"InvalidCandidate\"}"))
                .andExpect(status().isUnauthorized());
    }
}
