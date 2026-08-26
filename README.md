# ServiceHub API

Projeto da Aula 04 com Spring Boot, Spring Data JPA, PostgreSQL e Flyway.

## Requisitos

- Java 25 ou superior
- Docker Desktop

## Executar

Inicie o PostgreSQL 15:

```powershell
docker compose up -d
```

Depois, inicie a API:

```powershell
.\mvnw.cmd spring-boot:run
```

Por padrão, a aplicação usa:

- banco: `servicehub`
- usuário: `servicehub`
- senha: `secret`
- endereço: `jdbc:postgresql://localhost:5432/servicehub`

Esses valores podem ser alterados com as variáveis `DB_URL`, `DB_USERNAME` e
`DB_PASSWORD`. Se a porta `5432` já estiver ocupada, por exemplo:

```powershell
$env:POSTGRES_PORT = "55433"
$env:DB_URL = "jdbc:postgresql://localhost:55433/servicehub"
docker compose up -d
.\mvnw.cmd spring-boot:run
```

Na inicialização, o Flyway aplica automaticamente as migrações `V1` a `V4`.
O Hibernate usa `ddl-auto=validate`, portanto apenas confere se as entidades e
o schema estão compatíveis.

## Endpoints

```text
GET    /api/hello
GET    /api/status
POST   /api/users
GET    /api/users
GET    /api/users/{id}
PUT    /api/users/{id}
DELETE /api/users/{id}
GET    /actuator/health
```

Exemplo de criação:

```powershell
curl.exe -X POST http://localhost:8080/api/users `
  -H "Content-Type: application/json" `
  -d '{"fullName":"Maria Silva","email":"maria@servicehub.com","password":"senha123"}'
```

## Testes

```powershell
.\mvnw.cmd clean test
```

Os testes validam as quatro migrações, o mapeamento e os relacionamentos JPA,
as consultas derivadas/JPQL/SQL nativo e o fluxo completo do CRUD de usuários.
