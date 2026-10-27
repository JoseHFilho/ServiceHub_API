# ServiceHub API

Projeto do checkpoint da disciplina de Desenvolvimento Backend com Java.
Implementa usuários, catálogo de serviços, contratações e avaliações, com JWT,
PostgreSQL, migrações Flyway, validações e documentação interativa OpenAPI.

## Executar após clonar ou descompactar

Requisitos: JDK 25 ou superior, Docker Desktop em execução e acesso à
internet na primeira compilação para baixar as dependências Maven.
Não é necessário instalar Maven separadamente.

No PowerShell, dentro da pasta do projeto:

```powershell
java -version
docker compose up -d --wait
.\mvnw.cmd clean verify
# Exemplo local: gere uma chave sem imprimi-la. Em produção, use uma chave estável gerenciada.
$env:JWT_SECRET = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
java -jar target\servicehub-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

Em Linux/macOS, use `sh ./mvnw clean verify` e barras `/` no caminho do JAR.
Para desenvolvimento, também é possível usar
`.\mvnw.cmd '-Dspring-boot.run.profiles=dev' spring-boot:run`.
O build usa `release 25`; a verificação atual foi executada com JDK 26.0.2.1.
JWT funciona em todos os perfis. O perfil `dev` torna Swagger/OpenAPI públicos;
fora de `dev`/`test`, ou quando `prod` está ativo, a documentação exige ADMIN.
HTTP Basic foi removido. Rotas privadas exigem `Authorization: Bearer <JWT>`.

`JWT_SECRET` é obrigatório: chave aleatória de 32–128 bytes codificada em Base64,
sem fallback. `JWT_TTL` aceita de `1m` a `1h` e usa `15m` por padrão.
`JWT_ISSUER` usa `servicehub-api` por padrão. Mantenha a chave estável entre
reinícios quando quiser preservar tokens ainda válidos; sua troca invalida
assinaturas anteriores. O Java não carrega `.env` automaticamente; exporte
as variáveis. `.env.example` documenta os nomes sem fornecer um segredo pronto.

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>
- Saúde da aplicação e do banco: <http://localhost:8080/actuator/health>
- Mensagem inicial em JSON: <http://localhost:8080/api/hello>

O PostgreSQL 15 usa a porta **55433 no computador** (5432 dentro do container),
banco `servicehub` e credenciais locais definidas no Compose.
O volume Docker mantém os registros após reiniciar o container.
O Flyway aplica V1–V5 e o Hibernate valida o schema sem recriá-lo.
V5 é uma migration Java em `src/main/java/db/migration`, descoberta pelo Flyway
na mesma localização lógica das migrations SQL. V1–V4 foram preservadas.

Se precisar de outra porta, ajuste o banco e a API juntos:

```powershell
$env:POSTGRES_PORT = '55434'
$env:DB_URL = 'jdbc:postgresql://localhost:55434/servicehub'
docker compose up -d --wait
java -jar target\servicehub-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

A API também aceita `DB_USERNAME`, `DB_PASSWORD` e `SERVER_PORT`.
Credenciais diferentes precisam corresponder às do banco escolhido.
Para encerrar: `Ctrl+C` no terminal da API e `docker compose stop`.

## Endpoints implementados

Etapa 4: os 13 novos endpoints de serviços, solicitações e avaliações exigem JWT.
O relatório completo está em [docs/ETAPA_04.md](docs/ETAPA_04.md).

| Método | Caminho | Resultado esperado |
| --- | --- | --- |
| POST | `/api/users` | 201 e usuário criado |
| POST | `/api/auth/login` | 200 e JWT, 400 para dados inválidos, 401 para credenciais inválidas |
| GET | `/api/users` | 200 e array de usuários |
| GET | `/api/users/{id}` | 200 ou 404 |
| PUT | `/api/users/{id}` | 200, substituição completa, ou 404 |
| PATCH | `/api/users/{id}` | 200, atualização parcial, ou 404 |
| DELETE | `/api/users/{id}` | 204, desativação lógica; 404 ou 409 |
| POST | `/api/services` | 201; PROVIDER próprio ou ADMIN representando um prestador |
| GET | `/api/services` | 200; catálogo ativo paginado, filtros `category` e `providerId` |
| GET | `/api/services/{id}` | 200 ou 404 para serviço indisponível |
| PUT / PATCH | `/api/services/{id}` | 200; proprietário PROVIDER ou ADMIN |
| DELETE | `/api/services/{id}` | 204 lógico; 409 se houver solicitações abertas |
| POST | `/api/requests` | 201; CLIENT próprio ou ADMIN representando um cliente |
| GET | `/api/requests` | 200; página privada, filtros `status` e `serviceId` |
| GET | `/api/requests/{id}` | 200; participante ou ADMIN |
| PATCH | `/api/requests/{id}/status` | 200; transição autorizada, 409 se conflitante |
| POST | `/api/reviews` | 201; cliente da solicitação concluída ou ADMIN |
| GET | `/api/reviews` | 200; página privada, filtros `requestId` e `serviceId` |
| GET | `/api/reviews/{id}` | 200; avaliador, prestador participante ou ADMIN |
| GET | `/api/hello` | 200, mensagem em JSON |
| GET | `/api/status` | 200, status e versão em JSON |
| GET | `/actuator/health` | 200/UP ou 503 quando indisponível |
| GET | `/actuator` | Links de monitoramento |
| GET | `/api/csrf` | 200, política de compatibilidade: `required=false`, sem token/cookie; obsoleto |

Cadastro, login, política CSRF, hello, status e health são públicos. Listar usuários exige
`ADMIN`. Buscar, alterar e desativar um usuário exige ser o titular ou `ADMIN`.
Swagger/OpenAPI são públicos somente em `dev`/`test`. O login recebe e-mail e
senha de uma conta ativa e retorna `accessToken`, `tokenType`, `expiresIn` e
`expiresAt`. Nenhuma conta ADMIN é criada automaticamente.

As três listagens novas aceitam `page=0` e `size=20` (máximo 100), ordenam por ID
crescente e retornam `content`, `page`, `size`, `totalElements`, `totalPages`.
Filtros são cumulativos e não ampliam a visibilidade de um usuário.

O PROVIDER que cadastra um serviço e o CLIENT que contrata são identificados
pelo JWT. Campos internos enviados no corpo são ignorados. Somente ADMIN pode
delegar explicitamente com `POST /api/services?providerId=...` ou
`POST /api/requests?clientId=...`, usando uma conta ativa do papel adequado.
Não é permitido contratar o próprio serviço.

O preço deve ser positivo, com até oito dígitos inteiros e duas casas decimais.
O título aceita até 150 caracteres; descrição e observações, até 5000;
comentário de avaliação, até 2000. Os textos são normalizados e devem ser texto
simples, sem marcação HTML. Uma data de agendamento informada deve ser atual ou
futura. `PUT` exige todos os campos obrigatórios; `PATCH` preserva campos nulos
ou omitidos e exige pelo menos um campo editável. Descrição vazia limpa o texto.

Somente o prestador participante ou ADMIN avança
`PENDING → ACCEPTED → IN_PROGRESS → COMPLETED`. O cliente pode cancelar
`PENDING`/`ACCEPTED`; o prestador e ADMIN podem cancelar qualquer estado aberto.
`COMPLETED`/`CANCELLED` são finais. Transição/repetição conflitante retorna 409;
ator sem permissão retorna 403; enum inválido retorna 400.

O preço é copiado do serviço durante a contratação e permanece no histórico.
Só há uma avaliação por solicitação `COMPLETED`, com nota inteira de 1 a 5;
o avaliador é sempre o cliente real, inclusive quando ADMIN o representa.
Não há endpoints de alteração/remoção de avaliação. Desativar um serviço
preserva solicitações/avaliações e exige ausência de solicitações abertas.
O histórico privado continua acessível aos participantes e ADMIN.

Exemplos de corpos JSON (também disponíveis no Swagger):

```json
{"title":"Limpeza residencial","description":"Limpeza completa","price":150.00,"category":"LIMPEZA"}
```

```json
{"serviceId":1,"notes":"Portaria recebe o prestador"}
```

```json
{"status":"ACCEPTED"}
```

```json
{"requestId":1,"rating":5,"comment":"Serviço excelente"}
```

A autenticação usa exclusivamente o header Bearer. Não há autenticação por
cookies, sessão, formulário, HTTP Basic ou token na query string. CSRF foi
dispensado para esse modelo. Tokens não são gravados automaticamente em cookies
e não são persistidos pelo Swagger. Use HTTPS fora do ambiente local.
Ativação e papel atual são consultados no banco em cada requisição; desativar
uma conta ou remover seu papel ADMIN passa a valer imediatamente.

`POST` e `PUT` exigem `fullName`, `email` e `password` não vazios.
`PATCH` exige ao menos um campo não nulo, preserva campos omitidos/nulos e
rejeita campos em branco. `PUT` substitui nome, e-mail e senha, preservando ID,
metadados, perfil, papel e ativação.
O e-mail é único, normalizado para minúsculas e sem espaços externos.
Novas senhas exigem de 8 a 72 caracteres e no máximo 72 bytes em UTF-8.
Recebem hash BCrypt com custo 12 e nunca aparecem nas respostas.
Hashes PBKDF2 legados continuam válidos e são atualizados após autenticação
correta. Senhas legadas longas usam BCrypt com pré-hash SHA-256 identificado,
preservando todos os bytes da senha. Não há conversão em lote.
Novos usuários recebem o papel `CLIENT`. Os DTOs de escrita não aceitam alterações
de papel ou ativação. As respostas incluem `role` e `active`.

`DELETE` preserva o registro e seu histórico, define `is_active=false` e desativa
os serviços do usuário. Solicitações `PENDING`, `ACCEPTED` ou `IN_PROGRESS`, tanto
como cliente quanto como prestador, impedem essa operação com HTTP 409.
Consultas HTTP listam apenas usuários ativos; um usuário desativado retorna 404
e não pode ser atualizado pelas rotas existentes. O e-mail continua reservado.

Exemplo de criação, login e consulta dos próprios dados, em PowerShell:

```powershell
$account = Get-Credential -Message 'E-mail e senha da nova conta CLIENT'
$body = @{fullName='Maria Silva'; email=$account.UserName; password=$account.GetNetworkCredential().Password} | ConvertTo-Json
$user = Invoke-RestMethod -Uri 'http://localhost:8080/api/users' -Method Post -ContentType 'application/json; charset=utf-8' -Body $body
$loginBody = @{email=$account.UserName; password=$account.GetNetworkCredential().Password} | ConvertTo-Json
$authentication = Invoke-RestMethod -Uri 'http://localhost:8080/api/auth/login' -Method Post -ContentType 'application/json; charset=utf-8' -Body $loginBody
$headers = @{Authorization='Bearer ' + $authentication.accessToken}
Invoke-RestMethod -Uri "http://localhost:8080/api/users/$($user.id)" -Headers $headers
```

Erros retornam `timestamp`, `status`, `error`, `message` e `path`:
400 para entrada inválida, 401 para autenticação ausente/inválida, 403 para
acesso negado, 404 para ID inexistente/inativo, 409 para e-mail duplicado
ou vínculos que impeçam uma operação, e 415 para conteúdo não suportado.

## Arquitetura e escopo

`Controller` trata HTTP e converte DTOs. `Service` concentra regras e transações.
`Repository` realiza o acesso JPA. `GlobalExceptionHandler` padroniza os erros.
`OpenApiConfig` configura a documentação, complementada por `@Operation`,
`@Tag`, `@Parameter`, `@ApiResponse` e `@Schema` nos endpoints e DTOs.

Usuários, serviços, solicitações e avaliações possuem endpoints REST.
A Etapa 4 acrescenta os três módulos de negócio, com DTOs, paginação,
autorização por propriedade, máquina de estados e proteção contra gravações
concorrentes. GraphQL, gRPC e Etapa 5 permanecem fora deste escopo.

A adequação ao schema das Aulas 08–12 começou pela **Etapa 1**. Os papéis
`ADMIN`, `PROVIDER` e `CLIENT` já existem no modelo e no banco. A **Etapa 2**
implementa Spring Security, autorização por papel/propriedade, BCrypt com
compatibilidade PBKDF2 e Bean Validation. Consulte o [relatório da Etapa 2](docs/ETAPA_02.md).
A **Etapa 3** acrescenta login JWT, filtro Bearer do Spring Security e consulta
das permissões atuais no banco. Consulte o [relatório da Etapa 3](docs/ETAPA_03.md).
Consulte o [diagnóstico e relatório da Etapa 1](docs/ETAPA_01.md) e a
[validação PostgreSQL concluída](docs/ETAPA_01_VALIDACAO_POSTGRES.md) para conhecer
as decisões e os resultados dos testes executados.

## Verificação

```powershell
.\mvnw.cmd clean verify
# Em outro terminal, com a API em execução:
.\scripts\verify-api.ps1 -Credential (Get-Credential -Message 'Conta ADMIN para login JWT')
```

O primeiro comando compila, executa os testes e gera o JAR.
O segundo testa o fluxo HTTP real, cria usuários com e-mails únicos
e os desativa logicamente ao terminar. Os registros permanecem no banco.
Para salvar o resultado:

```powershell
.\scripts\verify-api.ps1 -Credential (Get-Credential) -ReportPath '.tmp/http-validation.json'
```

Testes automatizados usam **H2 em modo PostgreSQL**, apenas com escopo `test`.
A suíte atual contém **266 testes**: os 127 das Etapas 1–3 e 139 da Etapa 4.
Os novos cenários incluem 110 verificações REST com JWT, 25 pares da máquina
de estados e quatro disputas reais entre transações independentes.
Resultados e evidências de H2/PostgreSQL estão em [docs/ETAPA_04.md](docs/ETAPA_04.md).
O agente Mockito é carregado explicitamente pelo Maven para os testes com JDKs
recentes, sem depender de anexação dinâmica à JVM.

Para executar a suíte completa em PostgreSQL real, com Docker Desktop
disponível e PowerShell 7, use:

```powershell
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
# Avaliação isolada de compatibilidade, sem atualizar o banco existente:
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
```

O verificador cria um container com nome único, porta local aleatória e dados
em memória. Não usa os volumes da aplicação. Uma falha do Docker ou dos testes
interrompe a verificação; testes não são ignorados para produzir sucesso.
Por padrão, mantém o container de teste ao terminar. A remoção automática
somente é habilitada ao informar explicitamente `-KeepTestContainer:$false`.

Após gerar o JAR, o fluxo HTTP de negócio pode ser repetido exclusivamente
em um desses containers temporários:

```powershell
.\scripts\verify-rest-business.ps1 -ContainerName 'servicehub-v5-test-<identificador retornado pelo verificador>'
```

O script exige nome de teste, imagem PostgreSQL 15/16, armazenamento `tmpfs`,
banco `schema_test` e porta em loopback. Inicia o JAR em `prod`, cria fixtures
sintéticos, valida o JWT e os fluxos de negócio, desativa logicamente as contas
ao concluir com sucesso e encerra sua própria instância da API. Os registros
do teste são preservados. O resultado fica em `docs/evidencias/etapa4-http.json`.

Validação da Etapa 1 concluída em 27/10/2026: **44 testes em PostgreSQL 15.19**,
**44 testes em PostgreSQL 16.14** e **44 testes no `clean verify` com H2**,
sem falhas, erros ou testes ignorados. Flyway e Hibernate inicializaram com
sucesso nos dois PostgreSQL. A imagem da aplicação permanece `postgres:15`.

A validação HTTP **anterior à V5** foi executada com **PostgreSQL 15**.
Consulte [o relatório](docs/VALIDACAO.md) e [o roteiro da apresentação](docs/ROTEIRO.md).
A apresentação está em [ServiceHub_API-final.pptx](docs/ServiceHub_API-final.pptx).

## Entrega

O pacote anterior `delivery/ServiceHub_API-entrega.zip` contém o código-fonte, Maven
Wrapper, configuração Docker, testes, evidências e apresentação. `target/`,
logs temporários e dados do banco não fazem parte do pacote.
Esse pacote e a apresentação ainda não foram regenerados com a Etapa 1.

O repositório configurado é <https://github.com/JoseHFilho/ServiceHub_API>.
Para entregar por link, publique também estas alterações e a apresentação.
Para entregar o estado local já preparado, use o ZIP.

Referência de compatibilidade: [SpringDoc para Spring Boot 4](https://springdoc.org/).
