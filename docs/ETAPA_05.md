# ServiceHub API — Etapa 5: integração GraphQL

Concluída em **27/10/2026**, horário de São Paulo.
Escopo exclusivamente GraphQL; Etapa 6 e gRPC não foram iniciados.

## Resultado

O endpoint **POST /graphql** oferece oito queries e seis mutations,
autenticadas pelo mesmo Bearer JWT do REST. As mutations chamam os services
já existentes; regras, transações, preço histórico, máquina de estados,
propriedade, avaliação única e exclusão lógica são compartilhados.

| Execução real | Testes/verificações | Falhas | Erros | Ignorados |
| --- | ---: | ---: | ---: | ---: |
| Maven clean verify, H2 2.4.240 | 360 | 0 | 0 | 0 |
| PostgreSQL 15.19, suíte completa | 360 | 0 | 0 | 0 |
| PostgreSQL 16.14, suíte completa | 360 | 0 | 0 | 0 |
| JAR em prod, HTTP GraphQL/REST com PG15.19 | 83 | 0 | 0 | — |

Os **266 testes anteriores foram preservados**, com as mesmas quantidades
por suíte, e **94 novos** foram adicionados. Não houve testes desabilitados
ou ignorados. O build gerou o JAR, e Flyway/Hibernate inicializaram com
validação do schema nos dois PostgreSQL.

Spring Boot permanece **4.1.1**, Java/release **25**.
Foram usados Spring for GraphQL **2.0.5** e GraphQL Java **25.0**, versões
gerenciadas pelo BOM existente, sem fixar versões alternativas.
A execução usou **JDK 26.0.2.1**; não foi executada uma JVM 25.

## Análise inicial e preservação

Foram lidos os relatórios das Etapas 1–4, inspecionados controllers,
services, repositories, DTOs, JWT, AccessPolicy e as migrations V1–V5.
O diretório iniciou limpo, na branch codex/atualizacao-etapas-1-a-4,
commit 33797874b5fe7eac75dae23b3e16ff6abc048e53, que já estava publicado
antes deste novo pedido.

O modelo de dados atende ao GraphQL. Nenhuma migration nova foi criada;
**V1–V5 mantêm os mesmos SHA-256**, conforme
[evidencias/etapa5-migrations.json](evidencias/etapa5-migrations.json).
Nenhum endpoint REST foi removido. Não houve alteração das regras de
contratação, transição, avaliações ou exclusão lógica.

## Schema, queries e mutations

Schema:
[src/main/resources/graphql/servicehub.graphqls](../src/main/resources/graphql/servicehub.graphqls).

O schema define Service, Provider, ServiceRequest, Review,
DeactivationResult, quatro tipos de página, PageInput, cinco entradas de
negócio, os enums existentes e scalars Decimal/LocalDateTime/Long.

| Query | Finalidade |
| --- | --- |
| services(category, providerId, page) | Catálogo ativo, filtros e paginação |
| service(id) | Detalhes de serviço disponível |
| providers(page) | Prestadores ativos, projeção pública mínima |
| provider(id) | Prestador ativo individual |
| requests(status, serviceId, page) | Solicitações no escopo autorizado |
| request(id) | Solicitação autorizada individual |
| reviews(requestId, serviceId, page) | Avaliações autorizadas e filtradas |
| review(id) | Avaliação autorizada individual |

| Mutation | Método existente reutilizado |
| --- | --- |
| createService(input, providerId) | ServiceOfferingService.create |
| updateService(id, input) | ServiceOfferingService.patch |
| deactivateService(id) | ServiceOfferingService.delete |
| createRequest(input, clientId) | ServiceRequestService.create |
| changeRequestStatus(id, input) | ServiceRequestService.changeStatus |
| createReview(input) | ReviewService.create |

updateService segue o PATCH: omitidos/nulos preservados, descrição vazia
limpa o texto e pelo menos um campo editável obrigatório. A desativação
retorna ID/active:false e preserva os registros históricos.

Foram reutilizados ServiceWriteDTO, ServicePatchDTO, ServiceResponse,
RequestCreateDTO, RequestStatusDTO, RequestResponse, ReviewCreateDTO,
ReviewResponse e PageResponse. ProviderResponse é uma projeção nova com
somente ID, nome público e avatar; PageInput adapta a paginação GraphQL.

Os exemplos completos, corpos JSON, queries, mutations, tipos e regras
estão no [guia GraphQL](GRAPHQL.md).

## JWT, autorização e privacidade

O filtro JWT existente continua responsável pela assinatura, validade,
usuário ativo e papel atual no banco. GraphQL não introduz login, sessão,
cookie, Basic ou algoritmo alternativo. O endpoint exige Authorization:
Bearer; os resolvers e services também usam PreAuthorize e AccessPolicy.

PROVIDER cria seu próprio serviço. CLIENT contrata em sua própria identidade.
Somente ADMIN pode delegar explicitamente providerId/clientId nos argumentos
separados, com as mesmas verificações dos services REST. Papel ou ID enviado
não é prova de identidade. Campos internos ausentes no schema são recusados.

Prestadores só modificam serviços próprios; solicitações são privadas para
cliente/prestador participante e ADMIN. Avaliações respeitam o mesmo escopo;
somente o cliente real ou ADMIN registra uma avaliação, sempre atribuída ao
cliente da solicitação.

Não há entidade JPA ou tipo User exposto no GraphQL. Provider não contém
email, telefone, senha, hash ou papel. Notas de solicitação só aparecem no
tipo privado autorizado. Os DTOs são montados dentro das transações dos
services, mantendo open-in-view=false.

## Validação e erros GraphQL

Bean Validation e normalização dos DTOs existentes foram mantidos.
PageInput limita página/tamanho e também evita offset que exceda o limite
aceito pelo JPA. IDs são positivos. Datas usam LocalDateTime local ISO;
agendamento deve ser presente/futuro.

Decimal usa string decimal exata, como "150.00", evitando arredondamento
binário na entrada e saída. Strings científicas/formatos indevidos e números
Float/Double são recusados pelo scalar. O preço continua sujeito às regras
compartilhadas de positividade e precisão do DTO.

GraphQlErrorResolver produz erros de campo com extensions.code para
BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND e CONFLICT.
Consultas podem retornar dados parciais; falhas de negócio não recebem
automaticamente os status 403/404/409 do REST.

O interceptor sanitiza erros globais de parsing/schema/variáveis.
Exceções inesperadas seguem o INTERNAL_ERROR opaco do Spring.
Nenhum erro de resposta revela SQL, stack trace ou credenciais.

Rejeições de transporte continuam possíveis: 401 para JWT inválido/ausente,
400 para requisição inválida e 413 para corpo excessivo.
SecurityErrorHandler conserva o formato REST nas rotas existentes e usa
errors no endpoint GraphQL. O corpo excessivo usa PAYLOAD_TOO_LARGE.

## Proteções contra consultas abusivas

| Controle | Limite |
| --- | ---: |
| Corpo completo, inclusive variables/chunked | 65.536 bytes |
| Documento | 16.384 caracteres |
| Profundidade | 8 |
| Complexidade ponderada | 2000 |
| Aliases expandidos | 10 |
| Campos raiz de query | 10 |
| Seleções expandidas, incluindo fragments | 200 |
| Operações por documento | 1 |
| Campos raiz de mutation | 1 |
| Itens por página | 1–100 |

GraphQL Java aplica profundidade/complexidade; GraphQlLimits restringe
documento, aliases, fragments, operações e seleções. O custo das páginas
considera o tamanho informado, inclusive por variáveis, com padrão 20.
As verificações também foram testadas com documentos em cache.

Múltiplas mutations no mesmo documento são recusadas antes da primeira
gravação, inclusive com aliases. Arrays de requisições JSON são recusados.
O filtro de corpo lê no máximo o limite mais um byte, inclusive sem
Content-Length, e não impõe esse limite às rotas REST.

## N+1 e ferramentas de desenvolvimento

As três consultas paginadas existentes ganharam EntityGraph das relações
necessárias para compor seus DTOs. Service.provider usa BatchMapping com
DataLoader e uma projeção pública em lote no UserRepository/UserService.

Um teste com vários prestadores distintos verifica **no máximo quatro
comandos SQL por página**: autenticação, consulta com JOIN, contagem e
lote de prestadores. O cenário passou em H2, PostgreSQL 15 e 16.

GraphiQL e schema printer estão desabilitados por padrão e suas rotas são
negadas em produção mesmo se as propriedades tentarem habilitá-los.
Introspecção só é permitida em dev/test, com JWT e limites.
Prod prevalece quando combinado com test/dev; há teste específico com
propriedades de desenvolvimento explicitamente habilitadas.

## Testes adicionados

| Suíte nova | Cenários |
| --- | ---: |
| GraphQlIntegrationTests | 60 |
| GraphQlProductionSecurityIntegrationTests | 1 |
| GraphQlLimitsAndScalarsTest | 19 |
| GraphQlErrorResolverTest | 10 |
| GraphQlBodyLimitFilterTest | 4 |
| Total novo | 94 |

Foram usados JUnit Jupiter, Mockito, MockMvc e HttpGraphQlTester.
A integração atravessa a cadeia JWT real. Os casos cobrem leituras, filtros,
projeção pública, mutações, propriedade, papéis, inativos, DTOs, scalars,
preço histórico, máquina de estados, avaliação duplicada, dados parciais,
erros sanitizados, limites, produção e interoperabilidade REST/GraphQL.

As 83 checagens HTTP usaram o **JAR empacotado em prod**, em uma porta
aleatória de loopback, com PostgreSQL 15 isolado. O roteiro:
- Cadastra fixtures e faz login pelo REST.
- Contrata, atualiza, avança estado e avalia pelo GraphQL.
- Confere os mesmos registros pelo REST.
- Testa autorização, privacidade, conflitos e limites.
- Desativa logicamente serviço e contas, preservando histórico.
- Confirma rejeição do JWT de conta inativa e encerra sua própria API.

As quatro contas sintéticas terminaram inativas. Serviço, solicitação
COMPLETED com snapshot 150.00 e avaliação continuam registrados.
A conferência SQL foi somente no container de teste identificado.

## Arquivos criados e modificados

Novos:
- Oito arquivos em src/main/java/com/servicehub/graphql:
  BusinessGraphQlController, PageInput, GraphQlConfiguration, GraphQlScalars,
  GraphQlLimits, GraphQlErrorResolver, GraphQlResponseInterceptor,
  GraphQlBodyLimitFilter.
- model/dto/ProviderResponse.java.
- resources/graphql/servicehub.graphqls e application-dev.properties.
- As cinco classes de teste relacionadas acima.
- scripts/verify-graphql.ps1.
- docs/GRAPHQL.md, este relatório e sete evidências etapa5.

Modificados:
- pom.xml: starters GraphQL/runtime e GraphQL/test gerenciados; spring-webflux
  apenas no escopo test para o adaptador MockMvcWebTestClient.
- SecurityConfig: proteção das ferramentas GraphQL em produção.
- SecurityErrorHandler: envelope de autenticação exclusivo de /graphql.
- UserRepository/UserService: leitura pública paginada e em lote de prestadores.
- ServiceOfferingRepository, ServiceRequestRepository e ReviewRepository:
  EntityGraph nas listagens existentes.
- application.properties e application-test.properties: opções GraphQL.
- README.md: uso, escopo e contagem atual de testes.

Não foram alteradas as implementações de negócio dos três services REST,
as entidades, o algoritmo JWT ou as migrations.

## Comandos e evidências

Ambiente desta validação:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.2.1'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -o clean verify
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
.\scripts\verify-graphql.ps1 -ContainerName servicehub-v5-test-30b26618986a42beb200133428fcc6cd
~~~

As novas dependências foram baixadas antes do build offline. Para repetir em
outro ambiente, use o nome retornado pelo verificador PostgreSQL da execução.

- [Maven/H2](evidencias/etapa5-maven-final.json)
- [PostgreSQL 15.19](evidencias/etapa5-postgres15.json)
- [PostgreSQL 16.14](evidencias/etapa5-postgres16.json)
- [83 verificações HTTP](evidencias/etapa5-http.json)
- [Preservação dos fixtures HTTP](evidencias/etapa5-http-fixtures.json)
- [Migrations intactas](evidencias/etapa5-migrations.json)
- [Recursos Docker preservados](evidencias/etapa5-preservacao.json)

## Dificuldades, limites e pendências

A validação GraphQL não passa pelo binding Jackson do REST; por isso foram
usados scalars explícitos e Bean Validation nos argumentos/DTOs.
O contexto de segurança nos resolvers e no lote foi validado pelo transporte
HTTP real. Uma colisão do nome de um helper de teste com status() do MockMvc
foi corrigida antes da execução dos cenários; a compilação intermediária não
foi contabilizada como sucesso.

Não há pendência funcional ou bloqueio da Etapa 5. Limitações deliberadas:
- A JVM 25 não foi executada; release 25 foi preservado.
- Limites são por requisição, sem quota distribuída/rate limiter de gateway.
- Introspecção profunda de ferramentas pode exceder os limites; o SDL local
  está disponível para os clientes.
- Não foram implementados subscriptions, WebSocket, federation ou gRPC.
- Não houve validação/migration no banco original, nem regeneração de ZIP/PPTX.

A Etapa 6 permanece não iniciada. A suíte de 360 testes e os verificadores
ficam disponíveis como base; automação adicional de CI, matriz de JVMs e
políticas de infraestrutura dependem do escopo do próximo pedido.

Docker Engine 29.7.2 estava funcional. Os **14 containers anteriores**
preservaram ID/nome/imagem/estado e os **6 volumes** mantiveram seus nomes.
O banco original servicehub-db continuou parado e não foi aberto.
Dois containers novos, sem volumes, permanecem para inspeção:
- PG15: servicehub-v5-test-30b26618986a42beb200133428fcc6cd.
- PG16: servicehub-v5-test-b755b1ba613c4d4bbc9c121a9b300927.

Nenhum container ou volume foi excluído. Não houve reset do Docker,
commit ou push nesta etapa. As alterações ficam locais para revisão.

## Referências consultadas

- [Spring Boot: integração GraphQL](https://docs.spring.io/spring-boot/reference/web/spring-graphql.html)
- [Spring GraphQL: execução, contexto e erros](https://docs.spring.io/spring-graphql/reference/request-execution.html)
- [Spring GraphQL: segurança](https://docs.spring.io/spring-graphql/reference/security.html)
- [Spring GraphQL: testes](https://docs.spring.io/spring-graphql/reference/testing.html)
- [GraphQL Java: limites](https://www.graphql-java.com/documentation/master/limits/)

As APIs foram também conferidas nos JARs efetivamente resolvidos pelo
BOM 4.1.1, preservando as versões acima.
