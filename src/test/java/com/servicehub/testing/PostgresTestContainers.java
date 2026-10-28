package com.servicehub.testing;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Creates only disposable, unshared databases; never connects to application containers. */
public final class PostgresTestContainers {
    private PostgresTestContainers() { }
    public static PostgreSQLContainer create(String purpose) {
        String version = System.getProperty("postgres.version", "15");
        if (!version.equals("15") && !version.equals("16")) throw new IllegalArgumentException("postgres.version deve ser 15 ou 16.");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new PostgreSQLContainer("postgres:" + version)
                .withDatabaseName("servicehub_it_" + suffix.substring(0, 12))
                .withUsername("it_" + suffix.substring(12, 24))
                .withPassword(UUID.randomUUID().toString().replace("-", ""))
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                .withLabel("servicehub.test.stage", "6")
                .withLabel("servicehub.test.purpose", purpose)
                .withReuse(false)
                .withStartupTimeout(Duration.ofSeconds(60));
    }
}
