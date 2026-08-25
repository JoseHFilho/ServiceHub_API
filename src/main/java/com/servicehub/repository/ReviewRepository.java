package com.servicehub.repository;

import com.servicehub.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    Optional<Review> findByRequestId(Long requestId);
    List<Review> findByReviewerId(Long reviewerId);
}
