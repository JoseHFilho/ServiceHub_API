package com.servicehub.controller;

import com.servicehub.model.dto.UserDTO;
import com.servicehub.model.dto.UserPatchDTO;
import com.servicehub.model.dto.UserResponse;
import com.servicehub.exception.ApiError;
import com.servicehub.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@Tag(name = "Usuários", description = "Cadastro e manutenção de usuários do ServiceHub.")
@ApiResponse(responseCode = "400", description = "Dados ou identificador inválidos", content = @Content(schema = @Schema(implementation = ApiError.class)))
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Criar usuário", description = "Cadastra um usuário com nome, e-mail único e senha. Normaliza o e-mail e armazena somente o hash da senha.")
    @ApiResponse(responseCode = "201", description = "Usuário criado")
    @ApiResponse(responseCode = "409", description = "E-mail já cadastrado", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "415", description = "Envie o corpo como application/json", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UserResponse create(@RequestBody UserDTO dto) {
        return UserResponse.from(userService.createUser(dto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Buscar usuário por ID", description = "Retorna os dados públicos do usuário. Retorna 404 quando o identificador não existe.")
    @ApiResponse(responseCode = "200", description = "Usuário encontrado")
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UserResponse findById(@Parameter(description = "ID do usuário", example = "1") @PathVariable Long id) {
        return UserResponse.from(userService.findById(id));
    }

    @GetMapping
    @Operation(summary = "Listar usuários", description = "Retorna todos os usuários cadastrados em um array. Retorna um array vazio quando não há registros.")
    @ApiResponse(responseCode = "200", description = "Lista de usuários")
    public List<UserResponse> findAll() {
        return userService.findAll().stream().map(UserResponse::from).toList();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Substituir dados do usuário", description = "Substitui nome, e-mail e senha. Os três campos são obrigatórios. O ID e a data de criação são preservados. Para alterar apenas alguns campos, use PATCH.")
    @ApiResponse(responseCode = "200", description = "Usuário atualizado")
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "E-mail pertence a outro usuário", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "415", description = "Envie o corpo como application/json", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UserResponse update(@Parameter(description = "ID do usuário", example = "1") @PathVariable Long id, @RequestBody UserDTO dto) {
        return UserResponse.from(userService.updateUser(id, dto));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Atualizar parcialmente o usuário", description = "Altera os campos informados e não nulos, preservando os demais. Exige ao menos um campo e rejeita valores em branco.")
    @ApiResponse(responseCode = "200", description = "Usuário atualizado")
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "E-mail pertence a outro usuário", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "415", description = "Envie o corpo como application/json", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public UserResponse patch(@Parameter(description = "ID do usuário", example = "1") @PathVariable Long id, @RequestBody UserPatchDTO dto) {
        return UserResponse.from(userService.patchUser(id, dto));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Excluir usuário", description = "Remove um usuário existente. A resposta de sucesso não tem corpo. Vínculos que impeçam a exclusão retornam 409.")
    @ApiResponse(responseCode = "204", description = "Usuário excluído", content = @Content)
    @ApiResponse(responseCode = "404", description = "Usuário não encontrado", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Usuário possui dados vinculados que impedem a exclusão", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public void delete(@Parameter(description = "ID do usuário", example = "1") @PathVariable Long id) {
        userService.deleteUser(id);
    }
}
