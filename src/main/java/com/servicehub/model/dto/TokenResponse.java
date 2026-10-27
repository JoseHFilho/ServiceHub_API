package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record TokenResponse(
        @Schema(description = "JWT assinado; envie no header Authorization: Bearer") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(description = "Validade em segundos", example = "900") long expiresIn,
        @Schema(description = "Instante de expiração UTC") Instant expiresAt
) {
    @Override public String toString() { return "TokenResponse[tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]"; }
}
