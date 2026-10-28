package com.servicehub.service;

import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.*;
import com.servicehub.security.AccessPolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared write order: users by ID, then service, then request, then review. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class BusinessLocks {
    private final EntityManager entityManager;
    private final Validator validator;
    private final AccessPolicy access;
    public BusinessLocks(EntityManager entityManager, Validator validator, AccessPolicy access) {
        this.entityManager = entityManager; this.validator = validator; this.access = access;
    }
    public Map<Long, User> users(Long... ids) {
        var sorted = new TreeSet<Long>();
        for (Long id : ids) if (id != null) sorted.add(id);
        var result = new HashMap<Long, User>();
        for (Long id : sorted) result.put(id, lock(User.class, id, "Usuário indisponível."));
        User actor = result.get(access.currentUserId());
        if (actor != null && (!actor.isActive() || !access.hasRole(actor.getRole().name())
                || actor.getCredentialVersion()!=access.currentCredentialVersion())) {
            throw new BadCredentialsException("Conta indisponível ou permissões alteradas.");
        }
        return result;
    }
    public ServiceOffering service(Long id) { return lock(ServiceOffering.class, id, "Serviço não encontrado: " + id); }
    public ServiceRequest request(Long id) { return lock(ServiceRequest.class, id, "Pedido não encontrado: " + id); }
    public Review review(Long id) { return lock(Review.class, id, "Avaliação não encontrada: " + id); }
    private <T> T lock(Class<T> type, Long id, String message) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Identificador inválido.");
        T entity = entityManager.find(type, id);
        if (entity == null) throw new ResourceNotFoundException(message);
        entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
        return entity;
    }
    public void detachInput(Object input) { if (entityManager.contains(input)) entityManager.detach(input); }
    public <T> void validate(T input) {
        var errors = validator.validate(input);
        if (!errors.isEmpty()) throw new ConstraintViolationException(errors);
    }
}
