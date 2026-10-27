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
@RequestMapping("/api/requests")
@Tag(name = "Solicitações", description = "Contratação, histórico privado e transições autorizadas.")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Dados, enum, identificador ou paginação inválidos."),
    @ApiResponse(responseCode = "401", description = "JWT ausente, inválido, expirado ou conta inativa."),
    @ApiResponse(responseCode = "403", description = "Papel ou propriedade não autorizados."),
    @ApiResponse(responseCode = "404", description = "Recurso inexistente ou indisponível."),
    @ApiResponse(responseCode = "409", description = "Conflito de negócio, concorrência ou integridade.")
})
public class ServiceRequestController {
    private final ServiceRequestService service;
    public ServiceRequestController(ServiceRequestService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "Contratar serviço", description = "CLIENT vem do JWT; ADMIN pode representar um CLIENT ativo por clientId. Serviço e prestador devem estar ativos. Preço é copiado do catálogo e estado inicia PENDING; identidades, preço e estado no JSON são ignorados.")
    @ApiResponse(responseCode = "201", description = "Solicitação criada com preço histórico; Location informa sua URI.")
    public ResponseEntity<RequestResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestCreateDTO.class),
                    examples = @ExampleObject(value = "{\"serviceId\":1,\"notes\":\"Portaria recebe o prestador\"}")))
            @Valid @RequestBody RequestCreateDTO dto,
            @Parameter(description = "Somente ADMIN: cliente ativo representado.", example = "3") @RequestParam(required = false) @Positive Long clientId) {
        var result = service.create(dto, clientId);
        return ResponseEntity.created(URI.create("/api/requests/" + result.id())).body(result);
    }

    @GetMapping
    @Operation(summary = "Listar solicitações autorizadas", description = "Cliente e prestador veem somente as solicitações de que participam; ADMIN vê todas. Inclui histórico de serviços desativados, com filtros cumulativos e ordem por ID crescente.")
    @ApiResponse(responseCode = "200", description = "Página de solicitações autorizadas.")
    public PageResponse<RequestResponse> list(
            @Parameter(description = "Filtra pelo estado.", example = "PENDING") @RequestParam(required = false) ServiceRequestStatus status,
            @Parameter(description = "Filtra pelo serviço, sem ampliar a autorização.", example = "1") @RequestParam(required = false) @Positive Long serviceId,
            @Parameter(description = "Página a partir de zero.", example = "0") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Itens por página, entre 1 e 100.", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(status, serviceId, PageRequest.of(page, size, Sort.by("id")));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar solicitação", description = "Somente cliente, prestador participante ou ADMIN. O preço histórico permanece igual após alterações ou desativação do serviço.")
    @ApiResponse(responseCode = "200", description = "Solicitação autorizada.")
    public RequestResponse get(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id) { return service.get(id); }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Alterar estado da solicitação", description = "Prestador ou ADMIN avança PENDING → ACCEPTED → IN_PROGRESS → COMPLETED e pode cancelar qualquer estado aberto. Cliente só cancela PENDING/ACCEPTED. Estados finais e repetições retornam 409; ator sem autorização retorna 403.")
    @ApiResponse(responseCode = "200", description = "Estado atualizado; participantes e preço preservados.")
    public RequestResponse changeStatus(@Parameter(description = "Identificador positivo do recurso.", example = "1") @PathVariable @Positive Long id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", schema = @Schema(implementation = RequestStatusDTO.class),
                    examples = @ExampleObject(value = "{\"status\":\"ACCEPTED\"}")))
            @Valid @RequestBody RequestStatusDTO dto) { return service.changeStatus(id, dto); }
}
