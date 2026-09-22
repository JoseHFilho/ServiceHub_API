package com.servicehub.security;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class PasswordHashServiceTest {

    private final PasswordHashService passwordHashService = new PasswordHashService();

    @Test
    void hashesPasswordUsingExpectedPbkdf2Format() {
        String hash = passwordHashService.hash("senha-segura");
        String[] parts = hash.split("\\$");

        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("pbkdf2");
        assertThat(parts[1]).isEqualTo("65536");
        assertThat(Base64.getDecoder().decode(parts[2])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[3])).hasSize(32);
    }

    @Test
    void generatesDifferentSaltForRepeatedHashes() {
        String firstHash = passwordHashService.hash("senha-segura");
        String secondHash = passwordHashService.hash("senha-segura");

        assertThat(firstHash).isNotEqualTo(secondHash);
    }

    @Test
    void rejectsNullOrBlankPassword() {
        assertThatThrownBy(() -> passwordHashService.hash(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A senha é obrigatória.");
        assertThatThrownBy(() -> passwordHashService.hash(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A senha é obrigatória.");
    }
}
