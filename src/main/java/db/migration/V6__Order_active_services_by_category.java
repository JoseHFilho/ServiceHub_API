package db.migration;

import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Replace the category index instead of maintaining a redundant second prefix. */
public class V6__Order_active_services_by_category extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        String database = connection.getMetaData().getDatabaseProductName();
        if (!database.equals("PostgreSQL") && !database.equals("H2")) {
            throw new SQLException("V6 suporta PostgreSQL e H2 para testes.");
        }
        // Transactional CREATE INDEX blocks writes while building. Plan a deployment window.
        // H2 keeps its full index because partial indexes are unsupported there (as in V5).
        try (var statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_services_category");
            statement.execute("CREATE INDEX idx_services_category ON services(category, id)"
                    + (database.equals("PostgreSQL") ? " WHERE is_active" : ""));
        }
    }
}
