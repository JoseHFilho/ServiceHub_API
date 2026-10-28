package com.servicehub.security;

import com.servicehub.model.User;
import com.servicehub.model.dto.LoginRequest;
import com.servicehub.service.AuthService;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class AuthCredentialRaceTest {
    private ServiceHubPrincipal principal(long version,String hash) {
        var user=new User();ReflectionTestUtils.setField(user,"id",7L);user.setEmail("race@credentials.invalid");user.setPasswordHash(hash);user.setCredentialVersion(version);
        return new ServiceHubPrincipal(user);
    }
    @Test void doesNotMintTheNewVersionForAPasswordVerifiedBeforeAConcurrentChange() {
        var manager=mock(AuthenticationManager.class);var users=mock(DatabaseUserDetailsService.class);
        var jwt=mock(JwtService.class);var passwords=mock(PasswordHashService.class);
        var verified=principal(0,"previous-fixture");var current=principal(1,"changed-fixture");
        when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(verified,null,verified.getAuthorities()));
        when(users.loadActiveUserById(7L)).thenReturn(current);
        when(passwords.matches("candidate","changed-fixture")).thenReturn(false);
        var service=new AuthService(manager,users,jwt,passwords);
        assertThatThrownBy(()->service.login(new LoginRequest("race@credentials.invalid","candidate"))).isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(jwt);
    }
    @Test void acceptsALegitimateHashUpgradeAfterRecheckingTheCurrentCredential() {
        var manager=mock(AuthenticationManager.class);var users=mock(DatabaseUserDetailsService.class);
        var jwt=mock(JwtService.class);var passwords=mock(PasswordHashService.class);
        var verified=principal(0,"legacy-fixture");var current=principal(1,"upgraded-fixture");
        when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(verified,null,verified.getAuthorities()));
        when(users.loadActiveUserById(7L)).thenReturn(current);
        when(passwords.matches("candidate","upgraded-fixture")).thenReturn(true);
        when(jwt.generateToken(current)).thenReturn(Jwt.withTokenValue("fixture-token").header("alg","HS256").issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(900)).build());
        var response=new AuthService(manager,users,jwt,passwords).login(new LoginRequest("race@credentials.invalid","candidate"));
        assertThat(response.expiresIn()).isEqualTo(900);verify(jwt).generateToken(current);
    }
}
