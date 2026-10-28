package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.servicehub.model.*;
import com.servicehub.repository.*;
import com.servicehub.service.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("web")
class SecurityIntegrationTests extends com.servicehub.JwtTestSupport {
    private static final String PASSWORD = "senha123";
    private static final String BCRYPT = new PasswordHashService().hash(PASSWORD);
    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private ServiceOfferingRepository offerings;
    @Autowired private ServiceRequestRepository requests;
    @Autowired private ReviewRepository reviews;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private PasswordHashService encoder;
    @Autowired private UserService userService;
    @Autowired private ServiceOfferingService offeringService;
    @Autowired private ServiceRequestService requestService;
    @Autowired private ReviewService reviewService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @ParameterizedTest
    @ValueSource(strings = {"/api/hello", "/api/status", "/actuator/health", "/api/csrf", "/v3/api-docs"})
    void exposesOnlyTheConfiguredPublicReadEndpoints(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void rejectsAnonymousAccessWithAConsistent401() throws Exception {
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.path").value("/api/users"))
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"ServiceHub\""));
    }

    @Test
    void acceptsBearerWritesWithoutCsrfAndKeepsPublicRegistrationAvailable() throws Exception {
        User client = user("client@example.com", UserRole.CLIENT);
        mvc.perform(patch("/api/users/{id}", client.getId()).with(bearer(client.getEmail(), PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"Novo nome\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName").value("Novo nome"));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nome\",\"email\":\"new@example.com\",\"password\":\"senha123\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void permitsOwnReadsAndPatchesWithoutChangingRoleOrOmittedFields() throws Exception {
        User client = user("client@example.com", UserRole.CLIENT);
        mvc.perform(get("/api/users/{id}", client.getId()).with(bearer(" CLIENT@EXAMPLE.COM ", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(client.getId()));
        mvc.perform(patch("/api/users/{id}", client.getId()).with(bearer(client.getEmail(), PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Nome novo","email":null,"role":"ADMIN","active":false,"passwordHash":"injected"}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(client.getEmail()))
                .andExpect(jsonPath("$.role").value("CLIENT")).andExpect(jsonPath("$.active").value(true));
        assertThat(users.findById(client.getId()).orElseThrow().getPasswordHash()).isEqualTo(BCRYPT);
    }

    @ParameterizedTest
    @CsvSource({"CLIENT,GET", "CLIENT,PUT", "CLIENT,PATCH", "CLIENT,DELETE",
            "PROVIDER,GET", "PROVIDER,PUT", "PROVIDER,PATCH", "PROVIDER,DELETE"})
    void preventsBolaAcrossEveryExistingUserItemOperation(UserRole role, String method) throws Exception {
        User actor = user("actor@example.com", role);
        User victim = user("victim@example.com", UserRole.CLIENT);
        var request = request(HttpMethod.valueOf(method), "/api/users/" + victim.getId())
                .with(bearer(actor.getEmail(), PASSWORD));
        if (method.equals("PUT") || method.equals("PATCH")) request.contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Invasão\",\"email\":\"changed@example.com\",\"password\":\"senha123\"}");
        mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        assertThat(users.findById(victim.getId()).orElseThrow().getEmail()).isEqualTo("victim@example.com");
        assertThat(users.findById(victim.getId()).orElseThrow().isActive()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT", "PROVIDER"})
    void preventsAdministrativeListingWithoutTheAdminRole(String role) throws Exception {
        User actor = user("actor@example.com", UserRole.valueOf(role));
        mvc.perform(get("/api/users").with(bearer(actor.getEmail(), PASSWORD)).header("X-Role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void permitsAdministrativeReadsAndMaintainsThe404Contract() throws Exception {
        User admin = user("admin@example.com", UserRole.ADMIN);
        User client = user("client@example.com", UserRole.CLIENT);
        mvc.perform(get("/api/users").with(bearer(admin.getEmail(), PASSWORD))).andExpect(status().isOk());
        mvc.perform(get("/api/users/{id}", client.getId()).with(bearer(admin.getEmail(), PASSWORD)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/{id}", Long.MAX_VALUE).with(bearer(admin.getEmail(), PASSWORD)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsWrongUnknownAndInactiveCredentialsWithoutLeakingAccountDetails() throws Exception {
        User active = user("active@example.com", UserRole.CLIENT);
        User inactive = user("inactive@example.com", UserRole.CLIENT);
        inactive.setActive(false); users.saveAndFlush(inactive);
        String expected = null;
        for (var pair : new String[][]{{active.getEmail(), "incorrect"}, {"unknown@example.com", PASSWORD}, {inactive.getEmail(), PASSWORD}}) {
            var response = mvc.perform(loginRequest(pair[0], pair[1]))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401))
                    .andReturn().getResponse();
            String message = JsonPath.read(response.getContentAsString(), "$.message");
            if (expected == null) expected = message;
            assertThat(message).isEqualTo(expected).doesNotContain(pair[0], BCRYPT);
        }
    }

    @Test
    void upgradesLegacyHashesOnlyAfterSuccessfulAuthentication() throws Exception {
        User legacy = user("legacy@example.com", UserRole.CLIENT);
        legacy.setPasswordHash(PasswordHashServiceTest.LEGACY); users.saveAndFlush(legacy);
        mvc.perform(loginRequest(legacy.getEmail(), "wrong-password"))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(legacy.getId()).orElseThrow().getPasswordHash()).isEqualTo(PasswordHashServiceTest.LEGACY);
        mvc.perform(loginRequest(legacy.getEmail(), "senha-legada-123"))
                .andExpect(status().isOk());
        String upgraded = users.findById(legacy.getId()).orElseThrow().getPasswordHash();
        assertThat(upgraded).startsWith("$2a$12$");
        assertThat(encoder.matches("senha-legada-123", upgraded)).isTrue();
    }

    @Test
    void neverUpgradesAnInactiveLegacyAccount() throws Exception {
        User legacy = user("legacy@example.com", UserRole.CLIENT);
        legacy.setPasswordHash(PasswordHashServiceTest.LEGACY); legacy.setActive(false); users.saveAndFlush(legacy);
        mvc.perform(loginRequest(legacy.getEmail(), "senha-legada-123"))
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(legacy.getId()).orElseThrow().getPasswordHash()).isEqualTo(PasswordHashServiceTest.LEGACY);
    }

    @Test
    void migratesLongLegacyPasswordsThroughTheRealAuthenticationManager() {
        User legacy = user("legacy-long@example.com", UserRole.CLIENT);
        legacy.setPasswordHash(PasswordHashServiceTest.LONG_LEGACY); users.saveAndFlush(legacy);
        var authenticated = authenticationManager.authenticate(UsernamePasswordAuthenticationToken
                .unauthenticated(legacy.getEmail(), "á".repeat(80)));
        assertThat(authenticated.isAuthenticated()).isTrue();
        String upgraded = users.findById(legacy.getId()).orElseThrow().getPasswordHash();
        assertThat(upgraded).startsWith("{bcrypt-sha256}$2a$12$");
        assertThat(encoder.matches("á".repeat(80), upgraded)).isTrue();
        assertThat(encoder.matches("á".repeat(80) + "x", upgraded)).isFalse();
    }

    @Test
    void guardsUserServiceAccessEvenWithoutAController() {
        User actor = user("actor@example.com", UserRole.CLIENT);
        User victim = user("victim@example.com", UserRole.CLIENT);
        as(actor);
        try {
            assertThat(userService.findById(actor.getId()).getId()).isEqualTo(actor.getId());
            assertThatThrownBy(() -> userService.findById(victim.getId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(userService::findAll).isInstanceOf(AccessDeniedException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void allowsProvidersToManageOnlyTheirOwnServicesAndRejectsForgedOwnership() {
        User provider = user("provider@example.com", UserRole.PROVIDER);
        User other = user("other@example.com", UserRole.PROVIDER);
        ServiceOffering ownedByOther = offering(other);
        as(provider);
        try {
            ServiceOffering own = offeringService.save(newOffering(provider));
            assertThat(own.getProvider().getId()).isEqualTo(provider.getId());
            assertThatThrownBy(() -> offeringService.delete(ownedByOther.getId())).isInstanceOf(AccessDeniedException.class);
            ServiceOffering forged = newOffering(provider);
            ReflectionTestUtils.setField(forged, "id", ownedByOther.getId());
            assertThatThrownBy(() -> offeringService.save(forged)).isInstanceOf(AccessDeniedException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void preventsClientsFromCreatingServices() {
        User client = user("client@example.com", UserRole.CLIENT);
        as(client);
        try { assertThatThrownBy(() -> offeringService.save(newOffering(client))).isInstanceOf(AccessDeniedException.class); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @Test
    void guardsRequestsAndReviewsByTheirActualParticipants() {
        User provider = user("provider@example.com", UserRole.PROVIDER);
        User client = user("client@example.com", UserRole.CLIENT);
        User stranger = user("stranger@example.com", UserRole.CLIENT);
        ServiceOffering offering = offering(provider);
        ServiceRequest request = new ServiceRequest(); request.setClient(client); request.setService(offering);
        request.setTotalPrice(new BigDecimal("100.00")); request.setStatus(ServiceRequestStatus.COMPLETED);
        requests.saveAndFlush(request);
        Review review = new Review(); review.setRequest(request); review.setReviewer(client); review.setRating((short) 5);
        reviews.saveAndFlush(review);
        as(stranger);
        try {
            assertThatThrownBy(() -> requestService.findById(request.getId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reviewService.findById(review.getId())).isInstanceOf(AccessDeniedException.class);
            Review forged = new Review(); forged.setRequest(request); forged.setReviewer(stranger); forged.setRating((short) 5);
            assertThatThrownBy(() -> reviewService.save(forged)).isInstanceOf(AccessDeniedException.class);
            ServiceRequest forgedRequest = new ServiceRequest(); forgedRequest.setClient(client); forgedRequest.setService(offering);
            assertThatThrownBy(() -> requestService.save(forgedRequest)).isInstanceOf(AccessDeniedException.class);
        } finally { SecurityContextHolder.clearContext(); }
        as(provider);
        try {
            assertThat(requestService.findById(request.getId()).getId()).isEqualTo(request.getId());
            assertThat(reviewService.findById(review.getId()).getId()).isEqualTo(review.getId());
            assertThatThrownBy(() -> requestService.delete(request.getId())).isInstanceOf(AccessDeniedException.class);
        } finally { SecurityContextHolder.clearContext(); }
        as(client);
        try {
            ServiceRequest invalid = new ServiceRequest(); invalid.setClient(client); invalid.setService(offering);
            ReflectionTestUtils.setField(invalid, "id", request.getId());
            assertThatThrownBy(() -> requestService.save(invalid)).isInstanceOf(AccessDeniedException.class);
            Review missingReviewerId = new Review(); missingReviewerId.setRequest(request); missingReviewerId.setReviewer(new User());
            assertThatThrownBy(() -> reviewService.save(missingReviewerId)).isInstanceOf(IllegalArgumentException.class);
        } finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"fullName\":\"<script>alert(1)</script>\",\"email\":\"a@example.com\",\"password\":\"senha123\"}",
            "{\"fullName\":\"Nome\",\"email\":\"invalid\",\"password\":\"senha123\"}",
            "{\"fullName\":\"Nome\",\"email\":\"a@example.com\",\"password\":\"short\"}",
            "{\"fullName\":\"Nome\",\"email\":\"a@example.com\",\"password\":\"\"}"})
    void validatesInputAndNeverIncludesRejectedSecretsInErrors(String body) throws Exception {
        String response = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("<script>", "senha123", "short", "passwordHash", "stackTrace");
    }

    @Test
    void appliesTheBcryptUtf8LimitAndPreservesAnEmptyPatchAs400() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nome\",\"email\":\"a@example.com\",\"password\":\"" + "á".repeat(40) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("A senha deve ter no máximo 72 bytes em UTF-8."));
        User client = user("client@example.com", UserRole.CLIENT);
        mvc.perform(patch("/api/users/{id}", client.getId()).with(bearer(client.getEmail(), PASSWORD))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private void as(User user) {
        SecurityContextHolder.getContext().setAuthentication(authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(user.getEmail(), PASSWORD)));
    }
    private User user(String email, UserRole role) {
        User user = new User(); user.setFullName("Usuário"); user.setEmail(email); user.setRole(role); user.setPasswordHash(BCRYPT);
        return users.saveAndFlush(user);
    }
    private ServiceOffering newOffering(User provider) {
        ServiceOffering offering = new ServiceOffering(); offering.setTitle("Limpeza"); offering.setCategory(ServiceCategory.LIMPEZA);
        offering.setPrice(new BigDecimal("100.00")); offering.setProvider(provider); return offering;
    }
    private ServiceOffering offering(User provider) { return offerings.saveAndFlush(newOffering(provider)); }
}
