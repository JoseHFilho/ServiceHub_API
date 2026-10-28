package com.servicehub.security;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/** Database-wide fixed windows and transaction-scoped concurrency slots, shared by API replicas. */
final class PostgresAuthAdmissionStore implements AuthAdmissionStore {
    private static final int LOCK_NAMESPACE = 1397249346;
    private final DataSource datasource;
    PostgresAuthAdmissionStore(DataSource datasource) { this.datasource = datasource; }
    @Override public void consume(List<Quota> quotas, int seconds) throws SQLException {
        try (var connection = datasource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                long now;
                try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT floor(extract(epoch FROM clock_timestamp()))::bigint")) {
                    row.next(); now = row.getLong(1);
                }
                long start = now / seconds * seconds;
                try (var statement = connection.prepareStatement("""
                        INSERT INTO auth_rate_buckets(bucket_key,window_start,attempts) VALUES (?,?,1)
                        ON CONFLICT (bucket_key) DO UPDATE SET window_start=greatest(auth_rate_buckets.window_start,excluded.window_start),
                          attempts=CASE WHEN auth_rate_buckets.window_start<excluded.window_start THEN 1 ELSE auth_rate_buckets.attempts+1 END
                        WHERE auth_rate_buckets.window_start<excluded.window_start OR auth_rate_buckets.attempts<?
                        RETURNING attempts
                        """)) {
                    for (var quota : quotas) {
                        statement.setString(1, quota.key()); statement.setLong(2, start); statement.setInt(3, quota.maximum());
                        try (var row = statement.executeQuery()) {
                            if (!row.next()) throw new AuthRateExceeded(start + seconds - now, "frequency");
                        }
                    }
                }
                connection.commit();
            } finally { connection.rollback(); }
        }
    }
    @Override public Permit acquire(int maximum) throws SQLException {
        Connection connection = datasource.getConnection();
        boolean acquired = false;
        try {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?,?)")) {
                for (int slot = 0; slot < maximum; slot++) {
                    statement.setInt(1, LOCK_NAMESPACE); statement.setInt(2, slot);
                    try (var row = statement.executeQuery()) {
                        row.next();
                        if (row.getBoolean(1)) {
                            acquired = true;
                            return new Permit() {
                                private boolean closed;
                                @Override public synchronized void close() {
                                    if (closed) return;
                                    closed = true;
                                    try { connection.rollback(); }
                                    catch (SQLException failure) {
                                        LoggerFactory.getLogger(PostgresAuthAdmissionStore.class).warn("Falha ao liberar admissão; conexão será encerrada.");
                                        try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
                                    } finally { try { connection.close(); } catch (SQLException ignored) { } }
                                }
                            };
                        }
                    }
                }
            }
            throw new AuthRateExceeded(1, "concurrency");
        } finally {
            if (!acquired) { try { connection.rollback(); } finally { connection.close(); } }
        }
    }
    @Override public void cleanup(int seconds) throws SQLException {
        try (var connection = datasource.getConnection(); var statement = connection.prepareStatement("""
                DELETE FROM auth_rate_buckets WHERE bucket_key IN
                  (SELECT bucket_key FROM auth_rate_buckets
                   WHERE window_start < floor(extract(epoch FROM clock_timestamp()))-?
                   ORDER BY bucket_key LIMIT 1000 FOR UPDATE SKIP LOCKED)
                """)) {
            statement.setInt(1, seconds * 2); statement.executeUpdate();
        }
    }
}
