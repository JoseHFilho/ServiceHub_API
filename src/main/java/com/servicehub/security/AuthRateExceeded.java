package com.servicehub.security;

final class AuthRateExceeded extends RuntimeException {
    final long retryAfter;
    final String reason;
    AuthRateExceeded(long retryAfter, String reason) {
        super("Limite temporário de autenticação excedido.");
        this.retryAfter = Math.max(1, retryAfter); this.reason = reason;
    }
}
