package com.servicehub;

import com.servicehub.exception.*;
import com.servicehub.model.*;
import com.servicehub.model.dto.*;
import com.servicehub.repository.*;
import com.servicehub.security.*;
import com.servicehub.service.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BusinessConcurrencyIntegrationTests extends JwtTestSupport {
    @Autowired TransactionTemplate tx;
    @Autowired UserRepository users;
    @Autowired ServiceOfferingRepository offerings;
    @Autowired ServiceRequestRepository requests;
    @Autowired ReviewRepository reviews;
    @Autowired ServiceOfferingService service;
    @Autowired ServiceRequestService booking;
    @Autowired ReviewService reviewing;
    @Autowired UserService userService;
    User provider, client;
    ServiceOffering offering;
    private static final String HASH = new PasswordHashService().hash("senha123");

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(status -> {
            provider = user(UserRole.PROVIDER); client = user(UserRole.CLIENT);
            offering = new ServiceOffering(); offering.setTitle("Concorrência"); offering.setProvider(provider);
            offering.setCategory(ServiceCategory.LIMPEZA); offering.setPrice(new BigDecimal("150.00"));
            offering = offerings.saveAndFlush(offering);
        });
    }
    @AfterEach
    void removeOnlyOwnedFixtures() {
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            requests.findByClientId(client.getId()).forEach(request -> {
                reviews.findByRequestId(request.getId()).ifPresent(reviews::delete);
                reviews.flush(); requests.delete(request);
            });
            requests.flush(); offerings.deleteById(offering.getId()); offerings.flush();
            users.deleteById(client.getId()); users.deleteById(provider.getId());
        });
    }

    @Test
    void simultaneousReviewsCreateExactlyOneRow() throws Exception {
        Long requestId = request(ServiceRequestStatus.COMPLETED);
        var result = race(() -> as(client, () -> reviewing.create(new ReviewCreateDTO(requestId, 5, "Ótimo"))),
                () -> as(client, () -> reviewing.create(new ReviewCreateDTO(requestId, 4, "Bom"))));
        assertThat(result).containsExactlyInAnyOrder("OK", "CONFLICT");
        tx.executeWithoutResult(status -> assertThat(reviews.findByRequestId(requestId)).isPresent());
    }

    @Test
    void simultaneousStatusChangesDoNotSilentlySucceedTwice() throws Exception {
        Long requestId = request(ServiceRequestStatus.PENDING);
        var result = race(() -> as(provider, () -> booking.changeStatus(requestId, new RequestStatusDTO(ServiceRequestStatus.ACCEPTED))),
                () -> as(provider, () -> booking.changeStatus(requestId, new RequestStatusDTO(ServiceRequestStatus.ACCEPTED))));
        assertThat(result).containsExactlyInAnyOrder("OK", "CONFLICT");
        tx.executeWithoutResult(status -> assertThat(requests.findById(requestId).orElseThrow().getStatus()).isEqualTo(ServiceRequestStatus.ACCEPTED));
    }

    @Test
    void bookingAndServiceDeactivationCannotBothCommit() throws Exception {
        var result = race(() -> as(client, () -> booking.create(new RequestCreateDTO(offering.getId(), null, null), null)),
                () -> as(provider, () -> { service.delete(offering.getId()); return null; }));
        assertThat(result.stream().filter("OK"::equals).count()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            var stored = offerings.findById(offering.getId()).orElseThrow();
            var created = requests.findByClientId(client.getId());
            if (stored.isActive()) assertThat(created).hasSize(1);
            else assertThat(created).isEmpty();
        });
    }

    @Test
    void bookingAndClientDeactivationCannotBothCommit() throws Exception {
        var result = race(() -> as(client, () -> booking.create(new RequestCreateDTO(offering.getId(), null, null), null)),
                () -> as(client, () -> { userService.deleteUser(client.getId()); return null; }));
        assertThat(result.stream().filter("OK"::equals).count()).isEqualTo(1);
        tx.executeWithoutResult(status -> {
            var stored = users.findById(client.getId()).orElseThrow();
            var created = requests.findByClientId(client.getId());
            if (stored.isActive()) assertThat(created).hasSize(1);
            else assertThat(created).isEmpty();
        });
    }

    private Long request(ServiceRequestStatus state) {
        return tx.execute(status -> {
            var request = new ServiceRequest(); request.setClient(users.findById(client.getId()).orElseThrow());
            request.setService(offerings.findById(offering.getId()).orElseThrow()); request.setStatus(state);
            request.setTotalPrice(new BigDecimal("150.00")); return requests.saveAndFlush(request).getId();
        });
    }
    private User user(UserRole role) {
        var user = new User(); user.setFullName("Teste concorrente"); user.setEmail(UUID.randomUUID() + "@concurrency.test");
        user.setRole(role); user.setPasswordHash(HASH); return users.saveAndFlush(user);
    }
    private Object as(User user, Supplier<?> action) {
        var principal = new ServiceHubPrincipal(user);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        SecurityContextHolder.setContext(context);
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }
    private List<String> race(Supplier<?> first, Supplier<?> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> run(first, ready, start));
            var b = pool.submit(() -> run(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }
    private String run(Supplier<?> action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barreira não liberada.");
        try { action.get(); return "OK"; }
        catch (BusinessConflictException expected) { return "CONFLICT"; }
        catch (ResourceNotFoundException expected) { return "UNAVAILABLE"; }
        catch (BadCredentialsException expected) { return "INACTIVE"; }
    }
}
