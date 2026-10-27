package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.servicehub.model.User;
import com.servicehub.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DatabaseUserDetailsServiceTest {
    @Mock private UserRepository users;

    @Test
    void refusesAHashUpgradeWhenTheStoredCredentialOrActivationChangedConcurrently() {
        User user = new User(); ReflectionTestUtils.setField(user, "id", 1L);
        user.setEmail("legacy@example.com"); user.setPasswordHash(PasswordHashServiceTest.LEGACY);
        var service = new DatabaseUserDetailsService(users);
        when(users.upgradePassword(eq(1L), eq(PasswordHashServiceTest.LEGACY), eq("new-hash"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.updatePassword(new ServiceHubPrincipal(user), "new-hash"))
                .isInstanceOf(BadCredentialsException.class);
    }
}
