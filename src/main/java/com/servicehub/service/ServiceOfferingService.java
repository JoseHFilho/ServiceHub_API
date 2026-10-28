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
public class ServiceOfferingService {
    private final ServiceOfferingRepository repository;
    private final ServiceRequestRepository requests;
    private final AccessPolicy access;
    private final BusinessLocks locks;

    public ServiceOfferingService(ServiceOfferingRepository repository, ServiceRequestRepository requests,
            AccessPolicy access, BusinessLocks locks) {
        this.repository = repository; this.requests = requests; this.access = access; this.locks = locks;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ServiceResponse create(ServiceWriteDTO dto, Long providerId) {
        return ServiceResponse.from(createOffering(dto, access.actingFor("PROVIDER", providerId)));
    }

    private ServiceOffering createOffering(ServiceWriteDTO dto, Long providerId) {
        access.requireProvider(providerId);
        var provider = locks.users(access.currentUserId(), providerId).get(providerId);
        if (!provider.isActive() || provider.getRole() != UserRole.PROVIDER) throw new ResourceNotFoundException("Prestador indisponível.");
        locks.validate(dto);
        var offering = new ServiceOffering();
        offering.setProvider(provider);
        apply(offering, dto);
        return repository.save(offering);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ServiceResponse update(Long id, ServiceWriteDTO dto) {
        var offering = editable(id);
        locks.validate(dto);
        apply(offering, dto);
        return ServiceResponse.from(repository.saveAndFlush(offering));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ServiceResponse patch(Long id, ServicePatchDTO dto) {
        var offering = editable(id);
        locks.validate(dto);
        if (dto.title() != null) offering.setTitle(dto.title());
        if (dto.description() != null) offering.setDescription(dto.description());
        if (dto.price() != null) offering.setPrice(dto.price());
        if (dto.category() != null) offering.setCategory(dto.category());
        return ServiceResponse.from(repository.saveAndFlush(offering));
    }

    /** Internal compatibility API applies the same ownership and validation as REST. */
    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public ServiceOffering save(ServiceOffering input) {
        if (input == null || input.getProvider() == null || input.getProvider().getId() == null) throw new IllegalArgumentException("Prestador obrigatório.");
        Long id = input.getId(), providerId = input.getProvider().getId();
        var dto = new ServiceWriteDTO(input.getTitle(), input.getDescription(), input.getPrice(), input.getCategory());
        boolean active = input.isActive();
        locks.detachInput(input);
        if (id == null) return createOffering(dto, providerId);
        var offering = editable(id);
        if (!offering.getProvider().getId().equals(providerId)) throw new AccessDeniedException("Acesso negado.");
        if (offering.isActive() != active) throw new IllegalArgumentException("Utilize a operação de desativação do serviço.");
        locks.validate(dto);
        apply(offering, dto);
        return repository.save(offering);
    }

    private ServiceOffering editable(Long id) {
        Long providerId = repository.findProviderIdById(id).orElseThrow(() -> new ResourceNotFoundException("Serviço não encontrado: " + id));
        access.requireProvider(providerId);
        var provider = locks.users(access.currentUserId(), providerId).get(providerId);
        var offering = locks.service(id);
        if (!offering.isActive() || !provider.isActive()) throw new ResourceNotFoundException("Serviço indisponível.");
        return offering;
    }

    private void apply(ServiceOffering offering, ServiceWriteDTO dto) {
        offering.setTitle(dto.title()); offering.setDescription(dto.description());
        offering.setPrice(dto.price()); offering.setCategory(dto.category());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public ServiceOffering findById(Long id) {
        return repository.findById(id).filter(s -> s.isActive() && s.getProvider().isActive() && s.getProvider().getRole() == UserRole.PROVIDER)
                .orElseThrow(() -> new ResourceNotFoundException("Serviço não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public ServiceResponse get(Long id) { return ServiceResponse.from(findById(id)); }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PageResponse<ServiceResponse> list(ServiceCategory category, Long providerId, Pageable page) {
        Specification<ServiceOffering> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.isTrue(root.get("active")));
            predicates.add(cb.isTrue(root.get("provider").get("active")));
            predicates.add(cb.equal(root.get("provider").get("role"), UserRole.PROVIDER));
            if (category != null) predicates.add(cb.equal(root.get("category"), category));
            if (providerId != null) predicates.add(cb.equal(root.get("provider").get("id"), providerId));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec, page).map(ServiceResponse::from));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<ServiceOffering> findAll() {
        return repository.findAllByActiveTrue().stream().filter(s -> s.getProvider().isActive() && s.getProvider().getRole() == UserRole.PROVIDER).toList();
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'PROVIDER')")
    public void delete(Long id) {
        var offering = editable(id);
        if (requests.existsByServiceIdAndStatusIn(id, ServiceRequestStatus.openStatuses())) throw new BusinessConflictException("Serviço possui solicitações em andamento.");
        offering.setActive(false);
        repository.save(offering);
    }
}
