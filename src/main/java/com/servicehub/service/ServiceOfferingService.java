package com.servicehub.service;

import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.ServiceOffering;
import com.servicehub.repository.ServiceOfferingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ServiceOfferingService {
    private final ServiceOfferingRepository repository;

    public ServiceOfferingService(ServiceOfferingRepository repository) {
        this.repository = repository;
    }

    public ServiceOffering save(ServiceOffering serviceOffering) {
        return repository.save(serviceOffering);
    }

    @Transactional(readOnly = true)
    public ServiceOffering findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Serviço não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    public List<ServiceOffering> findAll() {
        return repository.findAll();
    }

    public void delete(Long id) {
        repository.delete(findById(id));
    }
}
