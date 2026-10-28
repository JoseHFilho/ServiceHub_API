package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V9__Version_user_credentials extends BaseJavaMigration {
    private static final String COLUMNS="""
        ALTER TABLE users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
        ALTER TABLE users ADD CONSTRAINT chk_users_credential_version CHECK (credential_version >= 0);
        """;
    private static final String TRIGGER="""
        CREATE FUNCTION __SCHEMA__.servicehub_version_credentials() RETURNS trigger LANGUAGE plpgsql AS $body$
        BEGIN
            IF NEW.password_hash IS DISTINCT FROM OLD.password_hash THEN
                NEW.credential_version := OLD.credential_version + 1;
            ELSIF NEW.credential_version < OLD.credential_version THEN
                RAISE EXCEPTION 'Versão de credencial não pode diminuir.'
                    USING ERRCODE='23514', CONSTRAINT='chk_users_credential_version_monotonic';
            END IF;
            RETURN NEW;
        END;
        $body$;
        CREATE TRIGGER trg_user_credential_version BEFORE UPDATE OF password_hash,credential_version ON __SCHEMA__.users
          FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.servicehub_version_credentials();
        """;
    @Override public Integer getChecksum(){return 31*COLUMNS.hashCode()+TRIGGER.hashCode();}
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        try(var statement=connection.createStatement()) {
            statement.execute(COLUMNS);
            if("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                String schema="\""+connection.getSchema().replace("\"","\"\"")+"\"";
                statement.execute(TRIGGER.replace("__SCHEMA__",schema));
            }
        }
    }
}
