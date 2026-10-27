package com.servicehub.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Component
public class PasswordHashService implements PasswordEncoder {
    private static final String PREHASH_PREFIX = "{bcrypt-sha256}";
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

    public String hash(String password) {
        return encode(password);
    }

    @Override
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null || rawPassword.toString().isBlank()) {
            throw new IllegalArgumentException("A senha é obrigatória.");
        }
        // Preserve long legacy passwords without silently truncating for BCrypt.
        if (rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > 72) {
            return PREHASH_PREFIX + bcrypt.encode(digest(rawPassword));
        }
        return bcrypt.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || rawPassword.toString().isBlank()) return false;
        if (encodedPassword.startsWith("pbkdf2$")) return matchesLegacy(rawPassword, encodedPassword);
        boolean prehashed = encodedPassword.startsWith(PREHASH_PREFIX);
        String hash = prehashed ? encodedPassword.substring(PREHASH_PREFIX.length()) : encodedPassword;
        if (!validBcrypt(hash)) return false;
        if (!prehashed && rawPassword.toString().getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return bcrypt.matches(prehashed ? digest(rawPassword) : rawPassword, hash);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null) return false;
        if (encodedPassword.startsWith("pbkdf2$")) return true;
        String hash = encodedPassword.startsWith(PREHASH_PREFIX)
                ? encodedPassword.substring(PREHASH_PREFIX.length()) : encodedPassword;
        return validBcrypt(hash) && bcrypt.upgradeEncoding(hash);
    }

    private boolean matchesLegacy(CharSequence rawPassword, String encodedPassword) {
        PBEKeySpec specification = null;
        try {
            String[] parts = encodedPassword.split("\\$", -1);
            if (parts.length != 4 || !parts[1].equals("65536")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            specification = new PBEKeySpec(rawPassword.toString().toCharArray(), salt, 65_536, 256);
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return false;
        } finally {
            if (specification != null) specification.clearPassword();
        }
    }

    private boolean validBcrypt(String hash) {
        return hash.matches("^\\$2[aby]\\$(0[4-9]|1[0-6])\\$[./A-Za-z0-9]{53}$");
    }

    private String digest(CharSequence password) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(password.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Não foi possível proteger a senha.");
        }
    }
}
