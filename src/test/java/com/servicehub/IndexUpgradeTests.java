package com.servicehub;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class IndexUpgradeTests {
    @Test
    void upgradesPopulatedV5WithoutChangingRowsConstraintsOrDuplicatingTheCategoryIndex() throws Exception {
        String schema = "index_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = migrationConnection(schema)) {
            var config = Flyway.configure().dataSource(new SingleConnectionDataSource(connection, true))
                    .locations("classpath:db/migration").schemas(schema).defaultSchema(schema).cleanDisabled(true);
            config.target("5").load().migrate();
            connection.setSchema(schema);
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO users(id,full_name,email,password_hash,role,is_active) VALUES (10,'Provider','provider@index.invalid','legacy-unchanged','PROVIDER',TRUE),(20,'Client','client@index.invalid','legacy-inactive','CLIENT',FALSE)");
                statement.execute("INSERT INTO services(id,title,price,category,provider_id,is_active) VALUES (11,'Historical',200,'LIMPEZA',10,FALSE),(12,'Active',300,'CONSULTORIA_TI',10,TRUE)");
                statement.execute("INSERT INTO service_requests(id,service_id,client_id,status,total_price) VALUES (21,11,20,'COMPLETED',150)");
                statement.execute("INSERT INTO reviews(id,request_id,reviewer_id,rating) VALUES (31,21,20,5)");
            }
            var before = graph(connection);
            assertThat(indexColumns(connection, schema)).containsExactly("category");
            // This regression verifies the historical V5 -> V6 upgrade independently of later migrations.
            var latest = config.target("6").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("6");
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(graph(connection)).isEqualTo(before);
            assertThat(indexColumns(connection, schema)).containsExactly("category", "id");
            assertThat(latest.migrate().migrationsExecuted).isZero();
            if (connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                try (var statement = connection.createStatement(); var indexes = statement.executeQuery("SELECT indexdef FROM pg_indexes WHERE schemaname='" + schema + "' AND indexname='idx_services_category'")) {
                    assertThat(indexes.next()).isTrue();
                    assertThat(indexes.getString(1)).contains("(category, id) WHERE is_active");
                    assertThat(indexes.next()).isFalse();
                }
            }
            // Restrictive FKs and review uniqueness remain enforced after the populated upgrade.
            assertThat(connection.getMetaData().getImportedKeys(connection.getCatalog(), schema, "reviews")).satisfies(keys -> {
                try (keys) { int count = 0; while (keys.next()) { assertThat(keys.getShort("DELETE_RULE")).isEqualTo((short) java.sql.DatabaseMetaData.importedKeyRestrict); count++; } assertThat(count).isEqualTo(2); }
            });
        }
    }

    protected Connection migrationConnection(String schema) throws SQLException {
        String url = System.getProperty("migration.test.url", "jdbc:h2:mem:" + schema + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        return DriverManager.getConnection(url, System.getProperty("migration.test.username", "sa"), System.getenv().getOrDefault("MIGRATION_TEST_PASSWORD", ""));
    }
    private List<String> indexColumns(Connection connection, String schema) throws Exception {
        var columns = new java.util.TreeMap<Integer, String>();
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), schema, "services", false, false)) {
            while (indexes.next()) if ("idx_services_category".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) columns.put(indexes.getInt("ORDINAL_POSITION"), indexes.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(columns.values());
    }
    private List<String> graph(Connection connection) throws Exception {
        var snapshot = new ArrayList<String>();
        for (String table : List.of("users", "services", "service_requests", "reviews")) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                while (rows.next()) { var row = new ArrayList<String>(); for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) row.add(rows.getString(i)); snapshot.add(table + row); }
            }
        }
        return snapshot;
    }
}
