package com.servicehub;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Transactional
@Tag("web")
class CredentialVersionIntegrationTests extends JwtTestSupport {
    @Autowired UserRepository users;
    @Autowired PasswordHashService passwords;
    @Autowired JwtProperties jwtProperties;
    @Autowired MockMvc mvc;
    private User user(String hash) {
        var user=new User();user.setFullName("Credential Fixture");user.setEmail(UUID.randomUUID()+"@credentials.invalid");user.setPasswordHash(hash);
        return users.saveAndFlush(user);
    }
    private String login(User user,String password) throws Exception {
        var response=mvc.perform(loginRequest(user.getEmail(),password)).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("accessToken").asString();
    }
    @Test void passwordChangeRejectsAllOlderTokensInRestAndGraphQlButKeepsOtherAccountsValid() throws Exception {
        String password=UUID.randomUUID().toString(),replacement=UUID.randomUUID().toString();
        var user=user(passwords.hash(password));var other=user(passwords.hash(password));
        String old=login(user,password),secondOld=login(user,password),otherToken=login(other,password);
        mvc.perform(patch("/api/users/"+user.getId()).header("Authorization","Bearer "+old).contentType("application/json")
                .content(json.writeValueAsString(Map.of("password",replacement,"credentialVersion",0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialVersion").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
        for(String token:List.of(old,secondOld)) {
            mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/graphql").header("Authorization","Bearer "+token).contentType("application/json").content("{\"query\":\"{services{totalElements}}\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(loginRequest(user.getEmail(),password)).andExpect(status().isUnauthorized());
        String fresh=login(user,replacement);
        assertThat(jwtService.credentialVersion(jwtService.decode(fresh))).isEqualTo(1);
        mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+fresh)).andExpect(status().isOk());
        mvc.perform(get("/api/users/"+other.getId()).header("Authorization","Bearer "+otherToken)).andExpect(status().isOk());
    }
    @Test void preVersionTokensRemainValidOnlyUntilThatAccountsFirstCredentialChange() throws Exception {
        String password=UUID.randomUUID().toString();var user=user(passwords.hash(password));
        var encoder=NimbusJwtEncoder.withSecretKey(new SecretKeySpec(jwtProperties.secretBytes(),"HmacSHA256")).algorithm(MacAlgorithm.HS256).build();
        Instant now=Instant.now();
        String legacy=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder().issuer(jwtProperties.getIssuer()).subject(user.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(300)).claim("roles",List.of("CLIENT")).build())).getTokenValue();
        mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+legacy)).andExpect(status().isOk());
        mvc.perform(patch("/api/users/"+user.getId()).header("Authorization","Bearer "+legacy).contentType("application/json")
                .content(json.writeValueAsString(Map.of("password",UUID.randomUUID().toString())))).andExpect(status().isOk());
        mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+legacy)).andExpect(status().isUnauthorized());
    }
    @Test void legacyPbkdfUpgradeProducesAUsableTokenAndDoesNotRevokeItOnTheNextLogin() throws Exception {
        String password=UUID.randomUUID().toString();byte[] salt=new byte[16];new java.security.SecureRandom().nextBytes(salt);
        var spec=new PBEKeySpec(password.toCharArray(),salt,65536,256);
        String legacy;
        try {legacy="pbkdf2$65536$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        finally {spec.clearPassword();}
        var user=user(legacy);String first=login(user,password);
        assertThat(jwtService.credentialVersion(jwtService.decode(first))).isEqualTo(1);
        String next=login(user,password);
        assertThat(jwtService.credentialVersion(jwtService.decode(next))).isEqualTo(1);
        mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+first)).andExpect(status().isOk());
    }
    @Test void currentRoleAndActivationContinueToOverrideAnOlderRoleClaim() throws Exception {
        String password=UUID.randomUUID().toString();var user=user(passwords.hash(password));String token=login(user,password);
        user.setRole(UserRole.PROVIDER);users.saveAndFlush(user);
        mvc.perform(post("/api/services").header("Authorization","Bearer "+token).contentType("application/json")
                .content("{\"title\":\"Credential Role\",\"price\":10,\"category\":\"LIMPEZA\"}")).andExpect(status().isCreated());
        user.setActive(false);users.saveAndFlush(user);
        mvc.perform(get("/api/users/"+user.getId()).header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
}
