package com.servicehub.exception;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resposta de erro padronizada da API.")
public record ApiError(
        @Schema(description = "Data e hora do erro", example = "2026-09-22T10:00:00") LocalDateTime timestamp,
        @Schema(description = "Código HTTP", example = "404") int status,
        @Schema(description = "Descrição do status HTTP", example = "Not Found") String error,
        @Schema(description = "Mensagem explicativa", example = "Usuário não encontrado: 42") String message,
        @Schema(description = "Caminho da requisição", example = "/api/users/42") String path) {
}
