package com.servicehub;

import com.servicehub.model.*;
import com.servicehub.repository.*;
import com.servicehub.security.*;
import com.servicehub.service.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BusinessRestIntegrationTests extends JwtTestSupport {
    private static final String HASH = new PasswordHashService().hash("senha123");
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ServiceOfferingRepository offerings;
    @Autowired ServiceRequestRepository requests;
    @Autowired ReviewRepository reviews;
    MockMvc mvc;
    User provider, otherProvider, client, stranger, admin;
    ServiceOffering offering;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        provider = user("provider", UserRole.PROVIDER);
        otherProvider = user("otherProvider", UserRole.PROVIDER);
        client = user("client", UserRole.CLIENT);
        stranger = user("stranger", UserRole.CLIENT);
        admin = user("admin", UserRole.ADMIN);
        offering = offering(provider, ServiceCategory.LIMPEZA, true);
    }

    static Stream<Arguments> protectedRoutes() {
        var routes = List.of("POST /api/services", "GET /api/services", "GET /api/services/1",
                "PUT /api/services/1", "PATCH /api/services/1", "DELETE /api/services/1",
                "POST /api/requests", "GET /api/requests", "GET /api/requests/1", "PATCH /api/requests/1/status",
                "POST /api/reviews", "GET /api/reviews", "GET /api/reviews/1");
        return routes.stream().flatMap(route -> Stream.of(false, true).map(inactive -> Arguments.of(route, inactive)));
    }
    @ParameterizedTest @MethodSource("protectedRoutes")
    void everyNewRouteRequiresJwtAndAnActiveAccount(String route, boolean inactive) throws Exception {
        String[] parts = route.split(" ");
        var builder = request(HttpMethod.valueOf(parts[0]), parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (inactive) {
            String token = jwtService.generateToken(new ServiceHubPrincipal(client)).getTokenValue();
            client.setActive(false); users.saveAndFlush(client);
            builder.header("Authorization", "Bearer " + token);
        }
        mvc.perform(builder).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest @EnumSource(ServiceCategory.class)
    void providerCreatesEachSupportedCategoryWithoutBodyIdentity(ServiceCategory category) throws Exception {
        var payload = servicePayload();
        payload.put("category", category.name());
        payload.put("providerId", otherProvider.getId());
        payload.put("provider", Map.of("id", otherProvider.getId()));
        payload.put("id", 9999999);
        payload.put("active", false);
        mvc.perform(body(post("/api/services").with(as(provider)), payload))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.providerId").value(provider.getId()))
                .andExpect(jsonPath("$.category").value(category.name()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.title").value("Limpeza nova"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void clientCannotCreateOrModifyAServiceAndProviderCannotDelegate() throws Exception {
        mvc.perform(body(post("/api/services").with(as(client)), servicePayload())).andExpect(status().isForbidden());
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(client)), Map.of("price", 77))).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/services?providerId=" + otherProvider.getId()).with(as(provider)), servicePayload())).andExpect(status().isForbidden());
    }

    @Test
    void adminDelegatesOnlyToActiveUsersOfTheRequiredRole() throws Exception {
        mvc.perform(body(post("/api/services").with(as(admin)), servicePayload())).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/services?providerId=" + client.getId()).with(as(admin)), servicePayload())).andExpect(status().isNotFound());
        mvc.perform(body(post("/api/services?providerId=" + provider.getId()).with(as(admin)), servicePayload()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.providerId").value(provider.getId()));
        mvc.perform(body(post("/api/requests?clientId=" + client.getId()).with(as(admin)), Map.of("serviceId", offering.getId())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.clientId").value(client.getId()));
        mvc.perform(body(post("/api/requests").with(as(admin)), Map.of("serviceId", offering.getId()))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/requests?clientId=" + provider.getId()).with(as(admin)), Map.of("serviceId", offering.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void catalogFiltersCategoryAndProviderAndHidesUnavailableRows() throws Exception {
        offering(provider, ServiceCategory.ENCANAMENTO, true);
        offering(provider, ServiceCategory.LIMPEZA, false);
        var hiddenProvider = user("inactiveProvider", UserRole.PROVIDER);
        hiddenProvider.setActive(false); users.save(hiddenProvider);
        offering(hiddenProvider, ServiceCategory.LIMPEZA, true);
        mvc.perform(get("/api/services?category=LIMPEZA&providerId=" + provider.getId() + "&size=1").with(as(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1)).andExpect(jsonPath("$.content[0].id").value(offering.getId()))
                .andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
        mvc.perform(get("/api/services?category=LIMPEZA&providerId=" + provider.getId() + "&page=1&size=1").with(as(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void putReplacesEditableFieldsAndPatchPreservesOmissionsAndOwner() throws Exception {
        var payload = servicePayload();
        payload.remove("description");
        mvc.perform(body(put("/api/services/" + offering.getId()).with(as(provider)), payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").isEmpty())
                .andExpect(jsonPath("$.updatedAt").exists());
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(provider)),
                Map.of("price", 200, "providerId", otherProvider.getId(), "active", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(200))
                .andExpect(jsonPath("$.title").value("Limpeza nova")).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.providerId").value(provider.getId()));
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(admin)), Map.of("title", "Admin editou")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Admin editou"));
    }

    @ParameterizedTest @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void anotherProviderCannotChangeOrDeactivateAService(String verb) throws Exception {
        var builder = request(HttpMethod.valueOf(verb), "/api/services/" + offering.getId()).with(as(otherProvider));
        mvc.perform(body(builder, servicePayload())).andExpect(status().isForbidden());
        assertThat(offerings.findById(offering.getId()).orElseThrow().getPrice()).isEqualByComparingTo("150.00");
    }

    @Test
    void logicalDeletionPreservesServiceAndCompletedHistory() throws Exception {
        var request = requestRow(ServiceRequestStatus.COMPLETED, client);
        var review = reviewRow(request);
        mvc.perform(delete("/api/services/" + offering.getId()).with(as(provider))).andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(offerings.findById(offering.getId()).orElseThrow().isActive()).isFalse();
        assertThat(requests.existsById(request.getId())).isTrue();
        assertThat(reviews.existsById(review.getId())).isTrue();
        mvc.perform(get("/api/services/" + offering.getId()).with(as(client))).andExpect(status().isNotFound());
        mvc.perform(get("/api/requests/" + request.getId()).with(as(client))).andExpect(status().isOk());
        mvc.perform(get("/api/reviews/" + review.getId()).with(as(provider))).andExpect(status().isOk());
        mvc.perform(body(post("/api/requests").with(as(stranger)), Map.of("serviceId", offering.getId()))).andExpect(status().isNotFound());
    }

    @ParameterizedTest @EnumSource(value = ServiceRequestStatus.class, names = {"PENDING", "ACCEPTED", "IN_PROGRESS"})
    void openRequestsBlockServiceAndUserDeactivation(ServiceRequestStatus state) throws Exception {
        requestRow(state, client);
        mvc.perform(delete("/api/services/" + offering.getId()).with(as(provider))).andExpect(status().isConflict());
        mvc.perform(delete("/api/users/" + provider.getId()).with(as(provider))).andExpect(status().isConflict());
        mvc.perform(delete("/api/users/" + client.getId()).with(as(client))).andExpect(status().isConflict());
        assertThat(offering.isActive()).isTrue();
        assertThat(client.isActive()).isTrue();
    }

    static Stream<Arguments> invalidServices() {
        return Stream.of(Arguments.of("title", ""), Arguments.of("title", " ".repeat(5)), Arguments.of("title", "x".repeat(151)),
                Arguments.of("title", "<b>HTML</b>"), Arguments.of("price", 0), Arguments.of("price", -1),
                Arguments.of("price", "1.234"), Arguments.of("price", "100000000"), Arguments.of("category", "INVALID"),
                Arguments.of("description", "x".repeat(5001)), Arguments.of("description", "<script>alert(1)</script>"));
    }
    @ParameterizedTest @MethodSource("invalidServices")
    void validatesServiceInputBeforePersisting(String field, Object value) throws Exception {
        var payload = servicePayload(); payload.put(field, value);
        long before = offerings.count();
        mvc.perform(body(post("/api/services").with(as(provider)), payload)).andExpect(status().isBadRequest());
        assertThat(offerings.count()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"title", "price", "category"})
    void putRequiresAllMandatoryFields(String missing) throws Exception {
        var payload = servicePayload(); payload.remove(missing);
        mvc.perform(body(put("/api/services/" + offering.getId()).with(as(provider)), payload)).andExpect(status().isBadRequest());
    }

    @Test
    void emptyPatchAndInvalidNotesAndSchedulesAreRejected() throws Exception {
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(provider)), Map.of())).andExpect(status().isBadRequest());
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(provider)), Map.of("price", 0))).andExpect(status().isBadRequest());
        for (var extra : List.of(Map.of("notes", "x".repeat(5001)), Map.of("notes", "<b>privado</b>"), Map.of("scheduledAt", "2000-01-01T10:00:00"))) {
            var payload = new HashMap<String, Object>(extra); payload.put("serviceId", offering.getId());
            mvc.perform(body(post("/api/requests").with(as(client)), payload)).andExpect(status().isBadRequest());
        }
        mvc.perform(body(post("/api/requests").with(as(client)), Map.of())).andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"/api/services?page=-1", "/api/services?size=0", "/api/services?size=101",
            "/api/services?category=UNKNOWN", "/api/services/0", "/api/services?providerId=-1",
            "/api/requests?page=-1", "/api/requests?size=101", "/api/requests?status=INVALID", "/api/requests?serviceId=0",
            "/api/reviews?size=101", "/api/reviews?requestId=-1", "/api/reviews/invalid"})
    void rejectsInvalidPathsAndFilters(String path) throws Exception {
        mvc.perform(get(path).with(as(client))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void clientIdentityStatusAndPriceComeFromJwtAndDatabase() throws Exception {
        var payload = new HashMap<String,Object>();
        payload.put("serviceId", offering.getId()); payload.put("clientId", stranger.getId());
        payload.put("client", Map.of("id", stranger.getId())); payload.put("totalPrice", 1); payload.put("status", "COMPLETED");
        var response = mvc.perform(body(post("/api/requests").with(as(client)), payload))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.clientId").value(client.getId())).andExpect(jsonPath("$.totalPrice").value(150))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.client").doesNotExist()).andReturn();
        long id = id(response);
        mvc.perform(body(patch("/api/services/" + offering.getId()).with(as(provider)), Map.of("price", 220))).andExpect(status().isOk());
        mvc.perform(get("/api/requests/" + id).with(as(client))).andExpect(status().isOk()).andExpect(jsonPath("$.totalPrice").value(150));
        mvc.perform(body(patch("/api/requests/" + id + "/status").with(as(provider)),
                Map.of("status", "ACCEPTED", "clientId", stranger.getId(), "serviceId", 999999, "totalPrice", 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientId").value(client.getId()))
                .andExpect(jsonPath("$.serviceId").value(offering.getId())).andExpect(jsonPath("$.totalPrice").value(150));
        mvc.perform(body(post("/api/requests?clientId=" + stranger.getId()).with(as(client)), Map.of("serviceId", offering.getId()))).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/requests").with(as(provider)), Map.of("serviceId", offering.getId()))).andExpect(status().isForbidden());
    }

    @Test
    void unknownOrInactiveServiceAndInactiveProviderCannotBeBooked() throws Exception {
        mvc.perform(body(post("/api/requests").with(as(client)), Map.of("serviceId", 999999999))).andExpect(status().isNotFound());
        offering.setActive(false); offerings.saveAndFlush(offering);
        mvc.perform(body(post("/api/requests").with(as(client)), Map.of("serviceId", offering.getId()))).andExpect(status().isNotFound());
        offering.setActive(true); provider.setActive(false); users.saveAndFlush(provider); offerings.saveAndFlush(offering);
        mvc.perform(body(post("/api/requests").with(as(client)), Map.of("serviceId", offering.getId()))).andExpect(status().isNotFound());
    }

    @Test
    void requestsAndReviewsAreScopedToParticipantsEvenWithFilters() throws Exception {
        var own = requestRow(ServiceRequestStatus.COMPLETED, client);
        var other = requestRow(ServiceRequestStatus.PENDING, stranger);
        var review = reviewRow(own);
        mvc.perform(get("/api/requests/" + own.getId()).with(as(stranger))).andExpect(status().isForbidden());
        mvc.perform(body(patch("/api/requests/" + own.getId() + "/status").with(as(stranger)), Map.of("status", "CANCELLED"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/requests?serviceId=" + offering.getId()).with(as(client)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(own.getId()));
        mvc.perform(get("/api/requests?status=PENDING").with(as(provider))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/requests?serviceId=" + offering.getId()).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/reviews/" + review.getId()).with(as(stranger))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reviews?requestId=" + own.getId()).with(as(stranger))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(get("/api/reviews?serviceId=" + offering.getId()).with(as(provider))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/reviews?requestId=" + other.getId()).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void providerAndAdminAdvanceTheFullChainAndCannotReopenFinalStates() throws Exception {
        var request = requestRow(ServiceRequestStatus.PENDING, client);
        for (var state : List.of("ACCEPTED", "IN_PROGRESS", "COMPLETED")) {
            var actor = state.equals("IN_PROGRESS") ? admin : provider;
            mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(actor)), Map.of("status", state)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state));
        }
        mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(provider)), Map.of("status", "PENDING"))).andExpect(status().isConflict());
        mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(admin)), Map.of("status", "CANCELLED"))).andExpect(status().isConflict());
    }

    @ParameterizedTest @EnumSource(value = ServiceRequestStatus.class, names = {"PENDING", "ACCEPTED", "IN_PROGRESS"})
    void cancellationDependsOnStateAndActor(ServiceRequestStatus state) throws Exception {
        var request = requestRow(state, client);
        mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(client)), Map.of("status", "CANCELLED")))
                .andExpect(state == ServiceRequestStatus.IN_PROGRESS ? status().isForbidden() : status().isOk());
        if (state == ServiceRequestStatus.IN_PROGRESS)
            mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(provider)), Map.of("status", "CANCELLED")))
                    .andExpect(status().isOk());
        mvc.perform(body(patch("/api/requests/" + request.getId() + "/status").with(as(admin)), Map.of("status", "ACCEPTED"))).andExpect(status().isConflict());
    }

    @Test
    void transitionContractDistinguishesMalformedForbiddenAndConflictingRequests() throws Exception {
        var request = requestRow(ServiceRequestStatus.PENDING, client);
        String path = "/api/requests/" + request.getId() + "/status";
        mvc.perform(body(patch(path).with(as(provider)), Map.of("status", "COMPLETED"))).andExpect(status().isConflict());
        mvc.perform(body(patch(path).with(as(provider)), Map.of("status", "PENDING"))).andExpect(status().isConflict());
        mvc.perform(body(patch(path).with(as(client)), Map.of("status", "ACCEPTED"))).andExpect(status().isForbidden());
        mvc.perform(body(patch(path).with(as(provider)), Map.of("status", "INVALID"))).andExpect(status().isBadRequest());
        mvc.perform(body(patch(path).with(as(provider)), Map.of())).andExpect(status().isBadRequest());
    }

    @Test
    void completedReviewUsesTheRealClientAndPreservesPrivacyAndUniqueConstraint() throws Exception {
        var request = requestRow(ServiceRequestStatus.COMPLETED, client);
        var response = mvc.perform(body(post("/api/reviews").with(as(client)),
                Map.of("requestId", request.getId(), "rating", 5, "comment", "  Excelente serviço  ", "reviewerId", stranger.getId())))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.reviewerId").value(client.getId())).andExpect(jsonPath("$.comment").value("Excelente serviço"))
                .andExpect(jsonPath("$.request").doesNotExist()).andExpect(jsonPath("$.reviewer").doesNotExist())
                .andExpect(jsonPath("$.notes").doesNotExist()).andReturn();
        long reviewId = id(response);
        mvc.perform(get("/api/reviews/" + reviewId).with(as(provider))).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(5));
        mvc.perform(body(post("/api/reviews").with(as(client)), Map.of("requestId", request.getId(), "rating", 4)))
                .andExpect(status().isConflict());
        assertThat(reviews.findByRequestId(request.getId())).isPresent();
    }

    @ParameterizedTest @EnumSource(value = ServiceRequestStatus.class, names = {"PENDING", "ACCEPTED", "IN_PROGRESS", "CANCELLED"})
    void onlyCompletedRequestsCanBeReviewed(ServiceRequestStatus state) throws Exception {
        var request = requestRow(state, client);
        mvc.perform(body(post("/api/reviews").with(as(client)), Map.of("requestId", request.getId(), "rating", 5))).andExpect(status().isConflict());
        assertThat(reviews.findByRequestId(request.getId())).isEmpty();
    }

    @Test
    void providerAndStrangerCannotReviewButAdminRepresentsTheActualClient() throws Exception {
        var request = requestRow(ServiceRequestStatus.COMPLETED, client);
        var payload = Map.of("requestId", request.getId(), "rating", 5);
        mvc.perform(body(post("/api/reviews").with(as(stranger)), payload)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/reviews").with(as(provider)), payload)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/reviews").with(as(admin)), payload)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewerId").value(client.getId()));
        mvc.perform(body(post("/api/reviews").with(as(client)), Map.of("requestId", 9999999, "rating", 5))).andExpect(status().isNotFound());
    }

    static Stream<Arguments> invalidReviews() {
        return Stream.of(Arguments.of("rating", 0), Arguments.of("rating", 6), Arguments.of("rating", -1),
                Arguments.of("rating", 1.5), Arguments.of("rating", "bad"), Arguments.of("comment", "x".repeat(2001)),
                Arguments.of("comment", "<script>x</script>"), Arguments.of("requestId", 0), Arguments.of("requestId", 1.5));
    }
    @ParameterizedTest @MethodSource("invalidReviews")
    void validatesReviewPayloadWithoutTruncatingFractionalNumbers(String field, Object value) throws Exception {
        var request = requestRow(ServiceRequestStatus.COMPLETED, client);
        var payload = new HashMap<String,Object>(Map.of("requestId", request.getId(), "rating", 5)); payload.put(field, value);
        mvc.perform(body(post("/api/reviews").with(as(client)), payload)).andExpect(status().isBadRequest());
        assertThat(reviews.findByRequestId(request.getId())).isEmpty();
    }

    @Test
    void newOpenApiOperationsDescribeBearerAndSuccessfulResponses() throws Exception {
        var response = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var doc = json.readTree(response.getResponse().getContentAsString());
        for (var path : List.of("/api/services", "/api/services/{id}", "/api/requests", "/api/requests/{id}", "/api/requests/{id}/status", "/api/reviews", "/api/reviews/{id}")) {
            var operations = doc.path("paths").path(path);
            assertThat(operations.isMissingNode()).isFalse();
            operations.properties().forEach(operation -> {
                var op = operation.getValue();
                assertThat(op.path("summary").asText()).isNotBlank();
                assertThat(op.path("description").asText()).isNotBlank();
                assertThat(op.path("security").toString()).contains("bearerAuth");
                assertThat(op.path("responses").has("401")).isTrue();
                assertThat(op.path("responses").has("403")).isTrue();
            });
        }
        assertThat(doc.path("components").path("schemas").path("ServiceWriteDTO").path("properties").has("providerId")).isFalse();
        for (var path : List.of("/api/services", "/api/requests", "/api/reviews")) {
            var content = doc.path("paths").path(path).path("post").path("requestBody").path("content");
            var jsonContent = content.path("application/json");
            assertThat(jsonContent.has("example") || jsonContent.has("examples"))
                    .as("Exemplo JSON de %s: %s", path, content).isTrue();
        }
    }

    private User user(String prefix, UserRole role) {
        var user = new User(); user.setFullName(prefix); user.setEmail(prefix.toLowerCase(Locale.ROOT) + "@etapa4.test");
        user.setPasswordHash(HASH); user.setRole(role); return users.saveAndFlush(user);
    }
    private ServiceOffering offering(User owner, ServiceCategory category, boolean active) {
        var service = new ServiceOffering(); service.setProvider(owner); service.setTitle("Serviço existente");
        service.setDescription("Descrição"); service.setCategory(category); service.setPrice(new BigDecimal("150.00")); service.setActive(active);
        return offerings.saveAndFlush(service);
    }
    private ServiceRequest requestRow(ServiceRequestStatus state, User owner) {
        var row = new ServiceRequest(); row.setClient(owner); row.setService(offering); row.setTotalPrice(offering.getPrice());
        row.setStatus(state); row.setNotes("Informação privada"); return requests.saveAndFlush(row);
    }
    private Review reviewRow(ServiceRequest request) {
        var review = new Review(); review.setRequest(request); review.setReviewer(request.getClient()); review.setRating((short)5);
        return reviews.saveAndFlush(review);
    }
    private RequestPostProcessor as(User user) {
        String token = jwtService.generateToken(new ServiceHubPrincipal(user)).getTokenValue();
        return request -> { request.addHeader("Authorization", "Bearer " + token); return request; };
    }
    private Map<String,Object> servicePayload() {
        return new HashMap<>(Map.of("title", "  Limpeza   nova ", "description", "Descrição simples", "price", "160.50", "category", "LIMPEZA"));
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder builder, Object payload) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
    }
    private long id(MvcResult result) { return json.readTree(result.getResponse().getContentAsByteArray()).path("id").asLong(); }
}
