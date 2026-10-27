package com.servicehub.service;

import com.servicehub.exception.*;
import com.servicehub.model.*;
import com.servicehub.model.dto.*;
import com.servicehub.repository.*;
import com.servicehub.security.AccessPolicy;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReviewService {
    private final ReviewRepository repository;
    private final ServiceRequestRepository requests;
    private final AccessPolicy access;
    private final BusinessLocks locks;
    public ReviewService(ReviewRepository repository, ServiceRequestRepository requests, AccessPolicy access, BusinessLocks locks) {
        this.repository = repository; this.requests = requests; this.access = access; this.locks = locks;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENT')")
    public ReviewResponse create(ReviewCreateDTO dto) { return ReviewResponse.from(createReview(dto, null)); }

    private Review createReview(ReviewCreateDTO dto, Long assertedReviewerId) {
        locks.validate(dto);
        var route = requests.findParticipantsById(dto.requestId()).orElseThrow(() -> new ResourceNotFoundException("Pedido indisponível."));
        access.requireUser(route.getClientId());
        if (!access.isAdmin() && !access.hasRole("CLIENT")) throw new AccessDeniedException("Acesso negado.");
        if (assertedReviewerId != null && !assertedReviewerId.equals(route.getClientId())) throw new AccessDeniedException("Acesso negado.");
        var client = locks.users(access.currentUserId(), route.getClientId(), route.getProviderId()).get(route.getClientId());
        locks.service(route.getServiceId());
        var request = locks.request(dto.requestId());
        if (!client.isActive()) throw new ResourceNotFoundException("Cliente indisponível.");
        if (request.getStatus() != ServiceRequestStatus.COMPLETED) throw new BusinessConflictException("Somente solicitações concluídas podem ser avaliadas.");
        if (repository.findByRequestId(request.getId()).isPresent()) throw new BusinessConflictException("A solicitação já possui avaliação.");
        var review = new Review();
        review.setRequest(request); review.setReviewer(client);
        review.setRating(dto.rating().shortValue()); review.setComment(dto.comment());
        return repository.save(review);
    }

    @PreAuthorize("isAuthenticated()")
    public Review save(Review input) {
        if (input == null || input.getRequest() == null || input.getReviewer() == null
                || input.getRequest().getId() == null || input.getReviewer().getId() == null) {
            throw new IllegalArgumentException("Pedido e avaliador são obrigatórios.");
        }
        Long id = input.getId(), reviewerId = input.getReviewer().getId();
        var dto = new ReviewCreateDTO(input.getRequest().getId(), (int) input.getRating(), input.getComment());
        locks.detachInput(input);
        if (id != null) {
            var existing = findById(id);
            access.requireUser(existing.getReviewer().getId());
            throw new BusinessConflictException("Avaliações publicadas não podem ser substituídas.");
        }
        return createReview(dto, reviewerId);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public Review findById(Long id) {
        var review = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Avaliação não encontrada: " + id));
        access.requireParticipant(review.getReviewer().getId(), review.getRequest().getService().getProvider().getId());
        return review;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public ReviewResponse get(Long id) { return ReviewResponse.from(findById(id)); }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PageResponse<ReviewResponse> list(Long requestId, Long serviceId, Pageable page) {
        Specification<Review> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!access.isAdmin()) predicates.add(cb.or(cb.equal(root.get("reviewer").get("id"), access.currentUserId()),
                    cb.equal(root.get("request").get("service").get("provider").get("id"), access.currentUserId())));
            if (requestId != null) predicates.add(cb.equal(root.get("request").get("id"), requestId));
            if (serviceId != null) predicates.add(cb.equal(root.get("request").get("service").get("id"), serviceId));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec, page).map(ReviewResponse::from));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<Review> findAll() { access.requireAdmin(); return repository.findAll(); }

    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        access.requireAdmin();
        repository.delete(findById(id));
    }
}
