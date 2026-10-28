package com.servicehub.security;

import java.sql.SQLException;
import java.util.List;

interface AuthAdmissionStore {
    record Quota(String key, int maximum) { }
    interface Permit extends AutoCloseable { @Override void close(); }
    void consume(List<Quota> quotas, int windowSeconds) throws SQLException;
    Permit acquire(int maximum) throws SQLException;
    default void cleanup(int windowSeconds) throws SQLException { }
}
