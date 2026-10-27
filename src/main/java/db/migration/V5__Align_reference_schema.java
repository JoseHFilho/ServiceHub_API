package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Additive upgrade from V4. Do not edit after deployment; create another migration. */
public class V5__Align_reference_schema extends BaseJavaMigration {
    private static final String CATEGORIES = "'LIMPEZA', 'DIARISTA', 'ENCANAMENTO', 'ELETRICA', "
            + "'JARDINAGEM', 'PINTURA', 'MARCENARIA', 'PEDREIRO', 'DEDETIZACAO', 'AR_CONDICIONADO', "
            + "'CHAVEIRO', 'MONTAGEM_MOVEIS', 'MUDANCA', 'SUPORTE_TECNICO', 'MANUTENCAO_COMPUTADORES', "
            + "'DESENVOLVIMENTO_SOFTWARE', 'REDES_E_INFRAESTRUTURA', 'SEGURANCA_DIGITAL', "
            + "'RECUPERACAO_DE_DADOS', 'CONSULTORIA_TI', 'OUTROS'";

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String product = connection.getMetaData().getDatabaseProductName();
        boolean postgres = "PostgreSQL".equals(product);
        if (!postgres && !"H2".equals(product)) {
            throw new SQLException("V5 suporta PostgreSQL e H2 para testes.");
        }

        // PostgreSQL holds these locks until Flyway commits, including the preflight.
        if (postgres) {
            execute(connection, "LOCK TABLE users, services, service_requests, reviews IN ACCESS EXCLUSIVE MODE");
        }
        requireEmpty(connection, """
                SELECT LOWER(TRIM(email)) FROM users
                GROUP BY LOWER(TRIM(email)) HAVING COUNT(*) > 1
                """, "Há e-mails conflitantes após normalização. Resolva os conflitos antes de aplicar V5.");
        requireEmpty(connection, "SELECT id FROM services WHERE price <= 0",
                "Há serviços com preço não positivo. Revise os registros antes de aplicar V5.");
        requireEmpty(connection, "SELECT id FROM service_requests WHERE total_price <= 0",
                "Há solicitações com preço não positivo. Revise os registros antes de aplicar V5.");
        requireEmpty(connection, "SELECT id FROM services WHERE category NOT IN (" + CATEGORIES
                + ", 'CLEANING', 'ELECTRICAL', 'GARDENING', 'PAINTING', 'PLUMBING', 'OTHER')",
                "Há categorias desconhecidas. Defina o mapeamento antes de aplicar V5.");

        execute(connection, "UPDATE users SET email = LOWER(TRIM(email)) WHERE email <> LOWER(TRIM(email))");
        execute(connection, "ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'CLIENT'");
        execute(connection, """
                UPDATE users SET role = 'PROVIDER'
                WHERE EXISTS (SELECT 1 FROM services WHERE services.provider_id = users.id)
                """);
        execute(connection, "ALTER TABLE users ADD CONSTRAINT chk_users_role CHECK (role IN ('ADMIN', 'PROVIDER', 'CLIENT'))");
        execute(connection, "ALTER TABLE users ADD CONSTRAINT chk_users_email_low CHECK (email = LOWER(email))");
        execute(connection, """
                UPDATE services SET category = CASE category
                    WHEN 'CLEANING' THEN 'LIMPEZA' WHEN 'ELECTRICAL' THEN 'ELETRICA'
                    WHEN 'GARDENING' THEN 'JARDINAGEM' WHEN 'PAINTING' THEN 'PINTURA'
                    WHEN 'PLUMBING' THEN 'ENCANAMENTO' WHEN 'OTHER' THEN 'OUTROS'
                    ELSE category END
                """);
        execute(connection, "ALTER TABLE services ADD COLUMN updated_at TIMESTAMP");
        execute(connection, "UPDATE services SET updated_at = created_at");
        execute(connection, "ALTER TABLE services ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP");
        execute(connection, "ALTER TABLE services ALTER COLUMN updated_at SET NOT NULL");
        execute(connection, "ALTER TABLE services ADD CONSTRAINT chk_services_price CHECK (price > 0)");
        execute(connection, "ALTER TABLE services ADD CONSTRAINT chk_services_category CHECK (category IN (" + CATEGORIES + "))");
        execute(connection, "ALTER TABLE service_requests ADD CONSTRAINT chk_requests_price CHECK (total_price > 0)");

        restrictForeignKey(connection, "services", "provider_id", "users", "fk_services_provider");
        restrictForeignKey(connection, "service_requests", "service_id", "services", "fk_requests_service");
        restrictForeignKey(connection, "service_requests", "client_id", "users", "fk_requests_client");
        restrictForeignKey(connection, "reviews", "request_id", "service_requests", "fk_reviews_request");
        restrictForeignKey(connection, "reviews", "reviewer_id", "users", "fk_reviews_reviewer");

        // V1 already has a UNIQUE(email) index. The additional plain index is redundant.
        execute(connection, "DROP INDEX idx_users_email");
        if (postgres) {
            execute(connection, "DROP INDEX idx_services_category");
            execute(connection, "CREATE INDEX idx_services_category ON services(category) WHERE is_active");
        }
        // H2 retains the full category index because it does not support partial indexes.
        execute(connection, "CREATE INDEX idx_requests_service ON service_requests(service_id)");
        execute(connection, "CREATE INDEX idx_requests_client ON service_requests(client_id)");
        execute(connection, "CREATE INDEX idx_requests_status ON service_requests(status)");
        execute(connection, "CREATE INDEX idx_reviews_reviewer ON reviews(reviewer_id)");
    }

    private void restrictForeignKey(Connection connection, String table, String column,
            String referencedTable, String constraint) throws SQLException {
        List<String> oldConstraints = new ArrayList<>();
        try (ResultSet keys = connection.getMetaData().getImportedKeys(
                connection.getCatalog(), connection.getSchema(), table)) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))) {
                    oldConstraints.add(keys.getString("FK_NAME"));
                }
            }
        }
        if (oldConstraints.size() != 1) {
            throw new SQLException("Esperada uma FK para " + table + "." + column);
        }
        String quotedName = "\"" + oldConstraints.getFirst().replace("\"", "\"\"") + "\"";
        execute(connection, "ALTER TABLE " + table + " DROP CONSTRAINT " + quotedName);
        execute(connection, "ALTER TABLE " + table + " ADD CONSTRAINT " + constraint
                + " FOREIGN KEY (" + column + ") REFERENCES " + referencedTable + "(id) ON DELETE RESTRICT");
    }

    private void requireEmpty(Connection connection, String sql, String message) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (rows.next()) {
                // Do not include user data, emails or hashes in migration logs.
                throw new SQLException(message);
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
