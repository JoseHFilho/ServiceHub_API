package com.servicehub;

import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.repository.ServiceRequestRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Tag("integration")
class QueryOptimizationIntegrationTests extends JwtTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ServiceRequestRepository requests;
    long provider, client, stranger, service;

    @BeforeEach
    void fixtures() {
        provider = user("PROVIDER"); client = user("CLIENT"); stranger = user("CLIENT");
        jdbc.update("INSERT INTO services(title,price,category,provider_id) VALUES ('Query probe',200,'LIMPEZA',?)", provider);
        service = jdbc.queryForObject("SELECT id FROM services WHERE provider_id=?", Long.class, provider);
    }

    @ParameterizedTest
    @EnumSource(ServiceRequestStatus.class)
    void existenceProbePreservesBothParticipationPathsAndStatusFiltering(ServiceRequestStatus status) {
        jdbc.update("INSERT INTO service_requests(service_id,client_id,status,total_price) VALUES (?,?,?,150)", service, client, status.name());
        for (long actor : List.of(provider, client, stranger)) {
            boolean previous = jdbc.queryForObject("SELECT COUNT(r.id)>0 FROM service_requests r JOIN services s ON s.id=r.service_id WHERE (r.client_id=? OR s.provider_id=?) AND r.status IN ('PENDING','ACCEPTED','IN_PROGRESS')", Boolean.class, actor, actor);
            assertThat(requests.hasOpenRequestsForUser(actor, ServiceRequestStatus.openStatuses())).isEqualTo(previous);
            assertThat(previous).isEqualTo(actor != stranger && ServiceRequestStatus.openStatuses().contains(status));
        }
    }

    @Test
    void existenceProbeKeepsLegacyDualParticipationAndCustomOrEmptyStatusSets() {
        // V5 can promote a former client to PROVIDER without changing their earlier requests.
        long otherProvider = user("PROVIDER");
        jdbc.update("INSERT INTO services(title,price,category,provider_id) VALUES ('Legacy',200,'LIMPEZA',?)", otherProvider);
        long otherService = jdbc.queryForObject("SELECT id FROM services WHERE provider_id=?", Long.class, otherProvider);
        jdbc.update("INSERT INTO service_requests(service_id,client_id,status,total_price) VALUES (?,?,'COMPLETED',150)", otherService, provider);
        assertThat(requests.hasOpenRequestsForUser(provider, List.of(ServiceRequestStatus.COMPLETED))).isTrue();
        assertThat(requests.hasOpenRequestsForUser(provider, ServiceRequestStatus.openStatuses())).isFalse();
        assertThat(requests.hasOpenRequestsForUser(provider, List.of())).isFalse();
        jdbc.update("INSERT INTO service_requests(service_id,client_id,status,total_price) VALUES (?,?,'PENDING',150)", service, client);
        assertThat(jdbc.queryForObject("SELECT COUNT(r.id)>0 FROM service_requests r JOIN services s ON s.id=r.service_id WHERE (r.client_id=? OR s.provider_id=?) AND r.status IN ('PENDING','ACCEPTED','IN_PROGRESS')", Boolean.class, provider, provider)).isTrue();
        assertThat(requests.hasOpenRequestsForUser(provider, ServiceRequestStatus.openStatuses())).isTrue();
        assertThat(requests.hasOpenRequestsForUser(provider, List.of(ServiceRequestStatus.COMPLETED))).isTrue();
    }

    private long user(String role) {
        String email = UUID.randomUUID() + "@query-test.invalid";
        jdbc.update("INSERT INTO users(full_name,email,password_hash,role) VALUES ('Query fixture',?,'not-a-login-hash',?)", email, role);
        return jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, email);
    }
}
