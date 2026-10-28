package com.servicehub.controller;

import com.servicehub.model.*;
import com.servicehub.model.dto.*;
import com.servicehub.service.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.*;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reviews")
@Tag(name = "Avaliações", description = "Uma avaliação imutável por solicitação concluída.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Dados, enum, identificador ou paginação inválidos."),
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido, expirado ou conta inativa."),
    @ApiResponse(responseCode = "403", description = "Papel ou propriedade não autorizados."),
    @ApiResponse(responseCode = "404", description = "Recurso inexistente ou indisponível."),
    @ApiResponse(responseCode = "409", description = "Conflito de negócio, concorrência ou integridade.")
})
public class ReviewController {
    private final ReviewService service;
    public ReviewController(ReviewService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Avaliar solicitação concluída", description = "Somente o CLIENT da solicitação ou ADMIN representando esse cliente. Avaliador é derivado da solicitação, nota inteira de 1 a 5 e comentário simples até 2000 caracteres. Solicitação não concluída ou duplicada retorna 409.")
    @ApiResponse(responseCode = "201", description = "Avaliação criada; Location informa sua URI.")
    public ResponseEntity<ReviewResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ReviewCreateDTO.class),
                    examples = @ExampleObject(value = "{\"requestId\":1,\"rating\":5,\"comment\":\"Serviço excelente\"}")))
            @Valid @RequestBody ReviewCreateDTO dto) {
        var result = service.create(dto);
        return ResponseEntity.created(URI.create("/api/reviews/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar avaliação autorizada", description = "Somente avaliador, prestador participante ou ADMIN. Não expõe credenciais, e-mail ou notas privadas da solicitação.")
    @ApiResponse(responseCode = "200", description = "Avaliação autorizada.")
    public ReviewResponse get(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id) { return service.get(id); }

    @GetMapping
    @Operation(summary = "Listar avaliações autorizadas", description = "Avaliador e prestador veem suas avaliações; ADMIN vê todas. Filtros cumulativos por solicitação/serviço preservam o escopo e histórico. Ordem por ID crescente.")
    @ApiResponse(responseCode = "200", description = "Página de avaliações autorizadas.")
    public PageResponse<ReviewResponse> list(
            @Parameter(description = "Filtra pela solicitação.", example = "1") @RequestParam(required = false) @Positive Long requestId,
            @Parameter(description = "Filtra pelo serviço.", example = "1") @RequestParam(required = false) @Positive Long serviceId,
            @Parameter(description = "Página a partir de zero.", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por página, entre 1 e 100.", example = "20") @RequestParam(defaultValue = "20") int size) {
        return service.list(requestId, serviceId, com.servicehub.validation.Pagination.page(page, size));
    }
}
