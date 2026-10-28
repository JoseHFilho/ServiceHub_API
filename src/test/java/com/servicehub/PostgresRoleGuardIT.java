package com.servicehub;

import com.servicehub.testing.PostgresTestContainers;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers(disabledWithoutDocker=false)
class PostgresRoleGuardIT {
    @Container static final PostgreSQLContainer postgres=PostgresTestContainers.create("role-policy-database");
    private Connection connection() throws SQLException {return DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());}
    @BeforeAll static void migrate() {Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration").load().migrate();}
    private long scalar(Connection connection,String sql) throws SQLException {
        try(var statement=connection.createStatement();var row=statement.executeQuery(sql)){row.next();return row.getLong(1);}
    }
    private record Fixture(long provider,long client,long service) { }
    private Fixture fixture(Connection connection) throws SQLException {
        String suffix=UUID.randomUUID().toString();
        long provider=scalar(connection,"INSERT INTO users(full_name,email,password_hash,role) VALUES('Operator Provider','p-"+suffix+"@role.invalid','fixture-only','PROVIDER') RETURNING id");
        long client=scalar(connection,"INSERT INTO users(full_name,email,password_hash) VALUES('Operator Client','c-"+suffix+"@role.invalid','fixture-only') RETURNING id");
        long service=scalar(connection,"INSERT INTO services(title,price,category,provider_id) VALUES('Operator Service',125.50,'LIMPEZA',"+provider+") RETURNING id");
        return new Fixture(provider,client,service);
    }
    @Test void directRoleRemovalCannotRaceWithACommittedOpenBooking() throws Exception {
        try(var first=connection();var executor=Executors.newSingleThreadExecutor()) {
            var fixture=fixture(first);first.setAutoCommit(false);
            scalar(first,"SELECT id FROM users WHERE id="+fixture.provider+" FOR UPDATE");
            String marker="role_wait_"+UUID.randomUUID().toString().replace("-","");
            var future=executor.submit(()->{
                try(var second=connection();var statement=second.createStatement()) {
                    second.setClientInfo("ApplicationName",marker);second.setAutoCommit(false);
                    statement.execute("SET LOCAL statement_timeout='8s'");
                    try {statement.executeUpdate("UPDATE users SET role='CLIENT' WHERE id="+fixture.provider);second.commit();return "COMMITTED";}
                    catch(SQLException exception){second.rollback();return exception.getSQLState();}
                }
            });
            boolean blocked=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            try(var observer=connection();var statement=observer.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name=? AND cardinality(pg_blocking_pids(pid))>0)")) {
                statement.setString(1,marker);
                while(System.nanoTime()<deadline) {
                    try(var row=statement.executeQuery()){row.next();blocked=row.getBoolean(1);}
                    if(blocked)break;
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
            }
            try {
                assertThat(blocked).isTrue();
                try(var statement=first.createStatement()) {statement.executeUpdate("INSERT INTO service_requests(service_id,client_id,status,total_price) VALUES("+fixture.service+","+fixture.client+",'PENDING',125.50)");}
                first.commit();
                assertThat(future.get(10,TimeUnit.SECONDS)).isEqualTo("23514");
                assertThat(scalar(first,"SELECT count(*) FROM users WHERE id="+fixture.provider+" AND role='PROVIDER'")).isEqualTo(1);
                assertThat(scalar(first,"SELECT count(*) FROM service_requests WHERE service_id="+fixture.service+" AND status='PENDING'")).isEqualTo(1);
            } finally {first.rollback();}
        }
    }
    @Test void closedHistorySurvivesOperatorChangesAndUnsafeIsolationIsRejected() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            var fixture=fixture(connection);
            statement.executeUpdate("INSERT INTO service_requests(service_id,client_id,status,total_price) VALUES("+fixture.service+","+fixture.client+",'COMPLETED',125.50)");
            assertThat(statement.executeUpdate("UPDATE users SET role='CLIENT' WHERE id="+fixture.provider)).isEqualTo(1);
            assertThat(scalar(connection,"SELECT count(*) FROM service_requests WHERE service_id="+fixture.service+" AND status='COMPLETED' AND total_price=125.50")).isEqualTo(1);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            assertThatThrownBy(()->statement.executeUpdate("UPDATE users SET role='PROVIDER' WHERE id="+fixture.provider))
                    .isInstanceOf(SQLException.class).satisfies(exception->assertThat(((SQLException)exception).getSQLState()).isEqualTo("25001"));
            connection.rollback();
        }
    }
    @Test void directPasswordWritesIncrementCredentialVersionAndCannotRollItBack() throws Exception {
        try(var connection=connection();var statement=connection.createStatement()) {
            var fixture=fixture(connection);
            assertThat(scalar(connection,"SELECT credential_version FROM users WHERE id="+fixture.client)).isZero();
            statement.executeUpdate("UPDATE users SET password_hash='changed-fixture' WHERE id="+fixture.client);
            assertThat(scalar(connection,"SELECT credential_version FROM users WHERE id="+fixture.client)).isEqualTo(1);
            assertThatThrownBy(()->statement.executeUpdate("UPDATE users SET credential_version=0 WHERE id="+fixture.client))
                    .isInstanceOf(SQLException.class).satisfies(exception->assertThat(((SQLException)exception).getSQLState()).isEqualTo("23514"));
            statement.executeUpdate("UPDATE users SET full_name='Updated Fixture' WHERE id="+fixture.client);
            assertThat(scalar(connection,"SELECT credential_version FROM users WHERE id="+fixture.client)).isEqualTo(1);
        }
    }
}
