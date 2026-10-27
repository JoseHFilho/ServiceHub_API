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
@RequestMapping("/api/services")
@Tag(name = "Serviços", description = "Catálogo ativo e administração dos próprios serviços.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Dados, enum, identificador ou paginação inválidos."),
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido, expirado ou conta inativa."),
    @ApiResponse(responseCode = "403", description = "Papel ou propriedade não autorizados."),
    @ApiResponse(responseCode = "404", description = "Recurso inexistente ou indisponível."),
    @ApiResponse(responseCode = "409", description = "Conflito de negócio, concorrência ou integridade.")
})
public class ServiceOfferingController {
    private final ServiceOfferingService service;
    public ServiceOfferingController(ServiceOfferingService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Cadastrar serviço", description = "PROVIDER é identificado pelo JWT. ADMIN deve representar um PROVIDER ativo pelo parâmetro providerId. Campos internos no JSON não são atribuídos.")
    @ApiResponse(responseCode = "201", description = "Serviço ativo criado; Location informa sua URI.")
    public ResponseEntity<ServiceResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceWriteDTO.class),
                    examples = @ExampleObject(value = "{\"title\":\"Limpeza residencial\",\"description\":\"Limpeza completa\",\"price\":150.00,\"category\":\"LIMPEZA\"}")))
            @Valid @RequestBody ServiceWriteDTO dto,
            @Parameter(description = "Somente ADMIN: prestador ativo representado.", example = "2") @RequestParam(required = false) @Positive Long providerId) {
        var result = service.create(dto, providerId);
        return ResponseEntity.created(URI.create("/api/services/" + result.id())).body(result);
    }

    @GetMapping
    @Operation(summary = "Listar serviços disponíveis", description = "Qualquer usuário autenticado consulta serviços e prestadores ativos. Filtros são cumulativos; ordem por ID crescente.")
    @ApiResponse(responseCode = "200", description = "Página do catálogo autorizado.")
    public PageResponse<ServiceResponse> list(
            @Parameter(description = "Uma das 21 categorias do catálogo.", example = "ENCANAMENTO") @RequestParam(required = false) ServiceCategory category,
            @Parameter(description = "Filtra pelo prestador.", example = "2") @RequestParam(required = false) @Positive Long providerId,
            @Parameter(description = "Página a partir de zero.", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Itens por página, entre 1 e 100.", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(category, providerId, PageRequest.of(page, size, Sort.by("id")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar serviço", description = "Retorna somente serviço e prestador ativos, sem e-mail, credenciais ou dados privados do prestador.")
    @ApiResponse(responseCode = "200", description = "Serviço disponível.")
    public ServiceResponse get(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id) { return service.get(id); }

    @PutMapping("/{id}")
    @Operation(summary = "Substituir campos editáveis do serviço", description = "PROVIDER proprietário ou ADMIN. Título, preço e categoria são obrigatórios; descrição omitida é limpa. Não altera prestador ou preço histórico das solicitações.")
    @ApiResponse(responseCode = "200", description = "Serviço atualizado.")
    public ServiceResponse update(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServiceWriteDTO.class),
                    examples = @ExampleObject(value = "{\"title\":\"Limpeza atualizada\",\"price\":180.00,\"category\":\"LIMPEZA\"}")))
            @Valid @RequestBody ServiceWriteDTO dto) { return service.update(id, dto); }

    @PatchMapping("/{id}")
    @Operation(summary = "Atualizar parcialmente o serviço", description = "PROVIDER proprietário ou ADMIN. Requer ao menos um campo editável. Campos nulos/omitidos são preservados; descrição vazia limpa o texto.")
    @ApiResponse(responseCode = "200", description = "Serviço atualizado.")
    public ServiceResponse patch(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = ServicePatchDTO.class),
                    examples = @ExampleObject(value = "{\"price\":200.00}")))
            @Valid @RequestBody ServicePatchDTO dto) { return service.patch(id, dto); }

    @DeleteMapping("/{id}")
    @Operation(summary = "Desativar serviço", description = "PROVIDER proprietário ou ADMIN. Exclusão lógica preserva solicitações e avaliações. Solicitações PENDING, ACCEPTED ou IN_PROGRESS impedem a operação com 409.")
    @ApiResponse(responseCode = "204", description = "Serviço desativado; corpo vazio.")
    public ResponseEntity<Void> delete(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
