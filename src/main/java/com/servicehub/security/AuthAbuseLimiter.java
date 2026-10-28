package com.servicehub.security;

import com.servicehub.validation.InputNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AuthAbuseLimiter {
    public enum Operation { LOGIN, REGISTRATION }
    private final AuthGuardProperties properties;
    private final AuthAdmissionStore store;
    private final SecretKeySpec key;
    private final MeterRegistry metrics;
    private final AtomicLong lastWarning = new AtomicLong();
    public AuthAbuseLimiter(AuthGuardProperties properties, JwtProperties jwt, DataSource datasource, MeterRegistry metrics) throws SQLException {
        this(properties, jwt, datasource, metrics, Clock.systemUTC());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AuthAbuseLimiter(AuthGuardProperties properties, JwtProperties jwt, DataSource datasource, MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Qualifier("admissionClock") Clock clock) throws SQLException {
        this.properties = properties; this.metrics = metrics;
        key = new SecretKeySpec(jwt.secretBytes(), "HmacSHA256");
        try (var connection = datasource.getConnection()) {
            store = switch (connection.getMetaData().getDatabaseProductName()) {
                case "PostgreSQL" -> new PostgresAuthAdmissionStore(datasource);
                case "H2" -> new LocalAuthAdmissionStore(clock);
                default -> throw new IllegalStateException("Backend de admissão não suportado.");
            };
        }
    }
    public AutoCloseable admit(Operation operation, String origin, String email) throws SQLException {
        if (!properties.isEnabled()) return () -> { };
        var quotas = new ArrayList<AuthAdmissionStore.Quota>();
        boolean login = operation == Operation.LOGIN;
        // Fixed acquisition order avoids opposing row-lock orders between instances.
        quotas.add(new AuthAdmissionStore.Quota(digest(operation + ":global"), login ? properties.getLoginGlobalLimit() : properties.getRegistrationGlobalLimit()));
        quotas.add(new AuthAdmissionStore.Quota(digest(operation + ":origin:" + origin), login ? properties.getLoginIpLimit() : properties.getRegistrationIpLimit()));
        String normalizedEmail = email == null ? null : InputNormalizer.email(email);
        if (normalizedEmail != null && !normalizedEmail.isBlank() && normalizedEmail.length() <= 255) {
            quotas.add(new AuthAdmissionStore.Quota(digest(operation + ":account:" + normalizedEmail), login ? properties.getLoginAccountLimit() : properties.getRegistrationAccountLimit()));
        }
        try {
            store.consume(quotas, properties.getWindowSeconds());
            return store.acquire(properties.getMaxConcurrent());
        } catch (AuthRateExceeded exception) {
            rejected(operation, exception.reason); throw exception;
        } catch (SQLException exception) {
            rejected(operation, "storage"); throw exception; // Never fall back to per-process limits in PostgreSQL.
        }
    }
    @Scheduled(fixedDelayString = "${servicehub.auth-guard.cleanup-ms:60000}", initialDelayString = "${servicehub.auth-guard.cleanup-ms:60000}")
    public void cleanup() {
        if (!properties.isEnabled()) return;
        try { store.cleanup(properties.getWindowSeconds()); }
        catch (SQLException exception) { rejected(Operation.LOGIN, "storage"); }
    }
    private String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(("servicehub-admission-v1:" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exception) { throw new IllegalStateException("Falha no controle de autenticação."); }
    }
    private void rejected(Operation operation, String reason) {
        metrics.counter("servicehub.auth.rejected", "operation", operation.name(), "reason", reason).increment();
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now - previous >= 1000 && lastWarning.compareAndSet(previous, now)) {
            LoggerFactory.getLogger(AuthAbuseLimiter.class).warn("Admissão pública recusada: operação={}, motivo={}", operation, reason);
        }
    }
}
