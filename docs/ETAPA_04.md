# ServiceHub API — Etapa 4: Endpoints REST e regras de negócio

Validação concluída em **27/10/2026**, horário de São Paulo.
Escopo limitado à Etapa 4. Etapa 5, GraphQL e gRPC não foram iniciados.

## Resultado

Foram implementadas **13 operações REST**, com JWT, DTOs, validação, paginação,
autorização por propriedade, preço histórico, máquina de estados, avaliação
única e exclusão lógica. As verificações finais foram executadas, com os
seguintes resultados:

| Execução | Testes/verificações | Falhas | Erros | Ignorados |
| --- | ---: | ---: | ---: | ---: |
| Maven `clean verify`, H2 2.4.240 | 266 | 0 | 0 | 0 |
| PostgreSQL 15.19, suíte completa | 266 | 0 | 0 | 0 |
| PostgreSQL 16.14, suíte completa | 266 | 0 | 0 | 0 |
| JAR em `prod`, HTTP real com PostgreSQL 15.19 | 45 | 0 | 0 | — |

Os **127 cenários anteriores foram preservados**. Foram adicionados 139:
110 REST/MockMvc com JWT assinado, 25 pares de transição e quatro testes de
concorrência com transações independentes. Nenhum teste foi desabilitado.

Spring Boot **4.1.1**, compilação com **release 25**, dependências e autenticação
da Etapa 3 foram mantidos. A execução usou o **JDK 26.0.2.1** disponível nesta
máquina; não foi executada uma JVM 25.

## Análise inicial

Foram consultados os relatórios das Etapas 1–3, a validação PostgreSQL da Etapa 1,
o arquivo `D:/download/servicehub_schema.sql`, a migration V5, as quatro
entidades, seus repositories/services, SecurityConfig, AccessPolicy e as
proteções de método existentes.

As tabelas `users`, `services`, `service_requests` e `reviews` já atendem ao
escopo. Não foi necessária mudança estrutural. Nenhuma migration V6 foi criada
e **V1–V5 permaneceram intactas**, conferidas por SHA-256 em
[evidencias/etapa4-migrations.json](evidencias/etapa4-migrations.json).

## Endpoints

Todos exigem `Authorization: Bearer <JWT>`. O cadastro público de usuários e o
login anteriores mantêm suas regras.

| Método | Caminho | Autorização e resultado |
| --- | --- | --- |
| POST | `/api/services` | PROVIDER próprio ou ADMIN delegado; 201 + Location |
| GET | `/api/services` | Usuário autenticado; catálogo ativo paginado |
| GET | `/api/services/{id}` | Usuário autenticado; 200 ou 404 |
| PUT | `/api/services/{id}` | PROVIDER proprietário ou ADMIN; 200 |
| PATCH | `/api/services/{id}` | PROVIDER proprietário ou ADMIN; 200 |
| DELETE | `/api/services/{id}` | Proprietário ou ADMIN; 204 lógico, 409 se houver pedidos abertos |
| POST | `/api/requests` | CLIENT próprio ou ADMIN delegado; 201 + Location |
| GET | `/api/requests` | Participante ou ADMIN; página dentro do escopo autorizado |
| GET | `/api/requests/{id}` | Cliente, prestador participante ou ADMIN; 200 |
| PATCH | `/api/requests/{id}/status` | Participante autorizado pela transição ou ADMIN; 200 |
| POST | `/api/reviews` | Cliente real da solicitação ou ADMIN; 201 + Location |
| GET | `/api/reviews/{id}` | Avaliador, prestador participante ou ADMIN; 200 |
| GET | `/api/reviews` | Página de avaliações dos participantes; ADMIN vê todas |

As listagens aceitam `page` a partir de zero e `size` entre 1 e 100, com
padrões 0/20 e ordenação por ID crescente. Resposta estável:
`content`, `page`, `size`, `totalElements`, `totalPages`.

Filtros cumulativos:
- Serviços: `category` e `providerId`.
- Solicitações: `status` e `serviceId`.
- Avaliações: `requestId` e `serviceId`.

Os filtros são combinados com a autorização; informar um ID de terceiro não
amplia a visibilidade.

## Identidade, DTOs e validação

A identidade comum vem exclusivamente do JWT. Não há vínculo do JSON com
propriedades internas de entidades JPA.

ADMIN pode representar explicitamente uma conta ativa do papel apropriado:
`POST /api/services?providerId=...` ou
`POST /api/requests?clientId=...`. Esses parâmetros são recusados para usuários
comuns. ADMIN deve informar o representado; não se torna prestador/cliente
implicitamente.

| DTO | Finalidade |
| --- | --- |
| `ServiceWriteDTO` | Título, descrição, preço e categoria para POST/PUT |
| `ServicePatchDTO` | Campos editáveis opcionais para PATCH |
| `ServiceResponse` | Serviço e identificação pública mínima do prestador |
| `RequestCreateDTO` | Serviço, agendamento opcional e observações |
| `RequestStatusDTO` | Estado de destino |
| `RequestResponse` | Participantes, estado e preço histórico |
| `ReviewCreateDTO` | Solicitação, nota e comentário |
| `ReviewResponse` | Avaliação sem credenciais ou observações privadas da solicitação |
| `PageResponse<T>` | Envelope das três listagens |

Limites: título obrigatório até 150 caracteres; descrição/observações até
5000; comentário até 2000; preço positivo com até oito dígitos inteiros e duas
casas decimais; categoria entre os 21 valores existentes; nota inteira de 1 a 5;
IDs positivos; agendamento informado atual ou futuro.

Textos são normalizados em NFC e aparados. A validação `@PlainText` recusa
marcação com `<`/`>` e controles indevidos. São usados `@Valid`, `@NotNull`,
`@NotBlank`, `@Size`, `@Positive`, `@Digits`, `@Min`, `@Max` e validação de
PATCH não vazio. Números fracionários não são truncados para IDs ou notas.

PUT exige título/preço/categoria; descrição omitida é limpa. PATCH preserva
campos nulos/omitidos e exige ao menos um campo editável; descrição vazia limpa
o texto. Identidades, ativação, preço histórico e estado inicial enviados como
campos extras não são atribuídos.

## Regras de negócio e autorização

Serviços exigem prestador ativo com papel PROVIDER. Alterações não transferem
propriedade. O catálogo e a consulta individual ocultam serviços ou prestadores
inativos. A desativação é lógica e bloqueada por solicitações PENDING, ACCEPTED
ou IN_PROGRESS.

Contratação exige CLIENT ativo, serviço/prestador ativos e impede contratar o
próprio serviço. O servidor inicia PENDING e copia o preço do catálogo sob
bloqueio transacional. Cliente, serviço e totalPrice permanecem imutáveis;
alterar o catálogo não altera os registros contratados.

| Transição | Cliente participante | Prestador participante | ADMIN |
| --- | --- | --- | --- |
| PENDING → ACCEPTED | 403 | Permitida | Permitida |
| ACCEPTED → IN_PROGRESS | 403 | Permitida | Permitida |
| IN_PROGRESS → COMPLETED | 403 | Permitida | Permitida |
| PENDING/ACCEPTED → CANCELLED | Permitida | Permitida | Permitida |
| IN_PROGRESS → CANCELLED | 403 | Permitida | Permitida |
| Saída de COMPLETED/CANCELLED ou repetição de estado | Não permitida | Não permitida | Não permitida |

Estados finais não reabrem. Conflitos da máquina de estados retornam 409;
ator sem permissão retorna 403; enum ausente/desconhecido retorna 400.
As 25 combinações de origem/destino foram verificadas por testes.

Avaliações exigem solicitação COMPLETED, cliente real ativo e ausência de
avaliação anterior. ADMIN representa o cliente da solicitação; o registro
continua atribuído a esse cliente. A constraint UNIQUE(request_id) permanece
ativa. Não foram criados endpoints de atualização ou remoção de avaliações.

`@PreAuthorize`, AccessPolicy e verificações dentro dos services atuam em
conjunto com o filtro JWT existente. Usuários inativos continuam bloqueados;
todos os 13 novos caminhos foram testados sem JWT e com token de conta inativa.
Os métodos internos existentes foram preservados e reforçados para não
permitir troca de participantes/preço ou contornar as transições.

Os services convertem entidades para DTOs dentro da transação, com
`open-in-view=false`. Nenhuma entidade JPA é devolvida pelos controllers novos.
O histórico de pedidos/avaliações continua acessível aos participantes e ADMIN
após desativação do serviço.

## Concorrência e erros

`BusinessLocks` usa bloqueios pessimistas e releitura dos registros, na ordem:
usuários por ID, serviço, solicitação e avaliação quando aplicável.
As mutações de UserService também bloqueiam o usuário para coordenar
contratação e desativação. As gravações via DTO não dependem de entidades
recebidas do cliente.

As quatro disputas testadas em H2 e nos dois PostgreSQL confirmam:
- Avaliações simultâneas criam somente uma linha.
- A mesma transição simultânea só é aceita uma vez.
- Contratação e desativação do serviço não são confirmadas simultaneamente.
- Contratação e desativação do cliente não são confirmadas simultaneamente.

GlobalExceptionHandler foi preservado e ganhou resposta genérica 409 para
conflitos de bloqueio/concorrência. Permanecem os contratos 400, 401, 403, 404 e
409 sem expor SQL, credenciais ou detalhes internos. Criação retorna 201 com
Location; exclusão lógica retorna 204 sem corpo.

## Swagger

Os controllers documentam resumo, descrição, parâmetros, respostas, erros e
`bearerAuth`. Os DTOs e corpos incluem exemplos JSON com
`application/json`. A documentação pode ser usada com o botão Authorize
existente. A proteção da documentação em `prod` permanece ADMIN.

A verificação HTTP inicial foi interrompida por uma suposição incorreta do
verificador: um único exemplo foi publicado corretamente no campo OpenAPI
`example`, e a checagem esperava apenas `examples`. O verificador e o teste
aceitam agora ambos os formatos válidos. O tipo de mídia foi explicitado.
Após a correção, as três suítes completas e as 45 verificações HTTP passaram.

## Arquivos da Etapa 4

Criados:
- Controllers: `ServiceOfferingController.java`, `ServiceRequestController.java`,
  `ReviewController.java`.
- Os nove DTOs relacionados acima, em `src/main/java/com/servicehub/model/dto/`.
- `service/BusinessLocks.java`, `service/RequestTransitions.java` e
  `validation/PlainText.java`.
- `BusinessRestIntegrationTests.java`, `BusinessConcurrencyIntegrationTests.java`
  e `service/RequestTransitionsTest.java`.
- `scripts/verify-rest-business.ps1`, este relatório e as evidências da Etapa 4.

Modificados nesta etapa:
- Services de serviços, solicitações, avaliações e usuários.
- Repositories das quatro entidades: especificações, projeções de participantes
  e consulta de usuário com bloqueio.
- `security/AccessPolicy.java`: delegação administrativa explícita.
- `validation/InputNormalizer.java`: normalização de textos longos.
- `exception/GlobalExceptionHandler.java`: conflito de concorrência.
- `application.properties`: desabilita conversão silenciosa de decimal em inteiro.
- `service/UserServiceTest.java`: três stubs ajustados para a consulta com
  bloqueio; os oito cenários existentes foram mantidos.
- `README.md`: escopo, endpoints, exemplos, regras e verificações atuais.

POM, versões de dependências, entidades, enum de categorias, JWT,
SecurityConfig e migrations não precisaram de alterações nesta etapa.
O diretório já continha alterações não commitadas das etapas anteriores;
essas alterações foram preservadas.

## Execução e evidências

Com JAVA_HOME apontando para o JDK válido desta máquina:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.2.1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -o clean verify
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
.\scripts\verify-rest-business.ps1 -ContainerName servicehub-v5-test-de2ea93bf18943919757d423ec5b7e18
~~~

Resultados finais salvos:
- [Maven/H2](evidencias/etapa4-maven-final.json).
- [PostgreSQL 15.19](evidencias/etapa4-postgres15.json).
- [PostgreSQL 16.14](evidencias/etapa4-postgres16.json).
- [45 verificações HTTP](evidencias/etapa4-http.json).
- [Preservação dos fixtures HTTP](evidencias/etapa4-http-fixtures.json).
- [Hashes das migrations](evidencias/etapa4-migrations.json).
- [Preservação dos recursos Docker](evidencias/etapa4-preservacao.json).

Flyway validou e inicializou V1–V5 nos dois PostgreSQL e Hibernate inicializou
com validação do schema. A suíte inclui os testes anteriores de upgrade,
constraints, categorias, índices e preservação de registros.

O fluxo HTTP usou uma instância própria do JAR em `prod`, publicada somente em
loopback e encerrada ao final. As quatro contas dessa execução foram
desativadas pela API. As quatro contas sintéticas da execução inicial
interrompida também foram desativadas logicamente, por IDs exatos no banco de
teste. Oito usuários, dois serviços, duas solicitações concluídas e duas
avaliações continuam registrados, sem exclusão física.

Problemas de construção dos testes novos também foram corrigidos: imports
ambíguos de MediaType, tratamento de leitura do MockMvc e e-mail do fixture com
maiúsculas, corretamente recusado pela constraint V5. Essas falhas intermediárias
não foram contabilizadas como sucesso.

## Docker, dados e pendências

Docker Engine **29.7.2** respondeu normalmente. Foram usadas portas aleatórias
em 127.0.0.1, credenciais sintéticas e armazenamento **tmpfs**, sem volumes da
aplicação. Não houve reset, remoção de containers ou acesso ao banco original.

Os dez containers anteriores preservaram ID, nome, imagem e estado; os seis
volumes mantiveram seus nomes. O container original `servicehub-db` continuou
`exited`. A conferência cobre metadados Docker; o conteúdo do banco original
não foi aberto nem modificado.

Quatro containers desta etapa foram mantidos, correspondentes às duas rodadas
completas de validação:

| Finalidade | Container |
| --- | --- |
| PG15 inicial e HTTP real | `servicehub-v5-test-de2ea93bf18943919757d423ec5b7e18` |
| PG16 inicial | `servicehub-v5-test-bbb5af9b9c2b4a77baded8babfac799c` |
| PG15 final | `servicehub-v5-test-eca10c0eb76b4d149ae63a768aec748a` |
| PG16 final | `servicehub-v5-test-56dfbbaebb8a4e8f8467f1a97cb3eb58` |

**Nenhuma pendência funcional da Etapa 4.** Os containers temporários
permanecem disponíveis para inspeção; eventual remoção fica para autorização
específica. Não foi feita aplicação de migrations ou validação no banco
original. JDK 25 como runtime não foi testado; release 25 foi mantido.
Pacotes ZIP e apresentações antigos não foram regenerados neste escopo.

Nenhum commit ou push foi realizado. O trabalho termina na Etapa 4.
