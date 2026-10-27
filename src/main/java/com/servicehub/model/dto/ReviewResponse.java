package com.servicehub.model.dto;

import com.servicehub.model.Review;
import java.time.LocalDateTime;

public record ReviewResponse(Long id, Long requestId, Long serviceId, Long reviewerId,
        short rating, String comment, LocalDateTime createdAt) {
    public static ReviewResponse from(Review review) {
        return new ReviewResponse(review.getId(), review.getRequest().getId(), review.getRequest().getService().getId(),
                review.getReviewer().getId(), review.getRating(), review.getComment(), review.getCreatedAt());
    }
}
