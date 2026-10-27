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
    @Override public String toString() { return "JwtProperties[segredo protegido]"; }
}
