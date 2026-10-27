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
public class ServiceRequestService {
    private final ServiceRequestRepository repository;
    private final ServiceOfferingRepository offerings;
    private final AccessPolicy access;
    private final BusinessLocks locks;

    public ServiceRequestService(ServiceRequestRepository repository, AccessPolicy access,
            BusinessLocks locks, ServiceOfferingRepository offerings) {
        this.repository = repository; this.access = access; this.locks = locks; this.offerings = offerings;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'CLIENT')")
    public RequestResponse create(RequestCreateDTO dto, Long clientId) {
        return RequestResponse.from(createRequest(dto, access.actingFor("CLIENT", clientId)));
    }

    private ServiceRequest createRequest(RequestCreateDTO dto, Long clientId) {
        if (!access.isAdmin() && !access.hasRole("CLIENT")) throw new AccessDeniedException("Acesso negado.");
        access.requireUser(clientId);
        locks.validate(dto);
        Long providerId = offerings.findProviderIdById(dto.serviceId())
                .orElseThrow(() -> new ResourceNotFoundException("Serviço indisponível."));
        if (clientId.equals(providerId)) throw new BusinessConflictException("Não é permitido contratar o próprio serviço.");
        var users = locks.users(access.currentUserId(), clientId, providerId);
        var client = users.get(clientId);
        var offering = locks.service(dto.serviceId());
        if (!client.isActive() || client.getRole() != UserRole.CLIENT) throw new ResourceNotFoundException("Cliente indisponível.");
        if (!offering.isActive() || !users.get(providerId).isActive()) throw new ResourceNotFoundException("Serviço indisponível.");
        var request = new ServiceRequest();
        request.setClient(client); request.setService(offering);
        request.setTotalPrice(offering.getPrice()); request.setStatus(ServiceRequestStatus.PENDING);
        request.setScheduledAt(dto.scheduledAt()); request.setNotes(dto.notes());
        return repository.save(request);
    }

    @PreAuthorize("isAuthenticated()")
    public RequestResponse changeStatus(Long id, RequestStatusDTO dto) {
        locks.validate(dto);
        var request = editable(id);
        transition(request, dto.status());
        return RequestResponse.from(repository.saveAndFlush(request));
    }

    private void transition(ServiceRequest request, ServiceRequestStatus target) {
        Long providerId = request.getService().getProvider().getId();
        if (!access.isAdmin() && !providerId.equals(access.currentUserId())) {
            if (target != ServiceRequestStatus.CANCELLED
                    || request.getStatus() == ServiceRequestStatus.IN_PROGRESS) throw new AccessDeniedException("Acesso negado.");
        } else if (!access.isAdmin()) access.requireProvider(providerId);
        RequestTransitions.require(request.getStatus(), target);
        request.setStatus(target);
    }

    /** Compatibility API cannot reassign participants, price, or bypass status transitions. */
    @PreAuthorize("isAuthenticated()")
    public ServiceRequest save(ServiceRequest input) {
        if (input == null || input.getClient() == null || input.getService() == null
                || input.getClient().getId() == null || input.getService().getId() == null) {
            throw new IllegalArgumentException("Cliente e serviço são obrigatórios.");
        }
        Long id = input.getId(), clientId = input.getClient().getId(), serviceId = input.getService().getId();
        var price = input.getTotalPrice();
        var target = input.getStatus();
        var dto = new RequestCreateDTO(serviceId, input.getScheduledAt(), input.getNotes());
        locks.detachInput(input);
        if (id == null) {
            if (target != ServiceRequestStatus.PENDING) throw new BusinessConflictException("Uma solicitação inicia em PENDING.");
            return createRequest(dto, clientId);
        }
        var request = editable(id);
        if (!request.getClient().getId().equals(clientId) || !request.getService().getId().equals(serviceId)
                || price == null || request.getTotalPrice().compareTo(price) != 0) {
            throw new AccessDeniedException("Os participantes e o preço histórico não podem ser substituídos.");
        }
        if (request.getStatus() != target) transition(request, target);
        return repository.save(request);
    }

    private ServiceRequest editable(Long id) {
        var route = repository.findParticipantsById(id).orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado: " + id));
        access.requireParticipant(route.getClientId(), route.getProviderId());
        locks.users(access.currentUserId(), route.getClientId(), route.getProviderId());
        locks.service(route.getServiceId());
        return locks.request(id);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public ServiceRequest findById(Long id) {
        var request = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado: " + id));
        access.requireParticipant(request.getClient().getId(), request.getService().getProvider().getId());
        return request;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public RequestResponse get(Long id) { return RequestResponse.from(findById(id)); }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public PageResponse<RequestResponse> list(ServiceRequestStatus status, Long serviceId, Pageable page) {
        Specification<ServiceRequest> spec = (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (!access.isAdmin()) predicates.add(cb.or(cb.equal(root.get("client").get("id"), access.currentUserId()),
                    cb.equal(root.get("service").get("provider").get("id"), access.currentUserId())));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (serviceId != null) predicates.add(cb.equal(root.get("service").get("id"), serviceId));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return PageResponse.from(repository.findAll(spec, page).map(RequestResponse::from));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<ServiceRequest> findAll() { access.requireAdmin(); return repository.findAll(); }

    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        access.requireAdmin();
        var request = editable(id);
        if (ServiceRequestStatus.openStatuses().contains(request.getStatus())) throw new BusinessConflictException("Solicitação em andamento.");
        repository.delete(request);
    }
}
