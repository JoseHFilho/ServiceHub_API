package com.servicehub;

import com.servicehub.graphql.GraphQlBodyLimitFilter;
import com.servicehub.model.*;
import com.servicehub.repository.*;
import com.servicehub.security.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.test.tester.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@org.junit.jupiter.api.Tag("web")
class GraphQlIntegrationTests extends JwtTestSupport {
    private static final String HASH = new PasswordHashService().hash("senha123");
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired ServiceOfferingRepository offerings;
    @Autowired ServiceRequestRepository requests;
    @Autowired ReviewRepository reviews;
    @Autowired GraphQlBodyLimitFilter bodyLimit;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;
    User provider, otherProvider, client, stranger, admin;
    ServiceOffering offering;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).addFilter(bodyLimit, "/graphql").build();
        provider = user("provider", UserRole.PROVIDER);
        otherProvider = user("other", UserRole.PROVIDER);
        client = user("client", UserRole.CLIENT);
        stranger = user("stranger", UserRole.CLIENT);
        admin = user("admin", UserRole.ADMIN);
        offering = offering(provider, ServiceCategory.LIMPEZA, true);
    }

    @Test
    void listsActiveServicesWithPublicProviderBatchAndPagination() {
        tester(client).document("""
                { services(category:LIMPEZA, page:{size:1}) {
                    content { id title price category provider { id fullName avatarUrl } }
                    page size totalElements totalPages
                } }
                """).execute().errors().verify()
                .path("services.content[0].id").entity(String.class).isEqualTo(offering.getId().toString())
                .path("services.content[0].price").entity(String.class).isEqualTo("150.00")
                .path("services.content[0].provider.id").entity(String.class).isEqualTo(provider.getId().toString())
                .path("services.totalElements").entity(Long.class).isEqualTo(1L);
    }

    @Test
    void createsThroughTheExistingProviderServiceAndIsVisibleOverRest() throws Exception {
        var response = tester(provider).document("""
                mutation { createService(input:{title:"  Nova limpeza  ", price:"190.50", category:LIMPEZA}) { id title price providerId active } }
                """).execute();
        response.errors().verify();
        long id = Long.parseLong(response.path("createService.id").entity(String.class).get());
        response.path("createService.providerId").entity(String.class).isEqualTo(provider.getId().toString());
        mvc.perform(get("/api/services/" + id).with(as(client))).andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(190.5)).andExpect(jsonPath("$.title").value("Nova limpeza"));
    }

    @Test
    void rejectsClientServiceCreationWithAFieldErrorAndNoWrite() {
        long before = offerings.count();
        error(tester(client).document("""
                mutation { createService(input:{title:"Inválido", price:"1.00", category:LIMPEZA}) { id } }
                """).execute(), "FORBIDDEN");
        assertThat(offerings.count()).isEqualTo(before);
    }

    @Test
    void catalogFiltersActiveRowsAndProviderPagesNeverExposeAccounts() {
        offering(provider, ServiceCategory.ENCANAMENTO, true);
        offering(provider, ServiceCategory.LIMPEZA, false);
        var inactive = user("inactive", UserRole.PROVIDER);
        inactive.setActive(false); users.saveAndFlush(inactive);
        offering(inactive, ServiceCategory.LIMPEZA, true);
        execute(client, """
                query($provider:ID!){ services(category:LIMPEZA,providerId:$provider,page:{size:1}){content{id} totalElements}
                providers(page:{size:100}){content{id fullName avatarUrl} totalElements} }
                """, Map.of("provider",provider.getId().toString())).errors().verify()
                .path("services.totalElements").entity(Long.class).isEqualTo(1L)
                .path("providers.totalElements").entity(Long.class).isEqualTo(2L);
        execute(client,"query($id:ID!){provider(id:$id){id fullName}}",Map.of("id",provider.getId().toString()))
                .errors().verify().path("provider.id").entity(String.class).isEqualTo(provider.getId().toString());
        error(execute(client,"query($id:ID!){provider(id:$id){id}}",Map.of("id",inactive.getId().toString())),"NOT_FOUND");
        error(execute(client,"query($id:ID!){provider(id:$id){id}}",Map.of("id",client.getId().toString())),"NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"email","passwordHash","phone","role","active"})
    void sensitiveProviderFieldsAreNotInTheSchema(String field) throws Exception {
        rawError("{providers{content{id " + field + "}}}","BAD_REQUEST");
    }

    @ParameterizedTest @ValueSource(strings = {
            "{services(page:{page:-1}){content{id}}}",
            "{services(page:{size:0}){content{id}}}",
            "{services(page:{size:101}){content{id}}}",
            "{requests(page:{page:2147483647,size:100}){content{id}}}",
            "{reviews(page:{size:101}){content{id}}}",
            "{providers(page:{size:101}){content{id}}}",
            "{service(id:\"0\"){id}}",
            "{provider(id:\"-1\"){id}}",
            "{services(providerId:\"-1\"){content{id}}}",
            "{requests(serviceId:\"-1\"){content{id}}}",
            "{reviews(requestId:\"-1\"){content{id}}}"
    })
    void validatesPagingAndIdsAtTheGraphQlBoundary(String document) {
        error(tester(client).document(document).execute(),"BAD_REQUEST");
    }

    @ParameterizedTest @ValueSource(strings = {
            "{service(id:\"not-an-id\"){id}}",
            "{services(category:INVALID){content{id}}}",
            "mutation{createService(input:{title:\"T\",price:1.5,category:LIMPEZA}){id}}",
            "mutation{createService(input:{title:\"T\",price:\"1E1000000000\",category:LIMPEZA}){id}}",
            "mutation{createReview(input:{requestId:\"1\",rating:1.5}){id}}",
            "mutation{createRequest(input:{serviceId:\"1\",scheduledAt:\"2027-99-99T99:99:99\"}){id}}",
            "{ this is malformed"
    })
    void returnsSanitizedGraphQlErrorsForMalformedOperations(String document) throws Exception {
        rawError(document,"BAD_REQUEST");
    }

    @ParameterizedTest @ValueSource(strings = {
            "title:\"\",price:\"1.00\",category:LIMPEZA",
            "title:\"<b>HTML</b>\",price:\"1.00\",category:LIMPEZA",
            "title:\"Teste\",price:\"0.00\",category:LIMPEZA",
            "title:\"Teste\",price:\"-1.00\",category:LIMPEZA",
            "title:\"Teste\",price:\"1.234\",category:LIMPEZA",
            "title:\"Teste\",price:\"100000000.00\",category:LIMPEZA"
    })
    void reusesTheServiceBeanValidation(String fields) {
        long before = offerings.count();
        error(tester(provider).document("mutation{createService(input:{" + fields + "}){id}}").execute(),"BAD_REQUEST");
        assertThat(offerings.count()).isEqualTo(before);
    }

    @Test
    void patchUpdatesOnlyEditableFieldsAndEnforcesOwner() {
        error(execute(otherProvider,"mutation($id:ID!){updateService(id:$id,input:{price:\"200.00\"}){id}}",Map.of("id",offering.getId().toString())),"FORBIDDEN");
        error(execute(client,"mutation($id:ID!){updateService(id:$id,input:{price:\"200.00\"}){id}}",Map.of("id",offering.getId().toString())),"FORBIDDEN");
        execute(provider,"mutation($id:ID!){updateService(id:$id,input:{price:\"200.00\"}){id price title providerId}}",Map.of("id",offering.getId().toString()))
                .errors().verify().path("updateService.price").entity(String.class).isEqualTo("200.00")
                .path("updateService.title").entity(String.class).isEqualTo("Serviço GraphQL")
                .path("updateService.providerId").entity(String.class).isEqualTo(provider.getId().toString());
        error(execute(provider,"mutation($id:ID!){updateService(id:$id,input:{}){id}}",Map.of("id",offering.getId().toString())),"BAD_REQUEST");
    }

    @Test
    void administrativeDelegationMatchesRestPolicy() {
        error(tester(admin).document("mutation{createService(input:{title:\"T\",price:\"10.00\",category:LIMPEZA}){id}}").execute(),"BAD_REQUEST");
        execute(admin,"mutation($owner:ID!){createService(providerId:$owner,input:{title:\"T\",price:\"10.00\",category:LIMPEZA}){providerId}}",
                Map.of("owner",provider.getId().toString())).errors().verify()
                .path("createService.providerId").entity(String.class).isEqualTo(provider.getId().toString());
        error(execute(provider,"mutation($owner:ID!){createService(providerId:$owner,input:{title:\"T\",price:\"10.00\",category:LIMPEZA}){id}}",
                Map.of("owner",otherProvider.getId().toString())),"FORBIDDEN");
        execute(admin,"mutation($service:ID!,$client:ID!){createRequest(clientId:$client,input:{serviceId:$service}){clientId totalPrice status}}",
                Map.of("service",offering.getId().toString(),"client",client.getId().toString())).errors().verify()
                .path("createRequest.clientId").entity(String.class).isEqualTo(client.getId().toString())
                .path("createRequest.totalPrice").entity(String.class).isEqualTo("150.00")
                .path("createRequest.status").entity(String.class).isEqualTo("PENDING");
        error(execute(client,"mutation($service:ID!,$client:ID!){createRequest(clientId:$client,input:{serviceId:$service}){id}}",
                Map.of("service",offering.getId().toString(),"client",stranger.getId().toString())),"FORBIDDEN");
        error(execute(admin,"mutation($service:ID!,$client:ID!){createRequest(clientId:$client,input:{serviceId:$service}){id}}",
                Map.of("service",offering.getId().toString(),"client",provider.getId().toString())),"CONFLICT");
    }

    @Test
    void interoperatesWithRestAcrossBookingPriceStatusReviewAndLogicalDeletion() throws Exception {
        var creation = execute(client,"mutation($id:ID!){createRequest(input:{serviceId:$id,notes:\"Portaria\"}){id clientId totalPrice status}}",
                Map.of("id",offering.getId().toString()));
        creation.errors().verify();
        long requestId = Long.parseLong(creation.path("createRequest.id").entity(String.class).get());
        creation.path("createRequest.clientId").entity(String.class).isEqualTo(client.getId().toString());
        mvc.perform(patch("/api/services/" + offering.getId()).with(as(provider)).contentType(MediaType.APPLICATION_JSON).content("{\"price\":250}"))
                .andExpect(status().isOk());
        execute(client,"query($id:ID!){request(id:$id){totalPrice notes}}",Map.of("id",Long.toString(requestId)))
                .errors().verify().path("request.totalPrice").entity(String.class).isEqualTo("150.00");
        changeStatus(provider,requestId,"ACCEPTED").errors().verify();
        changeStatus(admin,requestId,"IN_PROGRESS").errors().verify();
        mvc.perform(patch("/api/requests/" + requestId + "/status").with(as(provider)).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
        var review = execute(client,"mutation($id:ID!){createReview(input:{requestId:$id,rating:5,comment:\"Ótimo\"}){id reviewerId}}",
                Map.of("id",Long.toString(requestId)));
        review.errors().verify();
        long reviewId = Long.parseLong(review.path("createReview.id").entity(String.class).get());
        review.path("createReview.reviewerId").entity(String.class).isEqualTo(client.getId().toString());
        mvc.perform(get("/api/reviews/" + reviewId).with(as(provider))).andExpect(status().isOk()).andExpect(jsonPath("$.rating").value(5));
        error(execute(client,"mutation($id:ID!){createReview(input:{requestId:$id,rating:4}){id}}",Map.of("id",Long.toString(requestId))),"CONFLICT");
        execute(provider,"mutation($id:ID!){deactivateService(id:$id){id active}}",Map.of("id",offering.getId().toString()))
                .errors().verify().path("deactivateService.active").entity(Boolean.class).isEqualTo(false);
        mvc.perform(get("/api/services/" + offering.getId()).with(as(client))).andExpect(status().isNotFound());
        execute(client,"query($id:ID!){request(id:$id){totalPrice status}}",Map.of("id",Long.toString(requestId)))
                .errors().verify().path("request.status").entity(String.class).isEqualTo("COMPLETED");
        execute(provider,"query($id:ID!){review(id:$id){rating}}",Map.of("id",Long.toString(reviewId)))
                .errors().verify().path("review.rating").entity(Integer.class).isEqualTo(5);
        assertThat(offerings.findById(offering.getId()).orElseThrow().isActive()).isFalse();
        assertThat(requests.existsById(requestId)).isTrue();
        assertThat(reviews.existsById(reviewId)).isTrue();
    }

    @Test
    void privateQueriesRemainScopedAndCanReturnPartialData() {
        var own = requestRow(ServiceRequestStatus.COMPLETED,client);
        var other = requestRow(ServiceRequestStatus.PENDING,stranger);
        var review = reviewRow(own);
        var partial = execute(stranger,"query($service:ID!,$request:ID!){service(id:$service){id} request(id:$request){id notes}}",
                Map.of("service",offering.getId().toString(),"request",own.getId().toString()));
        error(partial,"FORBIDDEN");
        partial.path("service.id").entity(String.class).isEqualTo(offering.getId().toString());
        partial.path("request").valueIsNull();
        execute(client,"query($id:ID!){requests(serviceId:$id){totalElements content{id notes}}}",Map.of("id",offering.getId().toString()))
                .errors().verify().path("requests.totalElements").entity(Long.class).isEqualTo(1L);
        execute(provider,"{requests(status:PENDING){totalElements}}",Map.of()).errors().verify()
                .path("requests.totalElements").entity(Long.class).isEqualTo(1L);
        execute(admin,"query($id:ID!){requests(serviceId:$id){totalElements}}",Map.of("id",offering.getId().toString()))
                .errors().verify().path("requests.totalElements").entity(Long.class).isEqualTo(2L);
        error(execute(stranger,"query($id:ID!){review(id:$id){id}}",Map.of("id",review.getId().toString())),"FORBIDDEN");
        execute(stranger,"query($id:ID!){reviews(requestId:$id){content{id} totalElements}}",Map.of("id",own.getId().toString()))
                .errors().verify().path("reviews.totalElements").entity(Long.class).isEqualTo(0L);
        execute(provider,"query($id:ID!){reviews(serviceId:$id){content{id} totalElements}}",Map.of("id",offering.getId().toString()))
                .errors().verify().path("reviews.totalElements").entity(Long.class).isEqualTo(1L);
        error(changeStatus(stranger,other.getId(),"ACCEPTED"),"FORBIDDEN");
    }

    @ParameterizedTest @EnumSource(value=ServiceRequestStatus.class,names={"PENDING","ACCEPTED","IN_PROGRESS"})
    void openRequestsBlockDeactivationAndCancellationUsesTheExistingActorPolicy(ServiceRequestStatus state) {
        var request = requestRow(state,client);
        error(execute(provider,"mutation($id:ID!){deactivateService(id:$id){id}}",Map.of("id",offering.getId().toString())),"CONFLICT");
        if (state == ServiceRequestStatus.IN_PROGRESS) {
            error(changeStatus(client,request.getId(),"CANCELLED"),"FORBIDDEN");
            changeStatus(provider,request.getId(),"CANCELLED").errors().verify();
        } else changeStatus(client,request.getId(),"CANCELLED").errors().verify();
        error(changeStatus(admin,request.getId(),"ACCEPTED"),"CONFLICT");
    }

    @Test
    void rejectsSkippedRepeatedAndFinalTransitions() {
        var request = requestRow(ServiceRequestStatus.PENDING,client);
        error(changeStatus(client,request.getId(),"ACCEPTED"),"FORBIDDEN");
        error(changeStatus(provider,request.getId(),"COMPLETED"),"CONFLICT");
        error(changeStatus(provider,request.getId(),"PENDING"),"CONFLICT");
        changeStatus(provider,request.getId(),"ACCEPTED").errors().verify();
        changeStatus(provider,request.getId(),"IN_PROGRESS").errors().verify();
        changeStatus(provider,request.getId(),"COMPLETED").errors().verify();
        error(changeStatus(admin,request.getId(),"CANCELLED"),"CONFLICT");
    }

    @ParameterizedTest @EnumSource(value=ServiceRequestStatus.class,names={"PENDING","ACCEPTED","IN_PROGRESS","CANCELLED"})
    void refusesReviewsOfUnfinishedOrCancelledRequests(ServiceRequestStatus state) {
        var request = requestRow(state,client);
        error(execute(client,"mutation($id:ID!){createReview(input:{requestId:$id,rating:5}){id}}",Map.of("id",request.getId().toString())),"CONFLICT");
        assertThat(reviews.findByRequestId(request.getId())).isEmpty();
    }

    @ParameterizedTest @ValueSource(ints={0,6,-1})
    void validatesReviewRatings(int rating) {
        var request = requestRow(ServiceRequestStatus.COMPLETED,client);
        error(execute(client,"mutation($id:ID!,$rating:Int!){createReview(input:{requestId:$id,rating:$rating}){id}}",
                Map.of("id",request.getId().toString(),"rating",rating)),"BAD_REQUEST");
    }

    @Test
    void reviewOwnerAndAdminAttributionMatchRest() {
        var request = requestRow(ServiceRequestStatus.COMPLETED,client);
        String document = "mutation($id:ID!){createReview(input:{requestId:$id,rating:5}){reviewerId}}";
        error(execute(stranger,document,Map.of("id",request.getId().toString())),"FORBIDDEN");
        error(execute(provider,document,Map.of("id",request.getId().toString())),"FORBIDDEN");
        execute(admin,document,Map.of("id",request.getId().toString())).errors().verify()
                .path("createReview.reviewerId").entity(String.class).isEqualTo(client.getId().toString());
    }

    @Test
    void validatesLongTextsSchedulesAndReadonlyIdentityFields() throws Exception {
        var longComment = "x".repeat(2001);
        var request = requestRow(ServiceRequestStatus.COMPLETED,client);
        error(execute(client,"mutation($id:ID!,$comment:String!){createReview(input:{requestId:$id,rating:5,comment:$comment}){id}}",
                Map.of("id",request.getId().toString(),"comment",longComment)),"BAD_REQUEST");
        error(execute(client,"mutation($id:ID!){createRequest(input:{serviceId:$id,scheduledAt:\"2000-01-01T10:00:00\"}){id}}",
                Map.of("id",offering.getId().toString())),"BAD_REQUEST");
        rawError("mutation{createRequest(input:{serviceId:\"" + offering.getId() + "\",clientId:\"" + stranger.getId() + "\",totalPrice:\"1.00\"}){id}}","BAD_REQUEST");
        rawError("mutation{updateService(id:\"" + offering.getId() + "\",input:{providerId:\"" + otherProvider.getId() + "\"}){id}}","BAD_REQUEST");
    }

    @Test
    void unavailableResourcesReturnGraphQlNotFoundAndCannotBeBooked() {
        error(tester(client).document("{service(id:\"999999999\"){id}}").execute(),"NOT_FOUND");
        error(tester(client).document("{request(id:\"999999999\"){id}}").execute(),"NOT_FOUND");
        error(tester(client).document("{review(id:\"999999999\"){id}}").execute(),"NOT_FOUND");
        offering.setActive(false); offerings.saveAndFlush(offering);
        error(execute(client,"mutation($id:ID!){createRequest(input:{serviceId:$id}){id}}",Map.of("id",offering.getId().toString())),"NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings={"anonymous","basic","invalid","inactive"})
    void requiresTheSameActiveBearerAuthenticationAsRest(String variant) throws Exception {
        var builder = post("/graphql").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"{services{content{id}}}\"}");
        if (variant.equals("basic")) builder.with(httpBasic(client.getEmail(),"senha123"));
        if (variant.equals("invalid")) builder.header("Authorization","Bearer invalid");
        if (variant.equals("inactive")) {
            String token = token(client); client.setActive(false); users.saveAndFlush(client);
            builder.header("Authorization","Bearer " + token);
        }
        mvc.perform(builder).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.errors[0].extensions.code").value("UNAUTHORIZED"))
                .andExpect(header().string("WWW-Authenticate","Bearer realm=\"ServiceHub\""));
    }

    @Test
    void currentDatabaseRoleOverridesAnOlderToken() throws Exception {
        String token = token(provider);
        provider.setRole(UserRole.CLIENT); users.saveAndFlush(provider);
        var response = mvc.perform(post("/graphql").header("Authorization","Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"mutation{createService(input:{title:\\\"T\\\",price:\\\"1.00\\\",category:LIMPEZA}){id}}\"}")).andReturn();
        if (response.getRequest().isAsyncStarted()) response = mvc.perform(asyncDispatch(response)).andReturn();
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(response.getResponse().getContentAsByteArray()).path("errors").get(0).path("extensions").path("code").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    void limitsDepthComplexityAndBatchedWritesBeforeAnyMutation() {
        String aliases = java.util.stream.IntStream.range(0,11).mapToObj(i -> "a" + i + ":services{content{id}}").collect(java.util.stream.Collectors.joining(" "));
        error(tester(client).document("{" + aliases + "}").execute(),"QUERY_LIMIT_EXCEEDED");
        String deep = "{__type(name:\"Service\"){fields{type{" + "ofType{".repeat(10) + "name" + "}".repeat(10) + "}}}}";
        error(tester(client).document(deep).execute(),"QUERY_LIMIT_EXCEEDED");
        String operation = "services(page:{size:100}){content{id title description price category providerId providerName active createdAt updatedAt provider{id fullName avatarUrl}}}";
        error(tester(client).document("{a:" + operation + " b:" + operation + " c:" + operation + "}").execute(),"QUERY_LIMIT_EXCEEDED");
        long before = offerings.count();
        String mutation = "createService(input:{title:\"Batch\",price:\"1.00\",category:LIMPEZA}){id}";
        error(tester(provider).document("mutation{a:" + mutation + " b:" + mutation + "}").execute(),"QUERY_LIMIT_EXCEEDED");
        assertThat(offerings.count()).isEqualTo(before);
    }

    @Test
    void rejectsJsonBatchArraysAndBoundsTheFullBodyIncludingVariables() throws Exception {
        var batch = rawBody("[{\"query\":\"{services{content{id}}}\"},{\"query\":\"{providers{content{id}}}\"}]",client);
        assertThat(batch.getResponse().getStatus()).isEqualTo(400);
        var oversized = rawBody(json.writeValueAsString(Map.of("query","{services{content{id}}}","variables",Map.of("padding","x".repeat(65537)))),client);
        assertThat(oversized.getResponse().getStatus()).isEqualTo(413);
        assertThat(json.readTree(oversized.getResponse().getContentAsByteArray()).path("errors").get(0).path("extensions").path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        error(tester(client).document("{services{content{id}}}" + " ".repeat(16384)).execute(),"QUERY_LIMIT_EXCEEDED");
    }

    @Test
    void allowsAuthenticatedDevelopmentIntrospectionAndKeepsAll21Categories() {
        tester(client).document("{__schema{queryType{name}} __type(name:\"ServiceCategory\"){enumValues{name}}}").execute()
                .errors().verify().path("__schema.queryType.name").entity(String.class).isEqualTo("Query")
                .path("__type.enumValues[*].name").entityList(String.class)
                .containsExactly(Arrays.stream(ServiceCategory.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void providerProjectionAndServiceRelationsDoNotCauseNPlusOneQueries() {
        for (int i=0;i<6;i++) offering(user("nplus" + i,UserRole.PROVIDER),ServiceCategory.LIMPEZA,true);
        em.flush(); em.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled(); statistics.setStatisticsEnabled(true); statistics.clear();
        try {
            tester(client).document("{services(page:{size:5}){content{id providerName provider{id fullName avatarUrl}} totalElements}}")
                    .execute().errors().verify().path("services.content").entityList(Object.class).hasSize(5);
            assertThat(statistics.getPrepareStatementCount()).as("JWT, página com JOIN, COUNT e um lote de prestadores")
                    .isLessThanOrEqualTo(4);
        } finally { statistics.setStatisticsEnabled(previous); }
    }

    private GraphQlTester.Response execute(User actor, String document, Map<String,?> variables) {
        var request = tester(actor).document(document);
        variables.forEach(request::variable);
        return request.execute();
    }
    private GraphQlTester.Response changeStatus(User actor, long requestId, String status) {
        return execute(actor,"mutation($id:ID!,$status:RequestStatus!){changeRequestStatus(id:$id,input:{status:$status}){id status totalPrice}}",
                Map.of("id",Long.toString(requestId),"status",status));
    }

    private User user(String prefix, UserRole role) {
        var user = new User(); user.setFullName(prefix); user.setEmail(prefix + "@graphql.test");
        user.setPasswordHash(HASH); user.setRole(role); return users.saveAndFlush(user);
    }
    private ServiceOffering offering(User owner, ServiceCategory category, boolean active) {
        var row = new ServiceOffering(); row.setTitle("Serviço GraphQL"); row.setDescription("Descrição");
        row.setProvider(owner); row.setCategory(category); row.setActive(active); row.setPrice(new BigDecimal("150.00"));
        return offerings.saveAndFlush(row);
    }
    private ServiceRequest requestRow(ServiceRequestStatus status, User owner) {
        var row = new ServiceRequest(); row.setClient(owner); row.setService(offering); row.setStatus(status);
        row.setTotalPrice(offering.getPrice()); row.setNotes("Observação privada"); return requests.saveAndFlush(row);
    }
    private Review reviewRow(ServiceRequest request) {
        var row = new Review(); row.setRequest(request); row.setReviewer(request.getClient()); row.setRating((short)5);
        return reviews.saveAndFlush(row);
    }
    private String token(User user) { return jwtService.generateToken(new ServiceHubPrincipal(user)).getTokenValue(); }
    private RequestPostProcessor as(User user) {
        String token = token(user);
        return request -> { request.addHeader("Authorization", "Bearer " + token); return request; };
    }
    private HttpGraphQlTester tester(User user) {
        return HttpGraphQlTester.builder(MockMvcWebTestClient.bindTo(mvc))
                .url("/graphql").headers(headers -> headers.setBearerAuth(token(user))).build();
    }
    private void error(GraphQlTester.Response response, String code) {
        response.errors().satisfy(errors -> {
            assertThat(errors).isNotEmpty();
            assertThat(errors).anySatisfy(item -> assertThat(item.getExtensions()).containsEntry("code", code));
            assertThat(errors.toString()).doesNotContain("password_hash", "SELECT ", HASH);
        });
    }
    private MvcResult raw(String document, User actor) throws Exception {
        return rawBody(json.writeValueAsString(Map.of("query", document)), actor);
    }
    private MvcResult rawBody(String body, User actor) throws Exception {
        var builder = post("/graphql").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body);
        if (actor != null) builder.with(as(actor));
        var result = mvc.perform(builder).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }
    private void rawError(String document, String code) throws Exception {
        var response = raw(document, client);
        assertThat(response.getResponse().getStatus()).isIn(200, 400);
        var payload = json.readTree(response.getResponse().getContentAsByteArray());
        assertThat(payload.path("errors").isArray()).isTrue();
        assertThat(payload.path("errors").get(0).path("extensions").path("code").asString()).isEqualTo(code);
    }
}
