package com.servicehub.service;

import com.servicehub.exception.BusinessConflictException;
import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.model.UserRole;
import com.servicehub.repository.ServiceRequestRepository;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.AccessPolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal administrative operation only: deliberately no REST/GraphQL mapping. */
@Service
@Transactional
public class RoleAdministrationService {
    private final BusinessLocks locks;
    private final AccessPolicy access;
    private final UserRepository users;
    private final ServiceRequestRepository requests;
    public RoleAdministrationService(BusinessLocks locks, AccessPolicy access, UserRepository users, ServiceRequestRepository requests) {
        this.locks=locks; this.access=access; this.users=users; this.requests=requests;
    }
    @PreAuthorize("hasRole('ADMIN')")
    public void changeRole(Long id, UserRole role) {
        access.requireAdmin();
        if(role==null) throw new IllegalArgumentException("Papel obrigatório.");
        var user=locks.users(access.currentUserId(),id).get(id);
        if(user==null || !user.isActive()) throw new ResourceNotFoundException("Usuário indisponível.");
        if(user.getRole()==role) return;
        if(requests.hasOpenRequestsForUser(id,ServiceRequestStatus.openStatuses())) {
            throw new BusinessConflictException("Papel não pode ser alterado enquanto houver solicitações abertas.");
        }
        user.setRole(role); users.saveAndFlush(user);
    }
}
