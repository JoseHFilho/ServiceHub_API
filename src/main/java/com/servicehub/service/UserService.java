package com.servicehub.service;

import com.servicehub.exception.EmailAlreadyExistsException;
import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.User;
import com.servicehub.model.dto.UserDTO;
import com.servicehub.model.dto.UserPatchDTO;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.PasswordHashService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class UserService {
    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;

    public UserService(UserRepository userRepository, PasswordHashService passwordHashService) {
        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
    }

    public User createUser(UserDTO dto) {
        requireDto(dto);
        requireText(dto.getFullName(), "O nome completo é obrigatório.");
        requireText(dto.getEmail(), "O e-mail é obrigatório.");

        String fullName = dto.getFullName().trim();
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
    public User findById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário não encontrado: " + id));
    }

    @Transactional(readOnly = true)
    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User updateUser(Long id, UserDTO dto) {
        requireDto(dto);
        User user = findById(id);
        requireText(dto.getFullName(), "O nome completo é obrigatório.");
        requireText(dto.getEmail(), "O e-mail é obrigatório.");
        requireText(dto.getPassword(), "A senha é obrigatória.");
        return applyChanges(user, dto.getFullName(), dto.getEmail(), dto.getPassword());
    }

    public User patchUser(Long id, UserPatchDTO dto) {
        User user = findById(id);
        if (dto == null || (dto.fullName() == null && dto.email() == null && dto.password() == null)) {
            throw new IllegalArgumentException("Informe ao menos um campo para atualizar.");
        }
        return applyChanges(user, dto.fullName(), dto.email(), dto.password());
    }

    private User applyChanges(User user, String nameInput, String emailInput, String passwordInput) {
        if (nameInput != null) {
            requireText(nameInput, "O nome completo é obrigatório.");
            String fullName = nameInput.trim();
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

    public void deleteUser(Long id) {
        userRepository.delete(findById(id));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
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
