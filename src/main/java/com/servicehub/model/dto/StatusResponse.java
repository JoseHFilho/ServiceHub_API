package com.servicehub.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Identificação da aplicação. Para verificar também o banco, consulte /actuator/health.")
public record StatusResponse(
        @Schema(description = "Estado da aplicação", example = "OK") String status,
        @Schema(description = "Versão da API", example = "1.0.0") String version,
        @Schema(description = "Mensagem da aplicação", example = "ServiceHub API está no ar! 🚀") String message
) {
}
