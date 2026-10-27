package com.servicehub.model.dto;

import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "O avaliador é o cliente real da solicitação, validado pelo servidor.")
public record ReviewCreateDTO(
        @NotNull @Positive @Schema(example = "1") Long requestId,
        @NotNull @Min(1) @Max(5) @Schema(example = "5") Integer rating,
        @Size(max = 2000) @PlainText @Schema(example = "Serviço realizado conforme combinado.") String comment
) {
    public ReviewCreateDTO { comment = InputNormalizer.text(comment); }
}
