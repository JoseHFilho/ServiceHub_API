# Roteiro de apresentação (8 a 10 minutos)

## Preparação

Execute os comandos do README, confira `/actuator/health` e abra
`http://localhost:8080/swagger-ui.html`. Tenha o PowerPoint e as classes
`UserController`, `UserService`, `UserRepository` e `User` abertos.

## Sequência

1. Contexto e escopo (1 min): plataforma de serviços, cadastro de usuários
   implementado nesta etapa e outras entidades preparadas para evolução.
2. Arquitetura e banco (1 min): Controller recebe HTTP, Service valida,
   Repository persiste. Flyway cria o schema e Hibernate confere o mapeamento.
3. Contrato e validações (1 min): apresentar endpoints, diferença entre PUT e
   PATCH e os retornos 400, 404 e 409.
4. Swagger e chamadas ao vivo (3–4 min): expandir POST, mostrar schema e exemplo,
   criar um usuário com um e-mail novo, copiar seu ID e executar GET, PUT ou
   PATCH, DELETE e GET novamente para demonstrar 404. Repetir o POST antes da
   exclusão demonstra 409. Um e-mail inválido demonstra 400.
5. Código e testes (1–2 min): mostrar a delegação no controller, validações no
   service, interface JpaRepository e relatório dos testes automatizados/HTTP.
6. Encerramento (30 s): informar onde estão o projeto, slides e instruções de
   execução. Explicar que autenticação e CRUD de outros recursos são próximos
   passos do semestre.

## Payloads para a demonstração

POST e PUT exigem todos os campos:

```json
{"fullName":"Maria Silva","email":"maria.demo@servicehub.test","password":"senha123"}
```

PATCH preserva campos omitidos:

```json
{"fullName":"Maria Souza"}
```

Caso de erro 400:

```json
{"fullName":"Maria Silva","email":"email-invalido","password":"senha123"}
```

Para repetir a demonstração, exclua o registro criado ou utilize outro e-mail.
Use `scripts/verify-api.ps1` para executar automaticamente os 24 cenários HTTP.
