package com.servicehub.repository;

import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long>, JpaSpecificationExecutor<ServiceRequest> {
    @Override
    @EntityGraph(attributePaths = {"service", "service.provider"})
    Page<ServiceRequest> findAll(Specification<ServiceRequest> spec, Pageable pageable);
    interface Participants {
        Long getServiceId();
        Long getClientId();
        Long getProviderId();
    }
    @Query("select r.service.id as serviceId, r.client.id as clientId, r.service.provider.id as providerId from ServiceRequest r where r.id = :id")
    Optional<Participants> findParticipantsById(@Param("id") Long id);
    List<ServiceRequest> findByClientId(Long clientId);
    List<ServiceRequest> findByServiceProviderId(Long providerId);
    List<ServiceRequest> findByStatus(ServiceRequestStatus status);

    // Count at most one user, and stop each participation probe at its first matching request.
    // The correlated outer row also keeps repeated reads reliable on the H2 test backend.
    @Query("""
            SELECT COUNT(u) > 0 FROM User u
            WHERE u.id = :userId AND (EXISTS (
                SELECT 1 FROM ServiceRequest r
                WHERE r.client.id = u.id AND r.status IN :statuses
            ) OR EXISTS (
                SELECT 1 FROM ServiceRequest r
                WHERE r.service.provider.id = u.id AND r.status IN :statuses
            ))
            """)
    boolean hasOpenRequestsForUser(@Param("userId") Long userId,
            @Param("statuses") Collection<ServiceRequestStatus> statuses);

    boolean existsByServiceIdAndStatusIn(Long serviceId, Collection<ServiceRequestStatus> statuses);
}
