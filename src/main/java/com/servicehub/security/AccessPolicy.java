package com.servicehub.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component("access")
public class AccessPolicy {
    public boolean isAdmin() { return hasRole("ADMIN"); }
    public boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }
    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof ServiceHubPrincipal principal
                ? principal.id() : null;
    }
    public long currentCredentialVersion() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof ServiceHubPrincipal principal ? principal.credentialVersion() : 0;
    }
    public boolean canAccessUser(Long id) {
        return isAdmin() || (id != null && id.equals(currentUserId()));
    }
    public void requireUser(Long id) {
        requireAuthenticated();
        if (!canAccessUser(id)) throw new AccessDeniedException("Acesso negado.");
    }
    public void requireAdmin() {
        requireAuthenticated();
        if (!isAdmin()) throw new AccessDeniedException("Acesso negado.");
    }
    public void requireProvider(Long ownerId) {
        requireAuthenticated();
        if (!isAdmin() && !(hasRole("PROVIDER") && canAccessUser(ownerId))) {
            throw new AccessDeniedException("Acesso negado.");
        }
    }
    public void requireParticipant(Long clientId, Long providerId) {
        requireAuthenticated();
        if (!canAccessUser(clientId) && !canAccessUser(providerId)) throw new AccessDeniedException("Acesso negado.");
    }
    /** Administrative delegation is explicit; ordinary actors use their signed identity. */
    public Long actingFor(String role, Long delegatedId) {
        requireAuthenticated();
        if (isAdmin()) {
            if (delegatedId == null || delegatedId <= 0) throw new IllegalArgumentException("Informe o usuário representado pelo ADMIN.");
            return delegatedId;
        }
        if (!hasRole(role) || delegatedId != null || currentUserId() == null) throw new AccessDeniedException("Acesso negado.");
        return currentUserId();
    }
    public void requireAuthenticated() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("Autenticação necessária.");
        }
    }
}
