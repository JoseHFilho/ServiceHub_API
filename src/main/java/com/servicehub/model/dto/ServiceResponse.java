package com.servicehub.model.dto;

import com.servicehub.model.ServiceCategory;
import com.servicehub.model.ServiceOffering;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ServiceResponse(Long id, String title, String description, BigDecimal price,
        ServiceCategory category, Long providerId, String providerName, boolean active,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static ServiceResponse from(ServiceOffering service) {
        return new ServiceResponse(service.getId(), service.getTitle(), service.getDescription(), service.getPrice(),
                service.getCategory(), service.getProvider().getId(), service.getProvider().getFullName(), service.isActive(),
                service.getCreatedAt(), service.getUpdatedAt());
    }
}
