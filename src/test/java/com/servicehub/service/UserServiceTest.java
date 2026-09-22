package com.servicehub.service;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.servicehub.exception.EmailAlreadyExistsException;
import com.servicehub.exception.ResourceNotFoundException;
import com.servicehub.model.User;
import com.servicehub.model.dto.UserDTO;
import com.servicehub.repository.UserRepository;
import com.servicehub.security.PasswordHashService;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHashService passwordHashService;

    private UserService userService;

    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        userService = new UserService(userRepository, passwordHashService);
    }

    @Test
    void createsUserWithNormalizedFieldsAndHashedPassword() {
        UserDTO dto = dto(" Ana Prestadora ", " ANA@SERVICEHUB.COM ", "senha123");
        when(userRepository.existsByEmail("ana@servicehub.com")).thenReturn(false);
        when(passwordHashService.hash("senha123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User result = userService.createUser(dto);

        assertThat(result.getFullName()).isEqualTo("Ana Prestadora");
        assertThat(result.getEmail()).isEqualTo("ana@servicehub.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed-password");
        verify(userRepository).save(result);
    }

    @Test
    void rejectsDuplicateEmail() {
        UserDTO dto = dto("Ana Prestadora", "ana@servicehub.com", "senha123");
        when(userRepository.existsByEmail("ana@servicehub.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(dto))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessage("E-mail já cadastrado.");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void rejectsMissingRequiredData() {
        assertThatThrownBy(() -> userService.createUser(null))
                .isInstanceOf(IllegalArgumentException.class);

        UserDTO dto = dto(" ", "email@example.com", "senha123");
        assertThatThrownBy(() -> userService.createUser(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("O nome completo é obrigatório.");
    }

    @Test
    void rejectsInvalidFullNameAndEmail() {
        UserDTO longName = dto("a".repeat(101), "email@example.com", "senha123");
        assertThatThrownBy(() -> userService.createUser(longName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("O nome completo deve ter no máximo 100 caracteres.");

        UserDTO invalidEmail = dto("Ana", "invalid-email", "senha123");
        assertThatThrownBy(() -> userService.createUser(invalidEmail))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Informe um e-mail válido.");
    }

    @Test
    void findsUsersAndThrowsWhenIdDoesNotExist() {
        User user = new User();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findAll()).thenReturn(List.of(user));

        assertThat(userService.findById(7L)).isSameAs(user);
        assertThat(userService.findAll()).containsExactly(user);

        when(userRepository.findById(8L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> userService.findById(8L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Usuário não encontrado: 8");
    }

    @Test
    void replacesAllEditableFields() {
        User user = new User();
        user.setFullName("Nome antigo");
        user.setEmail("old@example.com");
        user.setPasswordHash("old-hash");
        UserDTO dto = dto(" Novo Nome ", " NEW@EXAMPLE.COM ", "nova-senha");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordHashService.hash("nova-senha")).thenReturn("new-hash");
        when(userRepository.save(user)).thenReturn(user);

        User result = userService.updateUser(1L, dto);

        assertThat(result.getFullName()).isEqualTo("Novo Nome");
        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void rejectsEmailConflictDuringUpdate() {
        User user = new User();
        user.setEmail("old@example.com");
        UserDTO dto = dto("Nome completo", "new@example.com", "senha123");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateUser(1L, dto))
                .isInstanceOf(EmailAlreadyExistsException.class);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void deletesExistingUser() {
        User user = new User();
        when(userRepository.findById(3L)).thenReturn(Optional.of(user));

        userService.deleteUser(3L);

        verify(userRepository).delete(user);
    }

    private UserDTO dto(String fullName, String email, String password) {
        UserDTO dto = new UserDTO();
        dto.setFullName(fullName);
        dto.setEmail(email);
        dto.setPassword(password);
        return dto;
    }
}
