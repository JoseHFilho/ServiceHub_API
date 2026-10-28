package com.servicehub.security;

import com.servicehub.model.UserRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService implements JwtDecoder {
    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration ttl;
    private final String issuer;
    private final Clock clock;

    @Autowired
    public JwtService(JwtProperties properties) { this(properties, Clock.systemUTC()); }

    public JwtService(JwtProperties properties, Clock clock) {
        byte[] key = properties.secretBytes();
        ttl = properties.getTtl();
        if (ttl == null || ttl.compareTo(Duration.ofMinutes(1)) < 0 || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalStateException("JWT_TTL deve estar entre 1 minuto e 1 hora.");
        }
        issuer = properties.getIssuer();
        if (issuer == null || issuer.isBlank()) throw new IllegalStateException("JWT_ISSUER é obrigatório.");
        this.clock = clock;
        var secretKey = new SecretKeySpec(key, "HmacSHA256");
        encoder = NimbusJwtEncoder.withSecretKey(secretKey).algorithm(MacAlgorithm.HS256).build();
        decoder = NimbusJwtDecoder.withSecretKey(secretKey).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(issuer), this::validateClaims));
    }

    public Jwt generateToken(ServiceHubPrincipal principal) {
        if (!principal.isEnabled() || principal.id() == null || principal.id() <= 0) {
            throw new BadCredentialsException("Credenciais inválidas.");
        }
        Instant now = clock.instant();
        var roles = principal.getAuthorities().stream().map(authority -> authority.getAuthority().substring(5)).toList();
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(principal.id().toString())
                .issuedAt(now).expiresAt(now.plus(ttl)).claim("roles", roles).claim("cv", principal.credentialVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims));
    }

    @Override
    public Jwt decode(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) throw new BadJwtException("Token inválido.");
        return decoder.decode(token);
    }

    public long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new BadJwtException("Token inválido."); }
    }
    public long credentialVersion(Jwt jwt) {
        if(!jwt.getClaims().containsKey("cv")) return 0;
        Object value=jwt.getClaims().get("cv");
        if((value instanceof Long || value instanceof Integer) && ((Number)value).longValue()>=0) return ((Number)value).longValue();
        throw new BadJwtException("Token inválido.");
    }

    private OAuth2TokenValidatorResult validateClaims(Jwt jwt) {
        try {
            credentialVersion(jwt);
            Instant issued = jwt.getIssuedAt();
            Instant expires = jwt.getExpiresAt();
            Object roles = jwt.getClaims().get("roles");
            if (!"JWT".equals(jwt.getHeaders().get("typ")) || jwt.getSubject() == null
                    || !jwt.getSubject().matches("[1-9][0-9]{0,18}") || userId(jwt) <= 0
                    || issued == null || expires == null || !expires.isAfter(issued)
                    || !expires.isAfter(clock.instant()) || issued.isAfter(clock.instant().plusSeconds(30))
                    || Duration.between(issued, expires).compareTo(Duration.ofHours(1)) > 0
                    || !(roles instanceof List<?> values) || values.size() != 1
                    || !(values.getFirst() instanceof String role)) return invalidClaims();
            UserRole.valueOf(role);
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) { return invalidClaims(); }
    }

    private OAuth2TokenValidatorResult invalidClaims() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token inválido.", null));
    }
}
