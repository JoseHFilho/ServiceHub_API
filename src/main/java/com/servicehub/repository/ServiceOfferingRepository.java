package com.servicehub.repository;

import com.servicehub.model.ServiceCategory;
import com.servicehub.model.ServiceOffering;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

import java.math.BigDecimal;
import java.util.List;

public interface ServiceOfferingRepository extends JpaRepository<ServiceOffering, Long>, JpaSpecificationExecutor<ServiceOffering> {
    @Query("select s.provider.id from ServiceOffering s where s.id = :id")
    Optional<Long> findProviderIdById(@Param("id") Long id);
    List<ServiceOffering> findByCategory(ServiceCategory category);
    List<ServiceOffering> findAllByActiveTrue();
    List<ServiceOffering> findByPriceBetween(BigDecimal min, BigDecimal max);
    List<ServiceOffering> findByProviderIdAndActiveTrue(Long providerId);
    long countByCategory(ServiceCategory category);
}
