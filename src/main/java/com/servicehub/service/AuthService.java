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
    private final com.servicehub.security.PasswordHashService passwords;
    public AuthService(AuthenticationManager authenticationManager, DatabaseUserDetailsService users, JwtService jwt, com.servicehub.security.PasswordHashService passwords) {
        this.authenticationManager = authenticationManager; this.users = users; this.jwt = jwt;
        this.passwords = passwords;
    }
    public TokenResponse login(LoginRequest request) {
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));
        var authenticated = (ServiceHubPrincipal) authentication.getPrincipal();
        var current = users.loadActiveUserById(authenticated.id());
        // Recheck a changed credential snapshot; a legitimate rehash can also change its version.
        if(current.credentialVersion()!=authenticated.credentialVersion() && !passwords.matches(request.password(),current.getPassword())) {
            throw new org.springframework.security.authentication.BadCredentialsException("Credenciais inválidas.");
        }
        var token = jwt.generateToken(current);
        return new TokenResponse(token.getTokenValue(), "Bearer",
                Duration.between(token.getIssuedAt(), token.getExpiresAt()).toSeconds(), token.getExpiresAt());
    }
}
