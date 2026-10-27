package com.servicehub.service;

import com.servicehub.exception.EmailAlreadyExistsException;
import com.servicehub.exception.BusinessConflictException;
import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.model.User;
import com.servicehub.model.dto.UserDTO;
import com.servicehub.model.dto.UserPatchDTO;
import com.servicehub.repository.UserRepository;
import com.servicehub.repository.ServiceOfferingRepository;
import com.servicehub.repository.ServiceRequestRepository;
import com.servicehub.security.PasswordHashService;
import com.servicehub.security.AccessPolicy;
import com.servicehub.validation.InputNormalizer;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
@Validated
public class UserService {
    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;
    private final ServiceOfferingRepository serviceOfferingRepository;
    private final ServiceRequestRepository serviceRequestRepository;
    private final AccessPolicy access;

    public UserService(UserRepository userRepository, PasswordHashService passwordHashService,
            ServiceOfferingRepository serviceOfferingRepository, ServiceRequestRepository serviceRequestRepository,
            AccessPolicy access) {
        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
        this.serviceOfferingRepository = serviceOfferingRepository;
        this.serviceRequestRepository = serviceRequestRepository;
        this.access = access;
    }

    @PreAuthorize("permitAll()")
    public User createUser(@Valid @NotNull UserDTO dto) {
        requireDto(dto);
        requireText(dto.getFullName(), "O nome completo é obrigatório.");
        requireText(dto.getEmail(), "O e-mail é obrigatório.");

        String fullName = InputNormalizer.name(dto.getFullName());
        String email = normalizeEmail(dto.getEmail());
        validateUserFields(fullName, email);
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("E-mail já cadastrado.");
        }

        User user = new User();
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPasswordHash(passwordHashService.hash(dto.getPassword()));
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("@access.canAccessUser(#id)")
    public User findById(Long id) {
        access.requireUser(id);
        return userRepository.findById(id)
                .filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<User> findAll() {
        access.requireAdmin();
        return userRepository.findAllByActiveTrue();
    }

    @PreAuthorize("@access.canAccessUser(#id)")
    public User updateUser(Long id, @Valid @NotNull UserDTO dto) {
        access.requireUser(id);
        requireDto(dto);
        User user = findForUpdate(id);
        requireText(dto.getFullName(), "O nome completo é obrigatório.");
        requireText(dto.getEmail(), "O e-mail é obrigatório.");
        requireText(dto.getPassword(), "A senha é obrigatória.");
        return applyChanges(user, dto.getFullName(), dto.getEmail(), dto.getPassword());
    }

    @PreAuthorize("@access.canAccessUser(#id)")
    public User patchUser(Long id, @Valid @NotNull UserPatchDTO dto) {
        access.requireUser(id);
        User user = findForUpdate(id);
        if (dto == null || (dto.fullName() == null && dto.email() == null && dto.password() == null)) {
            throw new IllegalArgumentException("Informe ao menos um campo para atualizar.");
        }
        return applyChanges(user, dto.fullName(), dto.email(), dto.password());
    }

    private User applyChanges(User user, String nameInput, String emailInput, String passwordInput) {
        if (nameInput != null) {
            requireText(nameInput, "O nome completo é obrigatório.");
            String fullName = InputNormalizer.name(nameInput);
            validateFullName(fullName);
            user.setFullName(fullName);
        }
        if (emailInput != null) {
            requireText(emailInput, "O e-mail é obrigatório.");
            String email = normalizeEmail(emailInput);
            validateEmail(email);
            if (!email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
                throw new EmailAlreadyExistsException("E-mail já cadastrado.");
            }
            user.setEmail(email);
        }
        if (passwordInput != null) {
            user.setPasswordHash(passwordHashService.hash(passwordInput));
        }

        return userRepository.save(user);
    }

    @PreAuthorize("@access.canAccessUser(#id)")
    public void deleteUser(Long id) {
        access.requireUser(id);
        User user = findForUpdate(id);
        if (serviceRequestRepository.hasOpenRequestsForUser(id, ServiceRequestStatus.openStatuses())) {
            throw new BusinessConflictException("Usuário possui solicitações em andamento.");
        }
        var offerings = serviceOfferingRepository.findByProviderIdAndActiveTrue(id);
        offerings.forEach(offering -> offering.setActive(false));
        serviceOfferingRepository.saveAll(offerings);
        user.setActive(false);
        userRepository.save(user);
    }

    private String normalizeEmail(String email) {
        return InputNormalizer.email(email);
    }

    private User findForUpdate(Long id) {
        return userRepository.findLockedById(id).filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
    }

    private void requireDto(UserDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("Os dados do usuário são obrigatórios.");
        }
    }

    private void validateUserFields(String fullName, String email) {
        validateFullName(fullName);
        validateEmail(email);
    }

    private void validateFullName(String fullName) {
        if (!fullName.matches("^[^<>\\p{Cntrl}]*$")) {
            throw new IllegalArgumentException("O nome completo contém caracteres não permitidos.");
        }
        if (fullName.length() > 100) {
            throw new IllegalArgumentException("O nome completo deve ter no máximo 100 caracteres.");
        }
    }

    private void validateEmail(String email) {
        if (email.length() > 255 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Informe um e-mail válido.");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
