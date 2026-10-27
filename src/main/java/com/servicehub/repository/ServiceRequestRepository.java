package com.servicehub.repository;

import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long>, JpaSpecificationExecutor<ServiceRequest> {
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

    @Query("""
            SELECT COUNT(r) > 0 FROM ServiceRequest r
            WHERE (r.client.id = :userId OR r.service.provider.id = :userId)
              AND r.status IN :statuses
            """)
    boolean hasOpenRequestsForUser(@Param("userId") Long userId,
            @Param("statuses") Collection<ServiceRequestStatus> statuses);

    boolean existsByServiceIdAndStatusIn(Long serviceId, Collection<ServiceRequestStatus> statuses);
}
