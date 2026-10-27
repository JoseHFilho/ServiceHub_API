package com.servicehub.model.dto;

import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record RequestResponse(Long id, Long serviceId, String serviceTitle, Long clientId, Long providerId,
        ServiceRequestStatus status, LocalDateTime scheduledAt, String notes, BigDecimal totalPrice,
        LocalDateTime createdAt, LocalDateTime updatedAt) {
    public static RequestResponse from(ServiceRequest request) {
        return new RequestResponse(request.getId(), request.getService().getId(), request.getService().getTitle(),
                request.getClient().getId(), request.getService().getProvider().getId(), request.getStatus(),
                request.getScheduledAt(), request.getNotes(), request.getTotalPrice(), request.getCreatedAt(), request.getUpdatedAt());
    }
}
