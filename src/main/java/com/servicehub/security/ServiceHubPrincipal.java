package com.servicehub.security;

import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class ServiceHubPrincipal implements UserDetails {
    private final Long id;
    private final String email;
    private final String password;
    private final UserRole role;
    private final boolean active;

    public ServiceHubPrincipal(User user) {
        this(user.getId(), user.getEmail(), user.getPasswordHash(), user.getRole(), user.isActive());
    }
    private ServiceHubPrincipal(Long id, String email, String password, UserRole role, boolean active) {
        this.id = id; this.email = email; this.password = password; this.role = role; this.active = active;
    }
    public Long id() { return id; }
    @Override public String getUsername() { return email; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return active; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
    public ServiceHubPrincipal withPassword(String value) {
        return new ServiceHubPrincipal(id, email, value, role, active);
    }
    @Override public String toString() { return "ServiceHubPrincipal[id=" + id + ", role=" + role + "]"; }
}
