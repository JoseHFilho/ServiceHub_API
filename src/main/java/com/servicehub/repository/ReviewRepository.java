package com.servicehub.repository;

import com.servicehub.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long>, JpaSpecificationExecutor<Review> {
    @Override
    @EntityGraph(attributePaths = {"request", "request.service"})
    Page<Review> findAll(Specification<Review> spec, Pageable pageable);
    Optional<Review> findByRequestId(Long requestId);
    List<Review> findByReviewerId(Long reviewerId);
}
