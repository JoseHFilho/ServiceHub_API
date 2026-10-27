package com.servicehub.service;

import com.servicehub.model.dto.LoginRequest;
import com.servicehub.model.dto.TokenResponse;
import com.servicehub.security.DatabaseUserDetailsService;
import com.servicehub.security.JwtService;
import com.servicehub.security.ServiceHubPrincipal;
import java.time.Duration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final DatabaseUserDetailsService users;
    private final JwtService jwt;
    public AuthService(AuthenticationManager authenticationManager, DatabaseUserDetailsService users, JwtService jwt) {
        this.authenticationManager = authenticationManager; this.users = users; this.jwt = jwt;
    }
    public TokenResponse login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        var authenticated = (ServiceHubPrincipal) authentication.getPrincipal();
        var token = jwt.generateToken(users.loadActiveUserById(authenticated.id()));
        return new TokenResponse(token.getTokenValue(), "Bearer",
                Duration.between(token.getIssuedAt(), token.getExpiresAt()).toSeconds(), token.getExpiresAt());
    }
}
