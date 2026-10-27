package com.servicehub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Segurança")
public class CsrfController {
    @GetMapping("/api/csrf")
    @Deprecated
    @Operation(summary = "Consultar política CSRF", deprecated = true,
            description = "Compatibilidade com clientes da Etapa 2. Não emite cookie nem token CSRF: a API autentica exclusivamente por Authorization: Bearer.")
    public CsrfPolicy csrf() { return new CsrfPolicy(false, "Bearer"); }
    public record CsrfPolicy(boolean required, String authentication) { }
}
