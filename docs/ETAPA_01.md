# Diagnóstico e adequação do banco — 27/10/2026

O pedido foi tratado na ordem indicada: análise do projeto e implementação da
primeira etapa pendente, a Etapa 1. A validação foi concluída em H2, PostgreSQL
15.19 e PostgreSQL 16.14, com 44 testes aprovados em cada execução.
O [relatório da validação real](ETAPA_01_VALIDACAO_POSTGRES.md) registra o
diagnóstico do Docker e as evidências. As etapas seguintes não foram antecipadas.

## Estado inicial verificado

- Branch: `appmod/java-upgrade-20260830185720`.
- HEAD: `d6b0f44`, `feat: prepara entrega do CRUD de usuários`.
- Histórico recente: testes de controller/serviço/hash, atualização do Java,
  documentação e entrega do CRUD. O diretório de trabalho estava limpo e não
  havia alterações staged.
- `pom.xml`: Spring Boot 4.1.1, Java/release 25, Web MVC, JPA, Flyway,
  PostgreSQL JDBC, Actuator, SpringDoc 3.1.1 e Lombok. Testes com os starters
  Spring, Mockito/JUnit e H2. Sem Security, Bean Validation, JWT, GraphQL ou
  Testcontainers configurados.
- `src/main`: controllers de usuários e identificação da API; quatro entidades,
  quatro repositories, quatro services, DTOs de usuário, tratamento global de
  erros e configuração OpenAPI. `ServiceCategory` tinha seis valores em inglês.
- Migrations V1–V4 criavam as quatro tabelas. Faltavam o papel do usuário,
  `services.updated_at`, verificações de preços/categorias/e-mail e índices.
  A FK de prestador permitia exclusão em cascata. `User` não mapeava os campos
  de perfil e ativação já existentes na V1.
- Endpoints disponíveis: CRUD completo e PATCH de `/api/users`,
  `/api/hello`, `/api/status`, Swagger/OpenAPI e health do Actuator.
  Os demais módulos tinham persistência básica, sem controllers HTTP.
- Havia validação manual e hash PBKDF2, mas nenhuma autenticação/autorização.
  A exclusão em `UserService` e `ServiceOfferingService` era física.
- Compose: somente PostgreSQL 15, healthcheck, porta local e volume nomeado.
  Sem Dockerfile e sem serviço de API em container. O volume foi preservado.
- Suíte inicial executada: **26 testes, zero falhas, erros ou ignorados**.

Foram consultados os cinco PPTX fornecidos e o SQL de referência. Os exemplos
das aulas foram tratados como material de apoio. A escolha explícita do pedido
para a Etapa 5 é GraphQL. Não foi adicionado gRPC.

## Mapa de implementação

| Etapa | Situação após esta alteração |
| --- | --- |
| 1 — Banco | Implementada e validada em H2, PostgreSQL 15.19 e 16.14 |
| 2 — Security e validação | Pendente; validação manual e DTOs existentes preservados |
| 3 — JWT | Pendente |
| 4 — REST | CRUD de usuários existente; faltam controllers e regras completas dos demais módulos |
| 5 — GraphQL | Pendente |
| 6 — Testes | Suíte ampliada para a Etapa 1; Security/JWT/GraphQL e Testcontainers ainda pendentes |
| 7 — Otimização | Índices do schema adequados; EXPLAIN/ANALYZE e medição ainda pendentes |
| 8 — Compose | Banco já configurado; container da API e integração completa ainda pendentes |

## Etapa 1: funcionalidades e decisões

### Migration nova

`src/main/java/db/migration/V5__Align_reference_schema.java` é uma migration
Java Flyway, encontrada em `classpath:db/migration`. V1–V4 não foram editadas.
A implementação em Java permite executar verificações antes de qualquer
alteração de dados, descobrir os nomes reais das FKs via JDBC e manter a suíte
H2 usando a mesma migration do PostgreSQL.

Antes de modificar o banco, V5 verifica colisões de e-mails após normalização,
preços não positivos em serviços/solicitações e categorias desconhecidas.
Se encontrar um problema, falha com uma mensagem que não expõe registros,
e-mails ou hashes. Nenhum usuário é fundido ou removido e nenhum preço é
substituído por um valor inventado. A correção dos dados exige revisão antes
de uma nova tentativa.

No PostgreSQL, as quatro tabelas ficam bloqueadas durante a migration para
evitar gravações entre a conferência e as alterações. A execução é transacional
por padrão do Flyway. Isso pode bloquear requisições durante a implantação e
deve ser considerado ao escolher a janela de execução. H2 não oferece a mesma
garantia de rollback de DDL e é usado somente em testes.

V5 aplica:

- `users.role VARCHAR(20) NOT NULL DEFAULT 'CLIENT'`, com CHECK para os três
  papéis. Quem já possui serviços recebe `PROVIDER`; os demais recebem
  `CLIENT`. Ninguém recebe `ADMIN` automaticamente. Um prestador que também
  tenha sido cliente conserva seu histórico, com papel único `PROVIDER`.
- E-mail em minúsculas e sem espaços externos, mantendo a unicidade existente,
  e CHECK de minúsculas conforme a referência.
- Conversão explícita de `CLEANING→LIMPEZA`, `ELECTRICAL→ELETRICA`,
  `GARDENING→JARDINAGEM`, `PAINTING→PINTURA`, `PLUMBING→ENCANAMENTO` e
  `OTHER→OUTROS`. As 21 categorias de referência passam a ser aceitas no enum
  e no CHECK. Valores já no formato final são mantidos.
- `services.updated_at`, preenchido inicialmente com `created_at`, com default
  e NOT NULL. Novas alterações JPA atualizam o timestamp.
- CHECK de preço positivo em serviços e snapshots de solicitações.
- Cinco FKs com `ON DELETE RESTRICT`, substituindo as anteriores sem apagar
  registros. As constraints existentes de status, rating, e-mail único e
  avaliação única por solicitação são reutilizadas, sem duplicação.
- Remoção do índice simples `idx_users_email`, redundante com UNIQUE(email).
  O índice de prestador é mantido. No PostgreSQL, o índice de categoria passa
  a ser parcial, `WHERE is_active`, conforme o SQL de referência. H2 mantém
  o índice completo porque não suporta índices parciais.
- Índices `idx_requests_service`, `idx_requests_client`, `idx_requests_status`
  e `idx_reviews_reviewer`, ausentes nas migrations anteriores.

Após aplicar V5 em qualquer ambiente, preserve seu código e crie V6 para
evoluções. Migrations Java não têm checksum automático por padrão do Flyway.

### Entidades e comportamento da API

`User` agora mapeia papel, telefone, bio, avatar e ativação. Novos usuários
começam ativos com papel `CLIENT`. `UserDTO` e `UserPatchDTO` continuam
restritos aos campos de escrita existentes. Os testes verificam que incluir
`role`, `active` ou `passwordHash` no JSON não altera essas propriedades.
`UserResponse` preserva os campos anteriores e acrescenta `role` e `active`.

`DELETE /api/users/{id}` passa a desativar o usuário e seus serviços dentro da
mesma transação. Solicitações abertas do usuário como cliente ou prestador
impedem a operação com 409. Os estados abertos são `PENDING`, `ACCEPTED` e
`IN_PROGRESS`. Pedidos encerrados e avaliações permanecem vinculados.
O e-mail de um usuário desativado continua reservado.

Listagem e busca pelo service retornam apenas usuários ativos. Busca,
PUT/PATCH e nova exclusão de usuário inativo retornam 404. O HTTP 204 do DELETE
é preservado. O service de ofertas também desativa em vez de excluir, oculta
inativos em suas consultas públicas e bloqueia a desativação com pedidos
abertos. Ainda não há novos endpoints HTTP de serviços.

Autorização por papel não foi implementada nesta etapa. Não se deve interpretar
a existência de `role` no modelo como proteção das rotas. A Etapa 2 continua
necessária. Os hashes PBKDF2 foram preservados sem rehash em lote. A evolução
para BCrypt deve verificar o formato legado no login e atualizar o hash após
autenticação válida, quando a senha estiver disponível, conforme a Etapa 2.

### PostgreSQL e dados existentes

`compose.yaml` e seu volume nomeado não foram modificados. A imagem continua
PostgreSQL 15. A compatibilidade da V5 foi confirmada pela execução real da
suíte nos PostgreSQL 15.19 e 16.14.
O verificador permite avaliar 15 e 16 separadamente. Não se deve conectar uma
imagem de major diferente ao volume existente como forma de atualização.

O SQL de referência não foi executado sobre banco existente. Nenhuma migration
foi aplicada no banco da aplicação durante esta sessão.

## Arquivos criados ou modificados

Criados:

- `src/main/java/db/migration/V5__Align_reference_schema.java`
- `src/main/java/com/servicehub/model/UserRole.java`
- `src/main/java/com/servicehub/exception/BusinessConflictException.java`
- `src/test/java/com/servicehub/SchemaUpgradeTests.java`
- `src/test/java/com/servicehub/DeactivationIntegrationTests.java`
- `scripts/verify-schema-postgres.ps1`
- `docs/ETAPA_01.md`

Modificados:

- `pom.xml`: agente Mockito explícito via plugins gerenciados pelo Spring Boot.
  Não houve atualização de versões nem adição de dependência de aplicação.
- `User`, `ServiceOffering`, `ServiceCategory`, `ServiceRequestStatus` e
  `UserResponse`: campos e enums alinhados ao schema.
- `UserRepository`, `ServiceOfferingRepository`, `ServiceRequestRepository`:
  consultas de ativação e pedidos abertos.
- `UserService` e `ServiceOfferingService`: exclusão lógica.
- `GlobalExceptionHandler`: conflitos de negócio com 409.
- `UserController`: descrições OpenAPI de listagem e exclusão lógica.
- `MigrationIntegrationTests`, `RepositoryIntegrationTests`, `UserServiceTest`:
  expectativas atualizadas para a quinta migration, categorias e exclusão
  lógica, mantendo a cobertura anterior.
- `scripts/verify-api.ps1`: mensagem final coerente com retenção dos registros.
- `README.md`: execução, estado da etapa e limitações da validação.

**Endpoints adicionados:** nenhum. Os contratos HTTP existentes foram mantidos,
com as mudanças de comportamento e os campos adicionais descritos acima.

## Testes executados e resultados

O ambiente tinha JDK 26.0.2.1. Ele foi selecionado via `JAVA_HOME`, mantendo
`release 25` e Spring Boot 4.1.1 no projeto. Não foi executado um JDK 25 nesta
sessão. O wrapper usa Maven 3.9.16 e as dependências estavam no cache local.

1. Baseline: **26 testes**, zero falhas/erros/ignorados.
2. Após a implementação inicial: **26 testes**, zero falhas/erros/ignorados.
3. Os testes novos revelaram um problema de sessão fechada do H2 ao avaliar
   CHECK IN criado em outra conexão. O fixture de upgrade passou a manter
   uma conexão aberta até o fim do cenário. As constraints não foram removidas.
4. Também foram corrigidas duas asserções dos testes novos: comparação de
   `short` com `int` nos metadados e precisão de timestamp após persistência.
5. Verificação final: **`.\mvnw.cmd -o clean verify`**, BUILD SUCCESS,
   **44 testes, zero falhas, zero erros e zero ignorados**, em aproximadamente
   1 min 10 s. O JAR foi gerado em `target/servicehub-api-0.0.1-SNAPSHOT.jar`.

| Suíte | Testes |
| --- | ---: |
| HelloControllerTest | 2 |
| PasswordHashServiceTest | 3 |
| UserServiceTest | 8 |
| UserControllerIntegrationTests | 7 |
| OpenApiIntegrationTests | 2 |
| RepositoryIntegrationTests | 2 |
| MigrationIntegrationTests | 1 |
| ServicehubApiApplicationTests | 1 |
| SchemaUpgradeTests | 9 |
| DeactivationIntegrationTests | 9 |
| **Total** | **44** |

Os 18 testes novos cobrem upgrade V4→V5 com dados, normalização sem colisões,
abortos seguros, mapeamento das categorias, constraints, FKs restritivas,
índices, preservação de perfil/hash/snapshot/histórico, ativação e estados que
bloqueiam exclusão lógica, HTTP 404 e proteção das propriedades nos DTOs.
O contexto Spring inicializa com Flyway e `ddl-auto=validate`.

O Mockito tentava anexar dinamicamente seu agente à JVM e a execução inicial
ficou sem avançar nessa etapa. O carregamento explícito no Surefire resolveu
o problema e permite usar o comando normal do Maven.

### Bloqueio da primeira verificação PostgreSQL e resolução posterior

O Docker Desktop foi iniciado para a verificação isolada, mas falhou na
inicialização do endpoint transitório `dockerInference`. O log local registrou
`The file cannot be accessed by the system` e o daemon não ficou disponível.
Não foram usados reset de fábrica, remoção de dados/volumes ou alteração de
configurações do Docker para contornar o problema.

Na primeira rodada, o teste com PostgreSQL não executou e não foi contabilizado
como aprovado. Na continuação, o Engine já estava operacional. A suíte completa
foi executada com sucesso nas duas versões, conforme o relatório complementar.
O script tem timeout para falhas do daemon e retorna erro quando Docker ou os
testes falham. Para repetir a verificação preservando os containers de teste:

```powershell
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
```

É necessário PowerShell 7, JDK compatível e Docker Desktop funcional.
O script cria um container exclusivo com PostgreSQL, banco e schemas de teste,
usa porta aleatória em loopback, senha temporária em variáveis de ambiente e
armazenamento em memória. Mantém o container por padrão e restaura as variáveis
temporárias. Não usa os volumes da aplicação. A remoção do container temporário
exige a opção explícita `-KeepTestContainer:$false`.

EXPLAIN/EXPLAIN ANALYZE, teste HTTP com a API empacotada em PostgreSQL e upgrade
real do banco existente não foram executados. As evidências em `docs/VALIDACAO.md`
e os artefatos anteriores de entrega continuam sendo históricos anteriores à V5.

## Pendências e Git

O verificador PostgreSQL foi executado e a Etapa 1 está validada. A próxima
implementação, fora do escopo desta validação, é a Etapa 2: Spring Security,
Bean Validation, autorização e migração compatível de hashes para BCrypt.
JWT, REST dos demais módulos, GraphQL, Testcontainers, análise de consultas e
Compose completo seguem na ordem solicitada.

Nenhum teste foi desativado. Nenhuma migration V1–V4 foi alterada. Não houve
commit, push, force-push ou reset. As alterações permanecem na branch inicial
para revisão. Mensagem de commit sugerida:

```text
feat: adequa schema e implementa exclusao logica na etapa 1
```

## Referências técnicas consultadas

- [Flyway: migrations Java](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/java-based-migrations)
- [Flyway: transações de migrations](https://documentation.red-gate.com/fd/migration-transaction-handling-273973399.html)
- [PostgreSQL 15: CREATE INDEX e índices parciais](https://www.postgresql.org/docs/15/sql-createindex.html)
- [Mockito: configuração do agente](https://github.com/mockito/mockito/blob/main/mockito-core/src/main/java/org/mockito/Mockito.java)
