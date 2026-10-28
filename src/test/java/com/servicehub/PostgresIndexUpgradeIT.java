package com.servicehub;

import com.servicehub.testing.PostgresTestContainers;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers(disabledWithoutDocker = false)
class PostgresIndexUpgradeIT extends IndexUpgradeTests {
    @Container
    static final PostgreSQLContainer postgres = PostgresTestContainers.create("index-upgrade");

    @Override
    protected Connection migrationConnection(String schema) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
