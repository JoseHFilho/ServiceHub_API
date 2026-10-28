package com.servicehub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servicehub.model.ServiceCategory;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Runs on H2 by default, and on an isolated PostgreSQL via migration.test.* properties. */
@org.junit.jupiter.api.Tag("integration")
class SchemaUpgradeTests {
    @Test
    void upgradesV4WithoutLosingProfilesHashesRelationshipsOrPriceSnapshots() throws Exception {
        try (Database db = new Database()) {
            db.seedLegacyGraph();
            db.migrate();
            assertThat(db.value("SELECT COUNT(*) FROM users")).isEqualTo("2");
            assertThat(db.value("SELECT email FROM users WHERE id = 1")).isEqualTo("provider@example.com");
            assertThat(db.value("SELECT role FROM users WHERE id = 1")).isEqualTo("PROVIDER");
            assertThat(db.value("SELECT role FROM users WHERE id = 2")).isEqualTo("CLIENT");
            assertThat(db.value("SELECT COUNT(*) FROM users WHERE role = 'ADMIN'")).isEqualTo("0");
            assertThat(db.value("SELECT password_hash FROM users WHERE id = 1")).isEqualTo("pbkdf2$legacy-preserved");
            assertThat(db.value("SELECT phone FROM users WHERE id = 1")).isEqualTo("62999999999");
            assertThat(db.value("SELECT bio FROM users WHERE id = 1")).isEqualTo("Perfil existente");
            assertThat(db.value("SELECT avatar_url FROM users WHERE id = 1")).isEqualTo("https://example.com/avatar.png");
            assertThat(db.value("SELECT COUNT(*) FROM users WHERE id = 2 AND NOT is_active")).isEqualTo("1");
            assertThat(db.value("SELECT category FROM services WHERE id = 1")).isEqualTo("LIMPEZA");
            assertThat(db.value("SELECT COUNT(*) FROM services WHERE updated_at = created_at")).isEqualTo("1");
            assertThat(db.value("SELECT total_price FROM service_requests WHERE id = 1")).isEqualTo("150.00");
            assertThat(db.value("""
                    SELECT COUNT(*) FROM reviews r JOIN service_requests sr ON sr.id = r.request_id
                    JOIN services s ON s.id = sr.service_id JOIN users u ON u.id = s.provider_id
                    WHERE r.reviewer_id = 2 AND r.rating = 5
                    """)).isEqualTo("1");
            assertThat(db.flyway(null).validateWithResult().validationSuccessful).isTrue();
            assertThat(db.flyway(null).migrate().migrationsExecuted).isZero();
        }
    }

    @Test
    void convertsAllLegacyCategoriesAndAcceptsEveryReferenceCategory() throws Exception {
        try (Database db = new Database()) {
            db.seedUsers();
            Map<String, String> mappings = Map.of("CLEANING", "LIMPEZA", "ELECTRICAL", "ELETRICA",
                    "GARDENING", "JARDINAGEM", "PAINTING", "PINTURA", "PLUMBING", "ENCANAMENTO", "OTHER", "OUTROS");
            for (String legacy : mappings.keySet()) {
                db.execute("INSERT INTO services(title, price, category, provider_id) VALUES ('" + legacy + "', 1, '" + legacy + "', 1)");
            }
            db.migrate();
            for (var mapping : mappings.entrySet()) {
                assertThat(db.value("SELECT category FROM services WHERE title = '" + mapping.getKey() + "'"))
                        .isEqualTo(mapping.getValue());
            }
            for (ServiceCategory category : ServiceCategory.values()) {
                db.execute("INSERT INTO services(title, price, category, provider_id) VALUES ('Novo', 1, '" + category.name() + "', 1)");
            }
            assertThat(db.value("SELECT COUNT(*) FROM services")).isEqualTo("27");
        }
    }

    @Test
    void refusesCollidingEmailsBeforeChangingAnyUserData() throws Exception {
        try (Database db = new Database()) {
            db.seedUsers();
            db.execute("UPDATE users SET email = 'provider@example.com' WHERE id = 2");
            assertThatThrownBy(db::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("e-mails conflitantes");
            assertThat(db.value("SELECT email FROM users WHERE id = 1")).isEqualTo(" PROVIDER@EXAMPLE.COM ");
            assertThat(db.value("SELECT COUNT(*) FROM users")).isEqualTo("2");
            db.assertRoleColumnAbsent();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void refusesLegacyNonPositivePricesWithoutChangingData(String price) throws Exception {
        try (Database db = new Database()) {
            db.seedLegacyGraph();
            db.execute("UPDATE services SET price = " + price);
            assertThatThrownBy(db::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("preço não positivo");
            assertThat(db.value("SELECT category FROM services")).isEqualTo("CLEANING");
            db.assertRoleColumnAbsent();
        }
    }

    @Test
    void refusesLegacyNonPositiveRequestSnapshotsWithoutChangingData() throws Exception {
        try (Database db = new Database()) {
            db.seedLegacyGraph();
            db.execute("UPDATE service_requests SET total_price = 0");
            assertThatThrownBy(db::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("solicitações com preço não positivo");
            assertThat(db.value("SELECT total_price FROM service_requests")).isEqualTo("0.00");
            db.assertRoleColumnAbsent();
        }
    }

    @Test
    void refusesUnknownLegacyCategoriesWithoutInventingAMapping() throws Exception {
        try (Database db = new Database()) {
            db.seedLegacyGraph();
            db.execute("UPDATE services SET category = 'UNRECOGNIZED'");
            assertThatThrownBy(db::migrate).isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("categorias desconhecidas");
            assertThat(db.value("SELECT category FROM services")).isEqualTo("UNRECOGNIZED");
            db.assertRoleColumnAbsent();
        }
    }

    @Test
    void enforcesDomainConstraintsAndRestrictsPhysicalDeletion() throws Exception {
        try (Database db = new Database()) {
            db.seedLegacyGraph();
            db.migrate();
            for (String sql : new String[]{
                    "UPDATE users SET role = 'ROOT' WHERE id = 1",
                    "UPDATE users SET email = 'UPPER@example.com' WHERE id = 1",
                    "UPDATE users SET email = 'provider@example.com' WHERE id = 2",
                    "UPDATE services SET price = 0 WHERE id = 1",
                    "UPDATE services SET price = -1 WHERE id = 1",
                    "UPDATE services SET category = 'CLEANING' WHERE id = 1",
                    "UPDATE services SET provider_id = 9999 WHERE id = 1",
                    "UPDATE service_requests SET total_price = 0 WHERE id = 1",
                    "UPDATE service_requests SET status = 'UNKNOWN' WHERE id = 1",
                    "UPDATE service_requests SET service_id = 9999 WHERE id = 1",
                    "UPDATE service_requests SET client_id = 9999 WHERE id = 1",
                    "UPDATE reviews SET rating = 0 WHERE id = 1",
                    "UPDATE reviews SET rating = 6 WHERE id = 1",
                    "UPDATE reviews SET reviewer_id = 9999 WHERE id = 1",
                    "UPDATE reviews SET request_id = 9999 WHERE id = 1",
                    "INSERT INTO reviews(request_id, reviewer_id, rating) VALUES (1, 2, 5)",
                    "DELETE FROM users WHERE id = 1",
                    "DELETE FROM users WHERE id = 2",
                    "DELETE FROM services WHERE id = 1",
                    "DELETE FROM service_requests WHERE id = 1"}) {
                assertThatThrownBy(() -> db.execute(sql)).as(sql).isInstanceOf(SQLException.class);
            }
            // Even a provider with no requests must not cascade-delete their service.
            db.execute("INSERT INTO users(full_name, email, password_hash) VALUES ('Outro', 'other@example.com', 'hash')");
            db.execute("INSERT INTO services(title, price, category, provider_id) VALUES ('Outro', 10, 'OUTROS', 3)");
            assertThatThrownBy(() -> db.execute("DELETE FROM users WHERE id = 3")).isInstanceOf(SQLException.class);
            assertThat(db.value("SELECT COUNT(*) FROM services WHERE provider_id = 3")).isEqualTo("1");
            assertThat(db.value("SELECT COUNT(*) FROM reviews")).isEqualTo("1");
            try (ResultSet keys = db.connection.getMetaData().getImportedKeys(
                    db.connection.getCatalog(), db.schema, "services")) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getInt("DELETE_RULE")).isIn(
                        DatabaseMetaData.importedKeyRestrict, DatabaseMetaData.importedKeyNoAction);
            }
        }
    }

    @Test
    void retainsUsefulIndexesWithoutDuplicatingUniqueEmailOrReviewRequestIndexes() throws Exception {
        try (Database db = new Database()) {
            db.migrate();
            String[] expected = {"idx_services_provider", "idx_services_category", "idx_requests_service",
                    "idx_requests_client", "idx_requests_status", "idx_reviews_reviewer"};
            boolean postgres = db.connection.getMetaData().getDatabaseProductName().equals("PostgreSQL");
            if (postgres) {
                for (String index : expected) {
                    assertThat(db.value("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = '" + db.schema
                            + "' AND indexname = '" + index + "'")).isEqualTo("1");
                }
                assertThat(db.value("SELECT indexdef FROM pg_indexes WHERE schemaname = '" + db.schema
                        + "' AND indexname = 'idx_services_category'")).contains("WHERE is_active");
                assertThat(db.value("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = '" + db.schema
                        + "' AND tablename = 'users' AND indexdef LIKE '%(email)%'")).isEqualTo("1");
                assertThat(db.value("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = '" + db.schema
                        + "' AND tablename = 'reviews' AND indexdef LIKE '%(request_id)%'")).isEqualTo("1");
            } else {
                for (String index : expected) {
                    assertThat(db.value("SELECT COUNT(*) FROM information_schema.indexes WHERE index_schema = '"
                            + db.schema + "' AND index_name = '" + index + "'")).isEqualTo("1");
                }
                assertThat(db.value("SELECT COUNT(*) FROM information_schema.indexes WHERE index_schema = '"
                        + db.schema + "' AND index_name = 'idx_users_email'")).isEqualTo("0");
            }
        }
    }

    /** Extension point for the PostgreSQL Testcontainers subclass; no global property mutation. */
    protected Connection migrationConnection(String schema) throws SQLException {
        String url = System.getProperty("migration.test.url", "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        return DriverManager.getConnection(url, System.getProperty("migration.test.username", "sa"),
                System.getenv().getOrDefault("MIGRATION_TEST_PASSWORD", ""));
    }

    private class Database implements AutoCloseable {
        private final String schema = "upgrade_test_" + UUID.randomUUID().toString().replace("-", "");
        private final Connection connection;

        Database() throws SQLException {
            connection = migrationConnection(schema);
            try {
                flyway("4").migrate();
                connection.setSchema(schema);
            } catch (SQLException | RuntimeException failure) {
                try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }

        Flyway flyway(String target) {
            // Keep H2's DDL session alive: its CHECK IN comparator retains that session.
            var configuration = Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                    .locations("classpath:db/migration").schemas(schema).defaultSchema(schema).cleanDisabled(true);
            if (target != null) configuration.target(target);
            return configuration.load();
        }

        void migrate() { flyway(null).migrate(); }

        void seedUsers() throws SQLException {
            execute("""
                    INSERT INTO users(full_name, email, password_hash, phone, bio, avatar_url)
                    VALUES ('Prestador', ' PROVIDER@EXAMPLE.COM ', 'pbkdf2$legacy-preserved',
                    '62999999999', 'Perfil existente', 'https://example.com/avatar.png')
                    """);
            execute("INSERT INTO users(full_name, email, password_hash, is_active) VALUES ('Cliente', 'client@example.com', 'hash', FALSE)");
        }

        void seedLegacyGraph() throws SQLException {
            seedUsers();
            execute("INSERT INTO services(title, price, category, provider_id) VALUES ('Limpeza', 200, 'CLEANING', 1)");
            execute("INSERT INTO service_requests(service_id, client_id, total_price, status) VALUES (1, 2, 150, 'COMPLETED')");
            execute("INSERT INTO reviews(request_id, reviewer_id, rating) VALUES (1, 2, 5)");
        }

        void execute(String sql) throws SQLException {
            try (var statement = connection.createStatement()) { statement.execute(sql); }
        }

        String value(String sql) throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                if (!rows.next()) throw new SQLException("Consulta de teste sem resultado");
                return rows.getString(1);
            }
        }

        void assertRoleColumnAbsent() throws SQLException {
            assertThat(value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
                    + "' AND table_name = 'users' AND column_name = 'role'")).isEqualTo("0");
        }

        @Override
        public void close() throws SQLException { connection.close(); }
    }
}
