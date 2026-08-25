package com.servicehub.repository;

import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ServiceRequestRepository extends JpaRepository<ServiceRequest, Long> {
    List<ServiceRequest> findByClientId(Long clientId);
    List<ServiceRequest> findByServiceProviderId(Long providerId);
    List<ServiceRequest> findByStatus(ServiceRequestStatus status);
}
