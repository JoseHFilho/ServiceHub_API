package com.servicehub.controller;

import com.servicehub.model.dto.StatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Aplicação", description = "Rotas de identificação e disponibilidade do ServiceHub.")
public class HelloController {

    @GetMapping("/hello")
    @Operation(summary = "Consultar mensagem inicial", description = "Retorna a identificação da aplicação em JSON, com uma mensagem de boas-vindas.")
    @ApiResponse(responseCode = "200", description = "Aplicação disponível")
    public StatusResponse hello() {
        return new StatusResponse("OK", "1.0.0", "ServiceHub API está no ar! 🚀");
    }

    @GetMapping("/status")
    @Operation(summary = "Consultar status da aplicação", description = "Retorna o status e a versão da API em JSON. A saúde do banco pode ser consultada em /actuator/health.")
    @ApiResponse(responseCode = "200", description = "Aplicação disponível")
    public StatusResponse status() {
        return new StatusResponse("OK", "1.0.0", "ServiceHub API operacional");
    }
}
