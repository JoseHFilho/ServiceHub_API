package com.servicehub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.servicehub.exception.BusinessConflictException;
import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.Review;
import com.servicehub.model.ServiceCategory;
import com.servicehub.model.ServiceOffering;
import com.servicehub.model.ServiceRequest;
import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.model.User;
import com.servicehub.model.UserRole;
import com.servicehub.repository.ReviewRepository;
import com.servicehub.repository.ServiceOfferingRepository;
import com.servicehub.repository.ServiceRequestRepository;
import com.servicehub.repository.UserRepository;
import com.servicehub.service.ServiceOfferingService;
import com.servicehub.service.UserService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
class DeactivationIntegrationTests extends com.servicehub.JwtTestSupport {
    @Autowired private UserRepository users;
    @Autowired private ServiceOfferingRepository offerings;
    @Autowired private ServiceRequestRepository requests;
    @Autowired private ReviewRepository reviews;
    @Autowired private UserService userService;
    @Autowired private ServiceOfferingService offeringService;
    @Autowired private WebApplicationContext context;
    @Autowired private EntityManager entityManager;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
            .defaultRequest(get("/")).build(); }

    @ParameterizedTest
    @EnumSource(ServiceRequestStatus.class)
    void protectsOpenRequestsAndPreservesClosedHistoryWhenDeactivating(ServiceRequestStatus status) {
        User provider = user("provider@example.com", UserRole.PROVIDER);
        User client = user("client@example.com", UserRole.CLIENT);
        ServiceOffering offering = offering(provider);
        ServiceRequest request = new ServiceRequest();
        request.setService(offering);
        request.setClient(client);
        request.setStatus(status);
        request.setTotalPrice(new BigDecimal("80.00"));
        requests.saveAndFlush(request);

        if (ServiceRequestStatus.openStatuses().contains(status)) {
            assertThatThrownBy(() -> userService.deleteUser(provider.getId())).isInstanceOf(BusinessConflictException.class);
            assertThatThrownBy(() -> userService.deleteUser(client.getId())).isInstanceOf(BusinessConflictException.class);
            assertThatThrownBy(() -> offeringService.delete(offering.getId())).isInstanceOf(BusinessConflictException.class);
            assertThat(provider.isActive()).isTrue();
            assertThat(client.isActive()).isTrue();
            assertThat(offering.isActive()).isTrue();
        } else {
            Long reviewId = null;
            if (status == ServiceRequestStatus.COMPLETED) {
                Review review = new Review();
                review.setRequest(request);
                review.setReviewer(client);
                review.setRating((short) 5);
                reviewId = reviews.saveAndFlush(review).getId();
            }
            userService.deleteUser(client.getId());
            userService.deleteUser(provider.getId());
            entityManager.flush();
            entityManager.clear();
            assertThat(users.findById(provider.getId()).orElseThrow().isActive()).isFalse();
            assertThat(users.findById(client.getId()).orElseThrow().isActive()).isFalse();
            assertThat(offerings.findById(offering.getId()).orElseThrow().isActive()).isFalse();
            assertThat(offeringService.findAll()).isEmpty();
            assertThat(userService.findAll()).isEmpty();
            if (reviewId != null) assertThat(reviews.findById(reviewId)).isPresent();
            assertThatThrownBy(() -> offeringService.findById(offering.getId())).isInstanceOf(ResourceNotFoundException.class);
        }
        assertThat(requests.findById(request.getId()).orElseThrow().getTotalPrice()).isEqualByComparingTo("80.00");
        assertThat(users.findById(provider.getId()).orElseThrow().getPasswordHash()).isEqualTo("existing-hash");
    }

    @Test
    void deactivatesAnOfferingWithoutRemovingItsRecordOrItsProvider() {
        User provider = user("provider@example.com", UserRole.PROVIDER);
        ServiceOffering offering = offering(provider);
        entityManager.refresh(offering);
        var createdAt = offering.getCreatedAt();
        offeringService.delete(offering.getId());
        entityManager.flush();
        entityManager.clear();
        ServiceOffering retained = offerings.findById(offering.getId()).orElseThrow();
        assertThat(retained.isActive()).isFalse();
        assertThat(retained.getCreatedAt()).isEqualTo(createdAt);
        assertThat(retained.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(users.findById(provider.getId()).orElseThrow().isActive()).isTrue();
        assertThatThrownBy(() -> offeringService.delete(offering.getId())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retainsDeletedUserAndReturns404ForReadsUpdatesAndRepeatedDeletes() throws Exception {
        User user = user("deleted@example.com", UserRole.CLIENT);
        mvc.perform(delete("/api/users/{id}", user.getId())).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo("existing-hash");
        assertThat(users.findActiveUsers(10)).isEmpty();
        mvc.perform(get("/api/users/{id}", user.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/users")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(delete("/api/users/{id}", user.getId())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Tentativa\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/users/{id}", user.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Nome\",\"email\":\"deleted@example.com\",\"password\":\"senha123\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void doesNotBindRoleOrActivationFromUserWritePayloads() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Cliente", "email":"new@example.com", "password":"senha123",
                 "role":"ADMIN", "active":false, "passwordHash":"injected"}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CLIENT"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.passwordHash").doesNotExist());
        User provider = user("provider@example.com", UserRole.PROVIDER);
        mvc.perform(put("/api/users/{id}", provider.getId()).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Novo nome", "email":"provider@example.com", "password":"senha123",
                 "role":"ADMIN", "active":false}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("PROVIDER"))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(patch("/api/users/{id}", provider.getId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Outro nome\",\"role\":\"ADMIN\",\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("PROVIDER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void persistsProfileFieldsRolesAndActivationAcrossEntityReloads() {
        User provider = user("profile@example.com", UserRole.PROVIDER);
        provider.setPhone("62999999999");
        provider.setBio("Perfil de prestador");
        provider.setAvatarUrl("https://example.com/avatar.png");
        users.saveAndFlush(provider);
        entityManager.clear();
        User reloaded = users.findById(provider.getId()).orElseThrow();
        assertThat(reloaded.getRole()).isEqualTo(UserRole.PROVIDER);
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getPhone()).isEqualTo("62999999999");
        assertThat(reloaded.getBio()).isEqualTo("Perfil de prestador");
        assertThat(reloaded.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    private User user(String email, UserRole role) {
        User user = new User();
        user.setFullName("Usuário");
        user.setEmail(email);
        user.setPasswordHash("existing-hash");
        user.setRole(role);
        return users.saveAndFlush(user);
    }

    private ServiceOffering offering(User provider) {
        ServiceOffering offering = new ServiceOffering();
        offering.setTitle("Limpeza");
        offering.setPrice(new BigDecimal("100.00"));
        offering.setCategory(ServiceCategory.LIMPEZA);
        offering.setProvider(provider);
        return offerings.saveAndFlush(offering);
    }
}
