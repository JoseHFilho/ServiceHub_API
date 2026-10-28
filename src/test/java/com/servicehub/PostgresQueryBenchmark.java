package com.servicehub;

import com.servicehub.testing.PostgresTestContainers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in measurement, excluded from the postgres regression tag. No timing assertions. */
@Tag("benchmark")
@Testcontainers(disabledWithoutDocker = false)
class PostgresQueryBenchmark {
    @Container
    static final PostgreSQLContainer postgres = PostgresTestContainers.create("query-benchmark")
            .withLabel("servicehub.test.stage", "7");
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void measureOnlySyntheticDataInANewContainer() throws Exception {
        String phase = System.getProperty("benchmark.phase", "before");
        assertThat(phase).isIn("before", "after");
        var report = new LinkedHashMap<String, Object>();
        report.put("stage", 7);
        report.put("phase", phase);
        report.put("database", postgres.getDockerImageName());
        report.put("isolatedContainer", true);
        report.put("originalDatabaseAccessed", false);
        report.put("samplesPerQuery", 5);
        report.put("cache", "VACUUM ANALYZE, one warm-up, then five samples; tmpfs, not cold-disk latency");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("5").cleanDisabled(true).load();
            flyway.migrate();
            assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
            try (var input = getClass().getResourceAsStream("/benchmark/postgres-data.sql")) {
                assertThat(input).isNotNull();
                execute(connection, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            String digestBeforeMigration = graphDigest(connection);
            if (phase.equals("after")) {
                long started = System.nanoTime();
                Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration").target("6").cleanDisabled(true).load().migrate();
                report.put("populatedUpgradeMs", (System.nanoTime() - started) / 1_000_000.0);
                assertThat(graphDigest(connection)).isEqualTo(digestBeforeMigration);
            }
            String version = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").cleanDisabled(true).load().info().current().getVersion().toString();
            assertThat(version).isEqualTo(phase.equals("before") ? "5" : "6");
            report.put("flywayVersion", version);
            report.put("populatedGraphDigest", digestBeforeMigration);
            report.put("rowsPreservedByMigration", true);
            for (String table : List.of("users", "services", "service_requests", "reviews")) {
                execute(connection, "VACUUM (ANALYZE) " + table);
            }
            report.put("serverVersion", scalar(connection, "SHOW server_version"));
            report.put("settings", rows(connection, "SELECT name,setting,unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','random_page_cost','effective_cache_size','max_parallel_workers_per_gather','plan_cache_mode') ORDER BY name"));
            report.put("distributions", rows(connection, "SELECT 'users' AS entity,role AS bucket,is_active::text AS active,count(*) AS rows FROM users GROUP BY role,is_active UNION ALL SELECT 'services',category,is_active::text,count(*) FROM services GROUP BY category,is_active UNION ALL SELECT 'requests',status,'n/a',count(*) FROM service_requests GROUP BY status UNION ALL SELECT 'reviews','all','n/a',count(*) FROM reviews ORDER BY entity,bucket,active"));
            report.put("participants", rows(connection, "SELECT u.id,u.role,u.is_active,(SELECT count(*) FROM services s WHERE s.provider_id=u.id) AS services,(SELECT count(*) FROM service_requests r WHERE r.client_id=u.id) AS as_client,(SELECT count(*) FROM service_requests r JOIN services s ON s.id=r.service_id WHERE s.provider_id=u.id) AS as_provider FROM users u WHERE u.id IN (1,3007,29999) ORDER BY u.id"));
            report.put("indexes", rows(connection, "SELECT tablename,indexname,indexdef,pg_relation_size((schemaname||'.'||indexname)::regclass) AS bytes FROM pg_indexes WHERE schemaname='public' AND tablename <> 'flyway_schema_history' ORDER BY tablename,indexname"));
            var measurements = new ArrayList<Object>();
            for (var query : workload(phase).entrySet()) measurements.add(measure(connection, query.getKey(), query.getValue()));
            execute(connection, "SET plan_cache_mode=force_generic_plan");
            execute(connection, "PREPARE benchmark_category(varchar,int,int) AS SELECT s.*,p.* FROM services s JOIN users p ON p.id=s.provider_id WHERE s.is_active AND p.is_active AND s.category=$1 ORDER BY s.id OFFSET $2 LIMIT $3");
            measurements.add(measure(connection, "catalog-category-generic-plan", "EXECUTE benchmark_category('CONSULTORIA_TI',0,20)"));
            String openSql = workload(phase).get("open-requests-actor-3007").replace("=3007", "=$1")
                    .replace("'PENDING'", "$2").replace("'ACCEPTED'", "$3").replace("'IN_PROGRESS'", "$4");
            execute(connection, "PREPARE benchmark_open(bigint,varchar,varchar,varchar) AS " + openSql);
            for (long actor : List.of(1L, 3007L, 29999L)) measurements.add(measure(connection,
                    "open-requests-generic-" + actor, "EXECUTE benchmark_open(" + actor + ",'PENDING','ACCEPTED','IN_PROGRESS')"));
            execute(connection, "SET plan_cache_mode=auto");
            report.put("queries", measurements);
            report.put("writes", writes(connection));
        }
        Path output = Path.of(System.getProperty("benchmark.output", "docs/evidencias"));
        Files.createDirectories(output);
        Path result = output.resolve("etapa7-postgres" + System.getProperty("postgres.version", "15") + "-" + phase + ".json");
        Files.writeString(result, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Benchmark evidence: " + result.toAbsolutePath());
    }

    private Map<String, String> workload(String phase) {
        var queries = new LinkedHashMap<String, String>();
        String catalog = "SELECT s.*,p.* FROM services s JOIN users p ON p.id=s.provider_id WHERE s.is_active AND p.is_active";
        queries.put("catalog-active-page", catalog + " ORDER BY s.id OFFSET 0 LIMIT 20");
        queries.put("catalog-category-rare-page", catalog + " AND s.category='CONSULTORIA_TI' ORDER BY s.id OFFSET 0 LIMIT 20");
        queries.put("catalog-category-rare-offset", catalog + " AND s.category='CONSULTORIA_TI' ORDER BY s.id OFFSET 200 LIMIT 20");
        queries.put("catalog-category-common-page", catalog + " AND s.category='LIMPEZA' ORDER BY s.id LIMIT 20");
        queries.put("catalog-category-count", "SELECT count(s.id) FROM services s JOIN users p ON p.id=s.provider_id WHERE s.is_active AND p.is_active AND s.category='CONSULTORIA_TI'");
        queries.put("catalog-provider-page", catalog + " AND s.provider_id=42 ORDER BY s.id LIMIT 20");
        queries.put("catalog-provider-hot-page", catalog + " AND s.provider_id=1 ORDER BY s.id LIMIT 20");
        String requests = "SELECT r.*,s.*,p.* FROM service_requests r JOIN services s ON s.id=r.service_id JOIN users p ON p.id=s.provider_id WHERE ";
        queries.put("requests-client-status", requests + "(r.client_id=3007 OR s.provider_id=3007) AND r.status='PENDING' ORDER BY r.id LIMIT 20");
        queries.put("requests-provider-status", requests + "(r.client_id=1 OR s.provider_id=1) AND r.status='PENDING' ORDER BY r.id LIMIT 20");
        queries.put("requests-admin-status", requests + "r.status='PENDING' ORDER BY r.id LIMIT 20");
        queries.put("requests-service-status", requests + "r.service_id=1234 AND r.status='COMPLETED' ORDER BY r.id LIMIT 20");
        queries.put("requests-authorized-count", "SELECT count(r.id) FROM service_requests r JOIN services s ON s.id=r.service_id WHERE (r.client_id=3007 OR s.provider_id=3007) AND r.status='PENDING'");
        String reviews = "SELECT v.*,r.*,s.* FROM reviews v JOIN service_requests r ON r.id=v.request_id JOIN services s ON s.id=r.service_id WHERE ";
        queries.put("reviews-service-authorized", reviews + "(v.reviewer_id=1 OR s.provider_id=1) AND r.service_id=1234 ORDER BY v.id LIMIT 20");
        queries.put("reviews-request", reviews + "v.request_id=22 ORDER BY v.id LIMIT 20");
        queries.put("user-active-pk", "SELECT * FROM users WHERE id=3007");
        queries.put("users-admin-active", "SELECT * FROM users WHERE is_active LIMIT 20");
        queries.put("users-admin-all-active", "SELECT * FROM users WHERE is_active");
        queries.put("requests-client-helper", "SELECT * FROM service_requests WHERE client_id=3007");
        queries.put("requests-provider-helper", "SELECT r.* FROM service_requests r JOIN services s ON s.id=r.service_id WHERE s.provider_id=42");
        queries.put("reviews-reviewer-helper", "SELECT * FROM reviews WHERE reviewer_id=3007");
        queries.put("providers-page", "SELECT id,full_name,avatar_url FROM users WHERE is_active AND role='PROVIDER' ORDER BY id LIMIT 20");
        queries.put("providers-count", "SELECT count(id) FROM users WHERE is_active AND role='PROVIDER'");
        queries.put("providers-batch", "SELECT id,full_name,avatar_url FROM users WHERE is_active AND role='PROVIDER' AND id IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20)");
        for (long actor : List.of(1L, 3007L, 29999L)) {
            String open = "r.status IN ('PENDING','ACCEPTED','IN_PROGRESS')";
            String sql = phase.equals("before")
                    ? "SELECT count(r.id)>0 FROM service_requests r JOIN services s ON s.id=r.service_id WHERE (r.client_id=" + actor + " OR s.provider_id=" + actor + ") AND " + open
                    : "SELECT count(u.id)>0 FROM users u WHERE u.id=" + actor + " AND (EXISTS(SELECT 1 FROM service_requests r WHERE r.client_id=u.id AND " + open + ") OR EXISTS(SELECT 1 FROM service_requests r JOIN services s ON s.id=r.service_id WHERE s.provider_id=u.id AND " + open + "))";
            queries.put("open-requests-actor-" + actor, sql);
        }
        return queries;
    }

    private Map<String, Object> measure(Connection connection, String name, String sql) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("sql", sql);
        result.put("resultDigest", resultDigest(connection, sql));
        result.put("resultOrderChecked", sql.contains("ORDER BY") || sql.startsWith("EXECUTE benchmark_category"));
        result.put("estimated", explain(connection, sql, false));
        explain(connection, sql, true); // warm-up excluded from samples
        var samples = new ArrayList<JsonNode>();
        for (int i = 0; i < 5; i++) samples.add(explain(connection, sql, true));
        result.put("samples", samples);
        result.put("medianExecutionMs", samples.stream().mapToDouble(p -> p.get(0).get("Execution Time").asDouble()).sorted().skip(2).findFirst().orElseThrow());
        return result;
    }

    private List<Object> writes(Connection connection) throws Exception {
        var results = new ArrayList<Object>();
        for (int sample = 0; sample < 5; sample++) {
            connection.setAutoCommit(false);
            try {
                String[] sqls = {
                    "INSERT INTO services(id,title,price,category,provider_id) SELECT g,'Synthetic write',100,'LIMPEZA',42 FROM generate_series(60001,61000) g",
                    "UPDATE services SET is_active=false WHERE id BETWEEN 60001 AND 61000",
                    "DELETE FROM services WHERE id BETWEEN 60001 AND 61000",
                    "INSERT INTO service_requests(id,service_id,client_id,status,total_price) SELECT g,1234,3007,'PENDING',100 FROM generate_series(600001,601000) g",
                    "UPDATE service_requests SET status='ACCEPTED' WHERE id BETWEEN 600001 AND 601000",
                    "DELETE FROM service_requests WHERE id BETWEEN 600001 AND 601000"
                };
                for (String sql : sqls) results.add(Map.of("sample", sample + 1, "sql", sql, "plan", explain(connection, sql, true)));
            } finally { connection.rollback(); connection.setAutoCommit(true); }
        }
        return results;
    }

    private JsonNode explain(Connection connection, String sql, boolean analyze) throws Exception {
        String options = analyze ? "ANALYZE, BUFFERS, WAL, FORMAT JSON" : "FORMAT JSON";
        return json.readTree(scalar(connection, "EXPLAIN (" + options + ") " + sql));
    }

    private String graphDigest(Connection connection) throws Exception {
        return resultDigest(connection, "SELECT 'users',count(*),sum(id)::text,md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM users t UNION ALL SELECT 'services',count(*),sum(id)::text,md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM services t UNION ALL SELECT 'requests',count(*),sum(id)::text,md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM service_requests t UNION ALL SELECT 'reviews',count(*),sum(id)::text,md5(string_agg(md5(row_to_json(t)::text),'' ORDER BY id)) FROM reviews t");
    }

    private String resultDigest(Connection connection, String sql) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        // Canonical sorting keeps unordered batch results comparable when the chosen plan changes.
        var rowDigests = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                var values = new ArrayList<String>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) values.add(rows.getString(column));
                rowDigests.add(json.writeValueAsString(values));
            }
        }
        var canonical = rowDigests.stream();
        if (!sql.contains("ORDER BY") && !sql.startsWith("EXECUTE benchmark_category")) canonical = canonical.sorted();
        canonical.forEach(row -> digest.update((row + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }

    private List<Map<String, Object>> rows(Connection connection, String sql) throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) row.put(rows.getMetaData().getColumnLabel(column), rows.getString(column));
                result.add(row);
            }
        }
        return result;
    }

    private String scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
    private void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
