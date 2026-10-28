package com.servicehub.security;

import com.servicehub.repository.UserRepository;
import com.servicehub.validation.InputNormalizer;
import java.time.LocalDateTime;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService, UserDetailsPasswordService {
    private final UserRepository users;
    public DatabaseUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        if (username == null) throw new UsernameNotFoundException("Credenciais inválidas.");
        return users.findByEmail(InputNormalizer.email(username)).map(ServiceHubPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas."));
    }

    @Transactional(readOnly = true)
    public ServiceHubPrincipal loadActiveUserById(Long id) {
        return users.findById(id).filter(user -> user.isActive()).map(ServiceHubPrincipal::new)
                .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas."));
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newPassword) {
        if (!(user instanceof ServiceHubPrincipal principal)
                || users.upgradePassword(principal.id(), principal.getPassword(), newPassword, LocalDateTime.now(), principal.credentialVersion()) != 1) {
            throw new BadCredentialsException("Credenciais inválidas.");
        }
        return principal.withPassword(newPassword);
    }
}
