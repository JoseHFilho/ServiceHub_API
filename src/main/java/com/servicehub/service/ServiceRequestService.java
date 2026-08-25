package com.servicehub.service;

import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.ServiceRequest;
import com.servicehub.repository.ServiceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ServiceRequestService {
    private final ServiceRequestRepository repository;

    public ServiceRequestService(ServiceRequestRepository repository) {
        this.repository = repository;
    }

    public ServiceRequest save(ServiceRequest serviceRequest) {
        return repository.save(serviceRequest);
    }

    @Transactional(readOnly = true)
    public ServiceRequest findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    public List<ServiceRequest> findAll() {
        return repository.findAll();
    }

    public void delete(Long id) {
        repository.delete(findById(id));
    }
}
