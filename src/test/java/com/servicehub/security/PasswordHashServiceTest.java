package com.servicehub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.Tag("unit")
class PasswordHashServiceTest {

    public static final String LEGACY = "pbkdf2$65536$AAECAwQFBgcICQoLDA0ODw==$gIYFOSdDklXup7oLrChlKs0O9tHxVEgAgHhNVXtNd+0=";
    public static final String LONG_LEGACY = "pbkdf2$65536$AAECAwQFBgcICQoLDA0ODw==$RugkzM7wBMXhR2K9pdGmy+6NNVF2hdzGhJAL/u5ZHD4=";

    private final PasswordHashService passwordHashService = new PasswordHashService();

    @Test
    void hashesNewPasswordsWithBcryptAndVerifiesThem() {
        String hash = passwordHashService.hash("senha-segura");
        assertThat(hash).startsWith("$2a$12$").hasSize(60);
        assertThat(passwordHashService.matches("senha-segura", hash)).isTrue();
        assertThat(passwordHashService.matches("senha-incorreta", hash)).isFalse();
        assertThat(passwordHashService.upgradeEncoding(hash)).isFalse();
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

    @Test
    void verifiesTheHistoricalPbkdf2FormatWithoutChangingThePassword() {
        assertThat(passwordHashService.matches("senha-legada-123", LEGACY)).isTrue();
        assertThat(passwordHashService.matches("senha-errada", LEGACY)).isFalse();
        assertThat(passwordHashService.upgradeEncoding(LEGACY)).isTrue();
    }

    @Test
    void preservesLongLegacyPasswordsWhenUpgradingToBcrypt() {
        String password = "á".repeat(80);
        assertThat(passwordHashService.matches(password, LONG_LEGACY)).isTrue();
        String upgraded = passwordHashService.encode(password);
        assertThat(upgraded).startsWith("{bcrypt-sha256}$2a$12$");
        assertThat(passwordHashService.matches(password, upgraded)).isTrue();
        assertThat(passwordHashService.matches(password + "x", upgraded)).isFalse();
        assertThat(passwordHashService.upgradeEncoding(upgraded)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"hash", "pbkdf2$65536$bad$bad", "pbkdf2$9999999999$bad$bad",
            "pbkdf2$65536$AAECAwQFBgcICQoLDA0ODw==$bad", "pbkdf2$65536$bad", "$2a$31$invalid", "{bcrypt-sha256}invalid"})
    void rejectsMalformedHashesSafely(String hash) {
        assertThat(passwordHashService.matches("senha-legada-123", hash)).isFalse();
    }

    @Test
    void rejectsNullBlankAndTruncatedCandidates() {
        assertThat(passwordHashService.matches(null, LEGACY)).isFalse();
        assertThat(passwordHashService.matches(" ", LEGACY)).isFalse();
        String hash = passwordHashService.encode("a".repeat(72));
        assertThat(passwordHashService.matches("a".repeat(72) + "b", hash)).isFalse();
    }
}
