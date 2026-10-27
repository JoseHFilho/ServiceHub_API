package com.servicehub.controller;

import com.servicehub.model.dto.LoginRequest;
import com.servicehub.model.dto.TokenResponse;
import com.servicehub.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Autenticação")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/login")
    @Operation(summary = "Autenticar e obter JWT", description = "Valida e-mail e senha da conta ativa. Retorna JWT assinado para Authorization: Bearer. Credenciais inválidas recebem resposta genérica, sem identificar contas existentes.")
    @ApiResponse(responseCode = "200", description = "JWT emitido; resposta não deve ser armazenada em cache")
    @ApiResponse(responseCode = "400", description = "Dados de login inválidos")
    @ApiResponse(responseCode = "401", description = "Credenciais inválidas ou conta indisponível")
    @ApiResponse(responseCode = "403", description = "Acesso negado por uma política de autorização")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(auth.login(request));
    }
}
