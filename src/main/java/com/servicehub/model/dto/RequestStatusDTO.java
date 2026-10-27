package com.servicehub.model.dto;

import com.servicehub.model.ServiceRequestStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record RequestStatusDTO(@NotNull @Schema(example = "ACCEPTED") ServiceRequestStatus status) { }
