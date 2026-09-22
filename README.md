# ServiceHub API

Projeto do checkpoint da disciplina de Desenvolvimento Backend com Java.
Implementa CRUD de usuários de uma plataforma de serviços, com PostgreSQL,
migrações Flyway, validações e documentação interativa OpenAPI.

## Executar após clonar ou descompactar

Requisitos: JDK 25 (versão validada), Docker Desktop em execução e acesso à
internet na primeira compilação para baixar as dependências Maven.
Não é necessário instalar Maven separadamente.

No PowerShell, dentro da pasta do projeto:

```powershell
java -version
docker compose up -d --wait
.\mvnw.cmd clean verify
java -jar target\servicehub-api-0.0.1-SNAPSHOT.jar
```

Em Linux/macOS, use `sh ./mvnw clean verify` e barras `/` no caminho do JAR.
Para desenvolvimento, também é possível usar `.\mvnw.cmd spring-boot:run`.

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>
- OpenAPI YAML: <http://localhost:8080/v3/api-docs.yaml>
- Saúde da aplicação e do banco: <http://localhost:8080/actuator/health>
- Mensagem inicial em JSON: <http://localhost:8080/api/hello>

O PostgreSQL 15 usa a porta **55433 no computador** (5432 dentro do container),
banco `servicehub`, usuário `servicehub` e senha local `secret`.
O volume Docker mantém os registros após reiniciar o container.
O Flyway aplica V1–V4 e o Hibernate valida o schema sem recriá-lo.

Se precisar de outra porta, ajuste o banco e a API juntos:

```powershell
$env:POSTGRES_PORT = '55434'
$env:DB_URL = 'jdbc:postgresql://localhost:55434/servicehub'
docker compose up -d --wait
java -jar target\servicehub-api-0.0.1-SNAPSHOT.jar
```

A API também aceita `DB_USERNAME`, `DB_PASSWORD` e `SERVER_PORT`.
Credenciais diferentes precisam corresponder às do banco escolhido.
Para encerrar: `Ctrl+C` no terminal da API e `docker compose stop`.

## Endpoints implementados

| Método | Caminho | Resultado esperado |
| --- | --- | --- |
| POST | `/api/users` | 201 e usuário criado |
| GET | `/api/users` | 200 e array de usuários |
| GET | `/api/users/{id}` | 200 ou 404 |
| PUT | `/api/users/{id}` | 200, substituição completa, ou 404 |
| PATCH | `/api/users/{id}` | 200, atualização parcial, ou 404 |
| DELETE | `/api/users/{id}` | 204 sem corpo ou 404 |
| GET | `/api/hello` | 200, mensagem em JSON |
| GET | `/api/status` | 200, status e versão em JSON |
| GET | `/actuator/health` | 200/UP ou 503 quando indisponível |
| GET | `/actuator` | Links de monitoramento |

`POST` e `PUT` exigem `fullName`, `email` e `password` não vazios.
`PATCH` exige ao menos um campo não nulo, preserva campos omitidos/nulos e
rejeita campos em branco. `PUT` preserva apenas ID e metadados de criação.
O e-mail é único, normalizado para minúsculas e sem espaços externos.
A senha recebe hash PBKDF2 com salt aleatório e nunca aparece nas respostas.

Exemplo de criação e atualização parcial:

```powershell
$body = @{fullName='Maria Silva'; email='maria@servicehub.com'; password='senha123'} | ConvertTo-Json
$user = Invoke-RestMethod -Uri 'http://localhost:8080/api/users' -Method Post -ContentType 'application/json; charset=utf-8' -Body $body
$patch = @{fullName='Maria Souza'} | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/users/$($user.id)" -Method Patch -ContentType 'application/json; charset=utf-8' -Body $patch
```

Erros retornam `timestamp`, `status`, `error`, `message` e `path`:
400 para entrada inválida, 404 para ID inexistente, 409 para e-mail duplicado
ou vínculos que impeçam uma operação, e 415 para conteúdo não suportado.

## Arquitetura e escopo

`Controller` trata HTTP e converte DTOs. `Service` concentra regras e transações.
`Repository` realiza o acesso JPA. `GlobalExceptionHandler` padroniza os erros.
`OpenApiConfig` configura a documentação, complementada por `@Operation`,
`@Tag`, `@Parameter`, `@ApiResponse` e `@Schema` nos endpoints e DTOs.

O recurso completo desta etapa é **Usuário**. `ServiceOffering`, `ServiceRequest`
e `Review` já possuem entidades, repositórios, serviços básicos e migrations,
mas ainda não possuem endpoints HTTP. Autenticação JWT pertence à evolução
futura. Esta entrega é para execução e demonstração local.

## Verificação

```powershell
.\mvnw.cmd clean verify
# Em outro terminal, com a API em execução:
.\scripts\verify-api.ps1
```

O primeiro comando compila, executa os testes e gera o JAR.
O segundo testa o fluxo HTTP real, cria dados temporários com e-mails únicos
e remove os usuários criados ao terminar. Para salvar o resultado:

```powershell
.\scripts\verify-api.ps1 -ReportPath '.tmp/http-validation.json'
```

Testes automatizados usam **H2 em modo PostgreSQL**, apenas com escopo `test`.
A validação HTTP registrada nesta entrega foi executada com **PostgreSQL 15**.
Consulte [o relatório](docs/VALIDACAO.md) e [o roteiro da apresentação](docs/ROTEIRO.md).
A apresentação está em [ServiceHub_API-final.pptx](docs/ServiceHub_API-final.pptx).

## Entrega

O pacote `delivery/ServiceHub_API-entrega.zip` contém o código-fonte, Maven
Wrapper, configuração Docker, testes, evidências e apresentação. `target/`,
logs temporários e dados do banco não fazem parte do pacote.

O repositório configurado é <https://github.com/JoseHFilho/ServiceHub_API>.
Para entregar por link, publique também estas alterações e a apresentação.
Para entregar o estado local já preparado, use o ZIP.

Referência de compatibilidade: [SpringDoc para Spring Boot 4](https://springdoc.org/).
