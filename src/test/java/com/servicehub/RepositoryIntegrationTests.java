package com.servicehub;

import com.servicehub.model.Review;
import com.servicehub.model.ServiceCategory;
import com.servicehub.model.ServiceOffering;
import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.model.User;
import com.servicehub.repository.ReviewRepository;
import com.servicehub.repository.ServiceOfferingRepository;
import com.servicehub.repository.ServiceRequestRepository;
import com.servicehub.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ServiceOfferingRepository serviceOfferingRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Test
    void executesTheUserQueryMethodsFromTheSlides() {
        User user = saveUser("Ana Prestadora", "ana@servicehub.com");

        assertThat(userRepository.findByEmail(user.getEmail())).contains(user);
        assertThat(userRepository.existsByEmail(user.getEmail())).isTrue();
        assertThat(userRepository.findByFullNameContainingIgnoreCase("prestadora")).contains(user);
        assertThat(userRepository.findRecentUsers(LocalDateTime.now().minusMinutes(1))).contains(user);
        assertThat(userRepository.findActiveUsers(10)).contains(user);
    }

    @Test
    void executesServiceQueriesAndPersistsAllRelationships() {
        User provider = saveUser("Carlos Profissional", "carlos@servicehub.com");
        User client = saveUser("Joana Cliente", "joana@servicehub.com");

        ServiceOffering service = new ServiceOffering();
        service.setTitle("Limpeza residencial");
        service.setDescription("Limpeza completa do imóvel");
        service.setPrice(new BigDecimal("150.00"));
        service.setCategory(ServiceCategory.CLEANING);
        service.setProvider(provider);
        service = serviceOfferingRepository.saveAndFlush(service);

        assertThat(serviceOfferingRepository.findByCategory(ServiceCategory.CLEANING)).contains(service);
        assertThat(serviceOfferingRepository.findByPriceBetween(
                new BigDecimal("100.00"), new BigDecimal("200.00"))).contains(service);
        assertThat(serviceOfferingRepository.findByProviderIdAndActiveTrue(provider.getId())).contains(service);
        assertThat(serviceOfferingRepository.countByCategory(ServiceCategory.CLEANING)).isEqualTo(1);

        ServiceRequest request = new ServiceRequest();
        request.setService(service);
        request.setClient(client);
        request.setStatus(ServiceRequestStatus.PENDING);
        request.setTotalPrice(new BigDecimal("150.00"));
        request = serviceRequestRepository.saveAndFlush(request);

        Review review = new Review();
        review.setRequest(request);
        review.setReviewer(client);
        review.setRating((short) 5);
        review.setComment("Excelente serviço");
        review = reviewRepository.saveAndFlush(review);

        assertThat(serviceRequestRepository.findByClientId(client.getId())).contains(request);
        assertThat(serviceRequestRepository.findByServiceProviderId(provider.getId())).contains(request);
        assertThat(serviceRequestRepository.findByStatus(ServiceRequestStatus.PENDING)).contains(request);
        assertThat(reviewRepository.findByRequestId(request.getId())).contains(review);
        assertThat(reviewRepository.findByReviewerId(client.getId())).contains(review);
    }

    private User saveUser(String fullName, String email) {
        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPasswordHash("hash-de-teste");
        return userRepository.saveAndFlush(user);
    }
}
