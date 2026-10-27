package com.servicehub.model.dto;

import com.servicehub.model.ServiceCategory;
import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@Schema(description = "Campos editáveis de serviço. Prestador e ativação não são vinculados ao corpo.")
public record ServiceWriteDTO(
        @NotBlank @Size(max = 150) @PlainText @Schema(example = "Limpeza residencial") String title,
        @Size(max = 5000) @PlainText @Schema(example = "Limpeza completa do imóvel") String description,
        @NotNull @Positive @Digits(integer = 8, fraction = 2) @Schema(example = "150.00") BigDecimal price,
        @NotNull @Schema(example = "LIMPEZA") ServiceCategory category
) {
    public ServiceWriteDTO { title = InputNormalizer.name(title); description = InputNormalizer.text(description); }
}
