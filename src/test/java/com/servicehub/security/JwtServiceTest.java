package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.util.ReflectionTestUtils;

class JwtServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void issuesMinimalSignedClaimsWithTheConfiguredLifetime() {
        JwtProperties properties = properties(); properties.setTtl(Duration.ofMinutes(5));
        JwtService service = new JwtService(properties, CLOCK);
        var principal = principal();
        Jwt issued = service.generateToken(principal);
        Jwt verified = service.decode(issued.getTokenValue());
        assertThat(verified.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(service.userId(verified)).isEqualTo(7L);
        assertThat(verified.getIssuedAt()).isEqualTo(NOW);
        assertThat(verified.getExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(verified.getClaimAsStringList("roles")).containsExactly("CLIENT");
        assertThat(verified.getClaims()).containsOnlyKeys("iss", "sub", "iat", "exp", "roles");
    }

    @Test
    void rejectsExpiredTokensAndTokensIssuedInTheFuture() {
        JwtProperties properties = properties();
        var current = new JwtService(properties, CLOCK);
        var past = new JwtService(properties, Clock.fixed(NOW.minusSeconds(1800), ZoneOffset.UTC));
        var future = new JwtService(properties, Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC));
        assertThatThrownBy(() -> current.decode(past.generateToken(principal()).getTokenValue())).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> current.decode(future.generateToken(principal()).getTokenValue())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsWrongSignaturesAndUnsignedTokens() {
        var current = new JwtService(properties(), CLOCK);
        var other = new JwtService(properties(), CLOCK);
        String token = other.generateToken(principal()).getTokenValue();
        assertThatThrownBy(() -> current.decode(token)).isInstanceOf(JwtException.class);
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"7\"}".getBytes()) + ".";
        assertThatThrownBy(() -> current.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat", "sub", "roles", "invalid-sub", "invalid-role"})
    void rejectsSignedTokensWithoutRequiredValidClaims(String scenario) {
        JwtProperties properties = properties();
        JwtService service = new JwtService(properties, CLOCK);
        var claims = JwtClaimsSet.builder().issuer(properties.getIssuer());
        if (!scenario.equals("sub")) claims.subject(scenario.equals("invalid-sub") ? "0" : "7");
        if (!scenario.equals("iat")) claims.issuedAt(NOW);
        if (!scenario.equals("exp")) claims.expiresAt(NOW.plusSeconds(900));
        if (!scenario.equals("roles")) claims.claim("roles", List.of(scenario.equals("invalid-role") ? "ROOT" : "CLIENT"));
        var encoder = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(Base64.getDecoder().decode(properties.getSecret()), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
        assertThatThrownBy(() -> service.decode(token)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bad-base64!", "c2hvcnQ="})
    void refusesMissingMalformedAndWeakSigningKeys(String secret) {
        JwtProperties properties = properties(); properties.setSecret(secret);
        assertThatThrownBy(() -> new JwtService(properties, CLOCK)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThat(properties.toString()).doesNotContain("bad-base64", "c2hvcnQ");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 30, 3601, -1})
    void refusesUnsafeExpiryConfiguration(long seconds) {
        JwtProperties properties = properties(); properties.setTtl(Duration.ofSeconds(seconds));
        assertThatThrownBy(() -> new JwtService(properties, CLOCK)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_TTL");
    }

    private JwtProperties properties() {
        byte[] key = new byte[64]; new SecureRandom().nextBytes(key);
        JwtProperties properties = new JwtProperties(); properties.setSecret(Base64.getEncoder().encodeToString(key));
        return properties;
    }
    private ServiceHubPrincipal principal() {
        User user = new User(); ReflectionTestUtils.setField(user, "id", 7L);
        user.setEmail("private@example.com"); user.setPasswordHash("private-hash"); user.setRole(UserRole.CLIENT);
        return new ServiceHubPrincipal(user);
    }
}
