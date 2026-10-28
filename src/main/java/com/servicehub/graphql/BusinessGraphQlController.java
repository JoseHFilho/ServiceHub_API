package com.servicehub.graphql;

import com.servicehub.model.ServiceCategory;
import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.model.dto.*;
import com.servicehub.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.graphql.data.method.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;

/** Transport adapter. Business decisions and transactions remain in the REST services. */
@Controller
@Validated
@PreAuthorize("isAuthenticated()")
public class BusinessGraphQlController {
    private final ServiceOfferingService services;
    private final ServiceRequestService requests;
    private final ReviewService reviews;
    private final UserService users;
    public BusinessGraphQlController(ServiceOfferingService services, ServiceRequestService requests,
            ReviewService reviews, UserService users) {
        this.services = services; this.requests = requests; this.reviews = reviews; this.users = users;
    }

    @QueryMapping
    public PageResponse<ServiceResponse> services(@Argument ServiceCategory category, @Argument @Positive Long providerId,
            @Argument @Valid PageInput page) {
        return services.list(category, providerId, page(page).pageable());
    }
    @QueryMapping
    public ServiceResponse service(@Argument @Positive Long id) { return services.get(id); }

    @QueryMapping
    public PageResponse<ProviderResponse> providers(@Argument @Valid PageInput page) { return users.publicProviders(page(page).pageable()); }

    @QueryMapping
    public ProviderResponse provider(@Argument @Positive Long id) { return users.publicProvider(id); }

    @QueryMapping
    public PageResponse<RequestResponse> requests(@Argument ServiceRequestStatus status, @Argument @Positive Long serviceId,
            @Argument @Valid PageInput page) { return requests.list(status, serviceId, page(page).pageable()); }

    @QueryMapping
    public RequestResponse request(@Argument @Positive Long id) { return requests.get(id); }

    @QueryMapping
    public PageResponse<ReviewResponse> reviews(@Argument @Positive Long requestId, @Argument @Positive Long serviceId,
            @Argument @Valid PageInput page) { return reviews.list(requestId, serviceId, page(page).pageable()); }

    @QueryMapping
    public ReviewResponse review(@Argument @Positive Long id) { return reviews.get(id); }

    @MutationMapping
    public ServiceResponse createService(@Argument @Valid ServiceWriteDTO input, @Argument @Positive Long providerId) {
        return services.create(input, providerId);
    }
    @MutationMapping
    public ServiceResponse updateService(@Argument @Positive Long id, @Argument @Valid ServicePatchDTO input) {
        return services.patch(id, input);
    }
    @MutationMapping
    public DeactivationResult deactivateService(@Argument @Positive Long id) {
        services.delete(id);
        return new DeactivationResult(id, false);
    }
    @MutationMapping
    public RequestResponse createRequest(@Argument @Valid RequestCreateDTO input, @Argument @Positive Long clientId) {
        return requests.create(input, clientId);
    }
    @MutationMapping
    public RequestResponse changeRequestStatus(@Argument @Positive Long id, @Argument @Valid RequestStatusDTO input) {
        return requests.changeStatus(id, input);
    }
    @MutationMapping
    public ReviewResponse createReview(@Argument @Valid ReviewCreateDTO input) { return reviews.create(input); }

    @BatchMapping(typeName = "Service", field = "provider")
    public Map<ServiceResponse, ProviderResponse> serviceProviders(List<ServiceResponse> offerings) {
        var providers = users.publicProvidersByIds(offerings.stream().map(ServiceResponse::providerId).distinct().toList());
        // A provider can become inactive between the catalog query and this batch: return null in that case.
        return offerings.stream().filter(item -> providers.containsKey(item.providerId()))
                .collect(Collectors.toMap(item -> item, item -> providers.get(item.providerId()), (first, second) -> first));
    }

    private PageInput page(PageInput input) { return input == null ? PageInput.DEFAULT : input; }
    public record DeactivationResult(Long id, boolean active) { }
}
