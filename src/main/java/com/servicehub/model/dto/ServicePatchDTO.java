package com.servicehub.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.servicehub.model.ServiceCategory;
import com.servicehub.validation.InputNormalizer;
import com.servicehub.validation.PlainText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

@Schema(description = "Atualização parcial: campos omitidos/nulos são preservados; ao menos um campo deve ser informado.")
public record ServicePatchDTO(
        @Pattern(regexp = "(?s).*\\S.*") @Size(max = 150) @PlainText @Schema(example = "Novo título") String title,
        @Size(max = 5000) @PlainText String description,
        @Positive @Digits(integer = 8, fraction = 2) @Schema(example = "200.00") BigDecimal price,
        ServiceCategory category
) {
    public ServicePatchDTO { title = InputNormalizer.name(title); description = InputNormalizer.text(description); }
    @AssertTrue(message = "Informe ao menos um campo para atualizar.") @JsonIgnore
    public boolean isNotEmpty() { return title != null || description != null || price != null || category != null; }
}
