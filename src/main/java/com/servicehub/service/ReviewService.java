package com.servicehub.service;

import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.Review;
import com.servicehub.repository.ReviewRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ReviewService {
    private final ReviewRepository repository;

    public ReviewService(ReviewRepository repository) {
        this.repository = repository;
    }

    public Review save(Review review) {
        if (review.getRating() < 1 || review.getRating() > 5) {
            throw new IllegalArgumentException("A avaliação deve estar entre 1 e 5.");
        }
        return repository.save(review);
    }

    @Transactional(readOnly = true)
    public Review findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Avaliação não encontrada: " + id));
    }

    @Transactional(readOnly = true)
    public List<Review> findAll() {
        return repository.findAll();
    }

    public void delete(Long id) {
        repository.delete(findById(id));
    }
}
