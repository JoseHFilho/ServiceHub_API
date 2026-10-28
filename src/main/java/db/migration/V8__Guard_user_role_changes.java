package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** PostgreSQL operator writes obey the same open-request policy as the internal service. */
public class V8__Guard_user_role_changes extends BaseJavaMigration {
    private static final String SQL = """
        CREATE FUNCTION __SCHEMA__.servicehub_guard_role_change() RETURNS trigger
        LANGUAGE plpgsql AS $body$
        BEGIN
            IF OLD.role IS DISTINCT FROM NEW.role THEN
                IF current_setting('transaction_isolation') <> 'read committed' THEN
                    RAISE EXCEPTION 'Mudança de papel requer isolamento READ COMMITTED.' USING ERRCODE='25001';
                END IF;
                IF EXISTS (
                    SELECT 1 FROM __SCHEMA__.service_requests r
                    WHERE r.client_id=OLD.id AND r.status IN ('PENDING','ACCEPTED','IN_PROGRESS')
                ) OR EXISTS (
                    SELECT 1 FROM __SCHEMA__.service_requests r
                    JOIN __SCHEMA__.services s ON s.id=r.service_id
                    WHERE s.provider_id=OLD.id
                      AND r.status IN ('PENDING','ACCEPTED','IN_PROGRESS')
                ) THEN
                    RAISE EXCEPTION 'Papel não pode ser alterado enquanto houver solicitações abertas.'
                        USING ERRCODE='23514', CONSTRAINT='chk_user_role_open_requests';
                END IF;
            END IF;
            RETURN NEW;
        END;
        $body$;
        CREATE TRIGGER trg_user_role_policy BEFORE UPDATE OF role ON __SCHEMA__.users
          FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.servicehub_guard_role_change();
        """;
    @Override public Integer getChecksum() { return SQL.hashCode(); }
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        if(!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) return;
        String schema="\""+connection.getSchema().replace("\"","\"\"")+"\"";
        try(var statement=connection.createStatement()){statement.execute(SQL.replace("__SCHEMA__",schema));}
    }
}
