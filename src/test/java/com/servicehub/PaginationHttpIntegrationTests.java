package com.servicehub;

import com.servicehub.model.User;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.PasswordHashService;
import com.servicehub.validation.Pagination;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.address=127.0.0.1")
@ActiveProfiles("test")
@Tag("web")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginationHttpIntegrationTests extends JwtTestSupport {
    @Value("${local.server.port}") int port;
    @Autowired UserRepository users;
    @Autowired PasswordHashService passwords;
    private Long id;
    private String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    @BeforeAll void fixture() {
        String password=UUID.randomUUID().toString();
        var user=new User(); user.setFullName("Pagination Fixture"); user.setEmail(UUID.randomUUID()+"@pagination.invalid");user.setPasswordHash(passwords.hash(password));
        user=users.saveAndFlush(user);id=user.getId();token=tokenFor(user.getEmail(),password);
    }
    @AfterAll void cleanup() { try { if(id!=null) users.deleteById(id); } finally { http.close(); } }
    private HttpResponse<String> get(String path, boolean authenticated) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(authenticated) request.header("Authorization","Bearer "+token);
        return http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(strings={"services","requests","reviews"})
    void rejectsEveryInvalidRangeAndOffsetAs400InsteadOf401(String route) throws Exception {
        for(String parameters:new String[]{"page=-1&size=20","page=0&size=0","page=0&size=101","page=2147483647&size=100","page=21474837&size=100"}) {
            var response=get("/api/"+route+"?"+parameters,true);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).get("message").asString()).isEqualTo(Pagination.INVALID);
            assertThat(response.body()).doesNotContain("Exception","Integer.MAX_VALUE","SQL");
        }
    }
    @ParameterizedTest @ValueSource(strings={"services","requests","reviews"})
    void preservesValidPagesAndRequiresAuthenticationBeforeValidation(String route) throws Exception {
        assertThat(get("/api/"+route+"?page=0&size=100",true).statusCode()).isEqualTo(200);
        assertThat(get("/api/"+route+"?page=2147483647&size=100",false).statusCode()).isEqualTo(401);
    }
    @Test void containerErrorDispatchPreserves404AndDoesNotExposeAnAnonymousBusinessRoute() throws Exception {
        assertThat(get("/api/unknown-pagination-route",true).statusCode()).isEqualTo(404);
        assertThat(get("/api/unknown-pagination-route",false).statusCode()).isEqualTo(401);
    }
}
