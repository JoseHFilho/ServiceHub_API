package com.servicehub.model.dto;

import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;

@Schema(description = "Cliente e preço vêm do servidor. O estado inicial é sempre PENDING.")
public record RequestCreateDTO(
        @NotNull @Positive @Schema(example = "1") Long serviceId,
        @FutureOrPresent @Schema(description = "Horário local opcional, presente ou futuro", example = "2027-01-10T09:00:00") LocalDateTime scheduledAt,
        @Size(max = 5000) @PlainText @Schema(example = "Preferência pelo período da manhã") String notes
) {
    public RequestCreateDTO { notes = InputNormalizer.text(notes); }
}
