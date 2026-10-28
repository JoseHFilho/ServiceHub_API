package com.servicehub.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("servicehub.jwt")
public class JwtProperties {
    private String secret;
    private Duration ttl = Duration.ofMinutes(15);
    private String issuer = "servicehub-api";
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public byte[] secretBytes() {
        byte[] bytes;
        try { bytes = java.util.Base64.getDecoder().decode(secret == null ? "" : secret); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("JWT_SECRET deve ser Base64 válido."); }
        if (bytes.length < 32 || bytes.length > 128) {
            throw new IllegalStateException("JWT_SECRET deve conter de 32 a 128 bytes aleatórios codificados em Base64.");
        }
        return bytes;
    }
    @Override public String toString() { return "JwtProperties[segredo protegido]"; }
}
