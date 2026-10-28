package com.servicehub.config;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fail closed if a PostgreSQL production runtime accidentally uses an administrator. */
@Component
public class ProductionDatabaseSafety implements ApplicationRunner {
    private final DataSource datasource;
    private final Environment environment;
    public ProductionDatabaseSafety(DataSource datasource, Environment environment) {
        this.datasource = datasource; this.environment = environment;
    }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        try (var connection = datasource.getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
            String runtimeUser = connection.getMetaData().getUserName();
            if (runtimeUser.equals(environment.getProperty("spring.flyway.user"))) fail();
            try (var statement = connection.createStatement(); var roles = statement.executeQuery("""
                    SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls
                           OR has_schema_privilege(current_user, 'public', 'CREATE')
                           OR has_table_privilege(current_user, 'public.flyway_schema_history', 'INSERT,UPDATE,DELETE')
                    FROM pg_roles WHERE rolname = current_user
                    """)) {
                if (!roles.next() || roles.getBoolean(1)) fail();
            }
        }
    }
    private static void fail() { throw new IllegalStateException("A conta de execução PostgreSQL deve ser restrita e distinta da conta de migrations."); }
}
