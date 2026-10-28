package com.servicehub;

import com.servicehub.model.*;
import com.servicehub.repository.*;
import com.servicehub.security.*;
import com.servicehub.service.RoleAdministrationService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc(print=org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@Transactional
@Tag("web")
class RolePolicyIntegrationTests extends JwtTestSupport {
    private static final String PASSWORD=UUID.randomUUID().toString();
    private static final String HASH=new PasswordHashService().hash(PASSWORD);
    @Autowired UserRepository users;
    @Autowired ServiceOfferingRepository services;
    @Autowired ServiceRequestRepository requests;
    @Autowired ReviewRepository reviews;
    @Autowired RoleAdministrationService roles;
    @Autowired MockMvc mvc;
    private User provider,client,admin;
    private ServiceOffering service;
    private User user(UserRole role) {
        var user=new User();user.setFullName("Role Fixture");user.setEmail(UUID.randomUUID()+"@role.invalid");user.setPasswordHash(HASH);user.setRole(role);
        return users.saveAndFlush(user);
    }
    @BeforeEach void fixtures() {
        provider=user(UserRole.PROVIDER);client=user(UserRole.CLIENT);admin=user(UserRole.ADMIN);
        service=new ServiceOffering();service.setProvider(provider);service.setTitle("Role Fixture");service.setCategory(ServiceCategory.LIMPEZA);service.setPrice(new BigDecimal("125.50"));
        service=services.saveAndFlush(service);
    }
    @AfterEach void clearContext(){SecurityContextHolder.clearContext();}
    private void as(User user) {
        var principal=new ServiceHubPrincipal(user);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal,null,principal.getAuthorities()));
    }
    private ServiceRequest request(ServiceRequestStatus status) {
        var request=new ServiceRequest();request.setClient(client);request.setService(service);request.setStatus(status);request.setTotalPrice(service.getPrice());
        return requests.saveAndFlush(request);
    }
    @Test void roleRemovalHidesCatalogAndRejectsNewBookingsInBothInterfaces() throws Exception {
        String oldProviderToken=tokenFor(provider.getEmail(),PASSWORD);as(admin);roles.changeRole(provider.getId(),UserRole.CLIENT);
        mvc.perform(get("/api/services/"+service.getId()).with(bearer(client.getEmail(),PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(get("/api/services").with(bearer(client.getEmail(),PASSWORD))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(post("/api/requests").contentType("application/json").content("{\"serviceId\":"+service.getId()+"}").with(bearer(client.getEmail(),PASSWORD))).andExpect(status().isNotFound());
        mvc.perform(post("/graphql").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("query","{service(id:\""+service.getId()+"\"){id} provider(id:\""+provider.getId()+"\"){id}}")))
                .with(bearer(client.getEmail(),PASSWORD))).andExpect(jsonPath("$.data.service").isEmpty()).andExpect(jsonPath("$.data.provider").isEmpty());
        mvc.perform(post("/api/services").contentType("application/json").content("{\"title\":\"Old Role\",\"price\":10,\"category\":\"LIMPEZA\"}")
                .header("Authorization","Bearer "+oldProviderToken)).andExpect(status().isForbidden());
        assertThat(services.findById(service.getId()).orElseThrow().isActive()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void openRequestsPreventChangingEitherParticipantRoleWithoutCancellingHistory(boolean clientSide) {
        var request=request(ServiceRequestStatus.PENDING);as(admin);
        assertThatThrownBy(()->roles.changeRole(clientSide?client.getId():provider.getId(),clientSide?UserRole.PROVIDER:UserRole.CLIENT)).isInstanceOf(com.servicehub.exception.BusinessConflictException.class);
        assertThat(provider.getRole()).isEqualTo(UserRole.PROVIDER);
        assertThat(client.getRole()).isEqualTo(UserRole.CLIENT);
        assertThat(request.getStatus()).isEqualTo(ServiceRequestStatus.PENDING);
    }
    @Test void closedHistoryRemainsAccessibleToTheFormerProvider() throws Exception {
        var request=request(ServiceRequestStatus.COMPLETED);
        var review=new Review();review.setRequest(request);review.setReviewer(client);review.setRating((short)5);review=reviews.saveAndFlush(review);
        String oldProviderToken=tokenFor(provider.getEmail(),PASSWORD);as(admin);roles.changeRole(provider.getId(),UserRole.CLIENT);
        mvc.perform(get("/api/requests/"+request.getId()).header("Authorization","Bearer "+oldProviderToken)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/reviews/"+review.getId()).header("Authorization","Bearer "+oldProviderToken)).andExpect(status().isOk());
        mvc.perform(post("/graphql").header("Authorization","Bearer "+oldProviderToken).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("query","{request(id:\""+request.getId()+"\"){status totalPrice}}"))))
                .andExpect(jsonPath("$.data.request.status").value("COMPLETED"));
    }
    @Test void ordinaryUsersCannotInvokeTheInternalAdministrativeOperation() {
        as(client);
        assertThatThrownBy(()->roles.changeRole(client.getId(),UserRole.ADMIN)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(client.getRole()).isEqualTo(UserRole.CLIENT);
    }
}
