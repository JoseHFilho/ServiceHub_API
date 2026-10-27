package com.servicehub.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Uses the signed subject for identity, and the database for current permissions. */
@Component
public class CurrentUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final JwtService jwtService;
    private final DatabaseUserDetailsService users;
    public CurrentUserJwtConverter(JwtService jwtService, DatabaseUserDetailsService users) {
        this.jwtService = jwtService; this.users = users;
    }
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var principal = users.loadActiveUserById(jwtService.userId(jwt));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
