package com.servicehub.config;

import java.sql.SQLException;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Grants domain DML after migrations, without granting ownership or Flyway writes. */
@Component
public class RuntimeDatabaseGrants implements Callback {
    private final boolean enabled;
    private final String runtimeUser;

    public RuntimeDatabaseGrants(@Value("${servicehub.database.manage-runtime-grants:false}") boolean enabled,
            @Value("${spring.datasource.username}") String runtimeUser) {
        this.enabled = enabled;
        this.runtimeUser = runtimeUser;
    }

    @Override public boolean supports(Event event, Context context) {
        return enabled && event == Event.AFTER_MIGRATE;
    }
    @Override public boolean canHandleInTransaction(Event event, Context context) { return true; }
    @Override public String getCallbackName() { return "domain-runtime-grants"; }

    @Override public void handle(Event event, Context context) {
        try {
            if (!"PostgreSQL".equals(context.getConnection().getMetaData().getDatabaseProductName())) return;
            if (!runtimeUser.matches("[a-z][a-z0-9_]{0,62}")) throw new FlywayException("Nome de conta de execução inválido.");
            String role = "\"" + runtimeUser + "\"";
            try (var statement = context.getConnection().createStatement()) {
                statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.users, public.services, public.service_requests, public.reviews TO " + role);
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON public.auth_rate_buckets TO " + role);
                statement.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + role);
                statement.execute("REVOKE ALL ON public.flyway_schema_history FROM " + role);
            }
        } catch (SQLException exception) {
            throw new FlywayException("Não foi possível conceder os privilégios de execução.", exception);
        }
    }
}
