package com.servicehub.security;

import com.servicehub.JwtTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"servicehub.auth-guard.enabled=true", "servicehub.auth-guard.login-ip-limit=2",
        "servicehub.auth-guard.login-account-limit=2", "servicehub.auth-guard.login-global-limit=100",
        "servicehub.auth-guard.registration-ip-limit=1", "servicehub.auth-guard.registration-global-limit=100"})
@ActiveProfiles("test")
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Tag("web")
class AuthAdmissionWebTests extends JwtTestSupport {
    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean(name="admissionClock") java.time.Clock admissionClock;
    @org.junit.jupiter.api.BeforeEach void fixedAdmissionWindow() {
        org.mockito.Mockito.when(admissionClock.instant()).thenReturn(java.time.Instant.parse("2026-10-27T12:00:00Z"));
    }
    private String email() { return UUID.randomUUID() + "@admission.invalid"; }
    @Test void limitsUnknownAndKnownAccountCandidatesWithoutTrustingForwardedHeaders() throws Exception {
        String email=email(); String origin="192.0.2.10";
        for(int index=0;index<2;index++) mvc.perform(loginRequest(email,"InvalidCandidate").with(request->{request.setRemoteAddr(origin);return request;})
                .header("X-Forwarded-For","198.51.100."+index)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(email,"InvalidCandidate").with(request->{request.setRemoteAddr(origin);return request;})
                .header("X-Forwarded-For","203.0.113.99")).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @Test void accountsAreLimitedAcrossDifferentOriginsAfterNormalization() throws Exception {
        String email=email();
        for(int index=0;index<2;index++){String origin="192.0.2."+(30+index);
            mvc.perform(loginRequest(email,"InvalidCandidate").with(request->{request.setRemoteAddr(origin);return request;})).andExpect(status().isUnauthorized());}
        mvc.perform(loginRequest(" ".repeat(300)+email.toUpperCase(java.util.Locale.ROOT)+" ".repeat(300),"InvalidCandidate").with(request->{request.setRemoteAddr("192.0.2.32");return request;}))
                .andExpect(status().isTooManyRequests());
    }
    @Test void registrationHasASeparateOriginBudget() throws Exception {
        var first=Map.of("fullName","Admission Fixture","email",email(),"password",UUID.randomUUID().toString());
        var second=Map.of("fullName","Admission Fixture","email",email(),"password",UUID.randomUUID().toString());
        mvc.perform(post("/api/users").contentType("application/json").content(json.writeValueAsString(first))
                .with(request->{request.setRemoteAddr("192.0.2.80");return request;})).andExpect(status().isCreated());
        mvc.perform(post("/api/users").contentType("application/json").content(json.writeValueAsString(second))
                .with(request->{request.setRemoteAddr("192.0.2.80");return request;})).andExpect(status().isTooManyRequests());
    }
    @Test void oversizedBodiesAreRejectedBeforeAuthenticationWorkAndSmallMalformedBodiesRemain400() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("x".repeat(98304))).andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{")
                .with(request->{request.setRemoteAddr("192.0.2.90");return request;})).andExpect(status().isBadRequest());
        mvc.perform(post("/api/services").contentType("application/json").content("x".repeat(98304))).andExpect(status().isUnauthorized());
    }
}
