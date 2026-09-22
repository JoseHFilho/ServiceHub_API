# Validação do checkpoint ServiceHub API

Data: 22/09/2026. Ambiente: Windows, JDK 25.0.2, Spring Boot 4.1.1,
SpringDoc 3.1.1, PostgreSQL 15 via Docker.

## Critérios da entrega

| Requisito | Evidência |
| --- | --- |
| Spring Web, JPA, PostgreSQL e SpringDoc | Dependências declaradas no `pom.xml`, sem duplicações |
| CRUD completo de ao menos um recurso | Usuários: POST, GET coleção/item, PUT, PATCH e DELETE |
| Swagger em `/swagger-ui.html` | Redirecionamento e interface real verificados, OpenAPI com summaries e descriptions |
| DTOs e exemplos | `UserDTO`, `UserPatchDTO`, `UserResponse`, `StatusResponse` e `ApiError` com `@Schema` |
| Métodos e status HTTP | Fluxo com 201, 200 e 204, erros 400, 404, 409 e 415 |
| Separação de responsabilidades | Controller, Service, Repository e tratamento global de exceções |
| Persistência | Flyway V1–V4 aplicado no PostgreSQL, Hibernate validando schema |
| Apresentação | `docs/ServiceHub_API-final.pptx`, com roteiro em `docs/ROTEIRO.md` |

## Testes automatizados

Comando: `mvnw.cmd clean verify`, Java 25. Resultado: **26 testes, zero falhas,
zero erros, zero ignorados**. Inclui inicialização do contexto, migrations,
relacionamentos JPA, consultas de repositório, serviço de senhas, regras de
usuários, CRUD, validação de entrada e Swagger/OpenAPI.

Os oito erros anteriores vinham da ausência de H2 no classpath de teste.
A dependência foi restaurada. O mapeamento `password_hash` também foi corrigido.

## Validação HTTP com PostgreSQL

Comando: `scripts/verify-api.ps1`, API em `http://localhost:8080`, banco em
`localhost:55433`. Resultado: **24 verificações aprovadas**.

O script cobre leitura, criação, substituição completa, atualização parcial,
exclusão, ausência de senha na resposta, campos omitidos no PATCH, requisição
incompleta, e-mail inválido/duplicado, JSON malformado, ID inválido/inexistente,
tipo de conteúdo, saúde da aplicação e acesso ao Swagger.

Os registros temporários criados pelo script foram removidos. O resultado
detalhado está em `docs/evidencias/http-validation.json`. Os resultados JUnit
estão resumidos em `docs/evidencias/junit-summary.json`. A imagem do Swagger
está em `docs/evidencias/swagger.jpg`.

## Relação com as aulas

As aulas 3 e 4 fundamentam a estrutura, persistência e CRUD. A aula 5 fundamenta
as anotações e exemplos da documentação. A aula 6, slides 4–7 e 23, define o
checkpoint usado para esta entrega, complementado pelo enunciado fornecido,
que exige PostgreSQL e uma apresentação do projeto.

O plano completo do semestre da aula 1 e o exercício de diagrama da aula 2
têm escopo maior ou separado. Outros recursos, JWT, papéis de acesso e deploy
não são apresentados como implementados nesta etapa. A suíte H2 complementa
os testes reais com PostgreSQL e não pretende provar equivalência total entre
os dois bancos.
