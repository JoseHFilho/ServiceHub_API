# ServiceHub API

Projeto do checkpoint da disciplina de Desenvolvimento Backend com Java.
Implementa usuários, catálogo de serviços, contratações e avaliações, com JWT,
REST e GraphQL, PostgreSQL, migrações Flyway e documentação OpenAPI.

## Estado desta entrega parcial

As Etapas 1–8 foram implementadas. O planejamento posterior de correções da
auditoria foi interrompido na **Fase 7 (revogação JWT por versão de credenciais)**;
as fases seguintes permanecem pendentes. O código dessa fase está integrado.
Esta versão é funcional com pendências e ainda exige preparação operacional
para produção pública. Consulte o [relatório de entrega](docs/ENTREGA_PARCIAL_AUDITORIA.md)
para resultados executados, arquivos, limitações e instruções de retomada.
Os relatórios das etapas anteriores e da auditoria original são históricos.

## Executar após clonar ou descompactar

Requisitos para o ambiente completo: Docker Desktop/Compose em execução,
PowerShell 7.2+ e acesso à internet na primeira construção. A imagem compila com
Maven Wrapper e JDK 25 e executa com JRE 25; não exige Java/Maven no host.

No PowerShell, dentro da pasta do projeto:

```powershell
.\scripts\prepare-env.ps1
docker compose --env-file .env config --quiet
docker compose --env-file .env build api
docker compose --env-file .env up -d --no-build --wait
docker compose --env-file .env ps
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

O preparador cria `.env` com senhas independentes de bootstrap, migrations e
runtime, além da chave JWT aleatória, sem
imprimi-las, e recusa sobrescrever um arquivo existente. `.env` e backups
estão ignorados pelo Git e ficam fora do contexto Docker. `.env.example`
contém os nomes e valores não secretos, com senhas/chaves vazias.
O build executa os 403 testes padrão antes de gerar a imagem.
PostgreSQL é provisionado com identidades separadas somente em volume vazio;
a API usa DML restrito e o Flyway usa o dono das migrations. Um `.env` antigo
precisa ser revisado: o novo Compose exige também `DB_ADMIN_*` e
`DB_MIGRATION_*`. Não sobrescreva seu arquivo privado nem adote volumes antigos.

O novo projeto padrão é **servicehub-stack**, separado do projeto antigo
servicehub-api. O volume tem nome formado pelo projeto e major do PostgreSQL,
como `servicehub-stack-postgres-15-data`. O volume original
`servicehub-api_servicehub-postgres-data` não é adotado automaticamente.
**Não execute a API nova contra o banco original sem backup, validação e
autorização para aplicar as migrations.**

O padrão usa `prod`, publica somente a API em **127.0.0.1:8080**, e mantém
PostgreSQL sem porta no host, em rede interna. A URL JDBC dentro do Compose usa
`postgres:5432`. Altere `API_PORT` para a porta do host e `SERVER_PORT` para a
porta interna. A API roda como usuário 10001, com filesystem somente leitura
e healthcheck que usa o próprio Java. O health agregado também verifica o banco.

JWT funciona em todos os perfis e HTTP Basic permanece removido. Rotas privadas
exigem `Authorization: Bearer <accessToken>`. `JWT_SECRET` é obrigatório:
32–128 bytes aleatórios em Base64; `JWT_TTL` aceita `1m` a `1h` (padrão `15m`)
e `JWT_ISSUER` usa `servicehub-api`. A chave do servidor não é o token de acesso.
Mantenha a chave estável entre reinícios. A documentação exige ADMIN em `prod`;
não há administrador ou senha padrão criados na inicialização.

Cada JWT novo contém `cv`, a versão de credenciais do usuário. Alterar a senha
revoga todos os tokens anteriores dessa conta em REST e GraphQL; faça login
novamente após a alteração. Tokens antigos sem `cv` são aceitos como versão 0
somente enquanto a conta continuar nessa versão. Upgrade automático de hash
legado também incrementa a versão. Conta ativa e papel atual continuam sendo
consultados no banco a cada requisição.

Login e cadastro têm quotas por origem, conta e operação, além de concorrência
limitada, compartilhadas no PostgreSQL. Excesso retorna 429 com Retry-After;
falha do armazenamento retorna 503. Corpos JSON de login/cadastro têm limite
de 8 KiB e escritas REST de 64 KiB. Os detalhes e as limitações estão no
[guia de implantação](docs/PRODUCAO.md).

- Saúde: <http://127.0.0.1:8080/actuator/health>.
- Login: `POST /api/auth/login`.
- Swagger: <http://127.0.0.1:8080/swagger-ui.html>, autenticado como ADMIN em prod.
- OpenAPI: <http://127.0.0.1:8080/v3/api-docs>, autenticado como ADMIN em prod.
- GraphQL: `POST http://127.0.0.1:8080/graphql`, com Bearer JWT.

Acesso externo de produção exige HTTPS. O override opt-in `compose.tls.yaml`
prepara o Caddy, remove a porta direta da API e restringe proxies confiáveis.
TLS local foi ensaiado; domínio, certificado público e renovação ainda dependem
de validação no destino. Consulte [docs/PRODUCAO.md](docs/PRODUCAO.md).
Flyway aplica V1–V9 e Hibernate valida o schema sem recriá-lo. V1–V6 foram
preservadas; V7 adiciona contadores de admissão, V8 protege mudanças de papel e
V9 adiciona a versão de credenciais. V6 reconstrói
um índice e bloqueia escritas durante a construção; planeje a implantação.

Consulte [docs/DOCKER.md](docs/DOCKER.md) para login sem exibir tokens,
REST/GraphQL, logs, reinícios, parada preservando volume, backup/restauração,
atualização, instalações novas PostgreSQL 15/16 e execução local do JAR.
Os resultados reais estão em [docs/ETAPA_08.md](docs/ETAPA_08.md).

## Endpoints implementados

Os 13 endpoints REST de serviços, solicitações e avaliações exigem JWT.
O relatório da Etapa 4 está em [docs/ETAPA_04.md](docs/ETAPA_04.md).
Na Etapa 5, **POST /graphql** acrescenta oito queries e seis mutations com o
mesmo JWT e os mesmos services. Exemplos, schema, erros e limites estão no
[guia GraphQL](docs/GRAPHQL.md) e no [relatório da Etapa 5](docs/ETAPA_05.md).

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
concorrentes. A Etapa 5 integra GraphQL reutilizando essas regras.
REST continua disponível. A Etapa 6 organiza a suíte, integra Testcontainers
e gera cobertura JaCoCo. A Etapa 7 mede os planos PostgreSQL, otimiza a
verificação de solicitações abertas e a paginação por categoria. Resultados,
trade-offs e reprodução estão em [docs/ETAPA_07.md](docs/ETAPA_07.md).
A Etapa 8 acrescenta a imagem Java 25 e o Compose completo API/PostgreSQL,
com validação em projetos isolados. gRPC e frontend permanecem fora do escopo.

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
A suíte padrão contém **403 testes**, preservando as classes e contagens
anteriores. O perfil Testcontainers acrescenta **117 cenários PostgreSQL** de
migração, persistência, concorrência, GraphQL e correções da auditoria.
Organização, requisitos e comandos estão em [docs/TESTES.md](docs/TESTES.md);
os resultados da consolidação e a cobertura, em [docs/ETAPA_06.md](docs/ETAPA_06.md),
e as medições históricas, em [docs/ETAPA_07.md](docs/ETAPA_07.md). As execuções
desta entrega estão em [docs/ENTREGA_PARCIAL_AUDITORIA.md](docs/ENTREGA_PARCIAL_AUDITORIA.md).
Para executar a matriz Java 25 sem instalar esse JDK no Windows, use
`.\scripts\verify-tests-java25.ps1`; ela utiliza somente o estágio JDK do
Dockerfile e bancos próprios do Testcontainers, sem o datasource do Compose.
Ela cria relatórios em uma pasta única de `.tmp`, sem sobrescrever evidências
antigas. Use `-NoHostCache` se o cache local não contiver as dependências Linux;
`-SkipBuild` só pode reutilizar uma imagem construída dos fontes atuais.
O agente Mockito é carregado explicitamente pelo Maven para os testes com JDKs
recentes, sem depender de anexação dinâmica à JVM.

```powershell
# Grupos separados, sem Docker:
.\mvnw.cmd -Punit clean verify
.\mvnw.cmd -Pweb clean verify
.\mvnw.cmd -Pintegration clean verify
# Suíte padrão + PostgreSQL gerido automaticamente (Docker obrigatório):
.\mvnw.cmd -Ptestcontainers clean verify
# Mesma seleção PostgreSQL na versão 16:
.\mvnw.cmd -Ptestcontainers '-Dpostgres.version=16' clean verify
```

`clean verify` sem perfil executa os 403 testes padrão, gera o JAR e a
cobertura sem precisar de Docker. Com `-Ptestcontainers`, Surefire executa a
suíte padrão e Failsafe executa os cenários em bancos temporários próprios.
As rodadas finais PostgreSQL 15.19 e 16.14 passaram com **403 + 117 = 520
execuções por versão**, sem falhas, erros ou ignorados.
Docker indisponível causa falha; não há ignorados automáticos.
Testcontainers cria e remove somente seus recursos descartáveis; não usa o
banco nem os volumes da aplicação.
Não combine os perfis seletivos unit/web/integration na verificação completa.

JaCoCo e Mockito usam agentes compatíveis sem sobrescrever seus argumentos.
O relatório HTML fica em `target/site/jacoco/index.html`, acompanhado de
`jacoco.xml` e `jacoco.csv`. Nenhum limite mínimo de cobertura foi imposto.

O verificador histórico da Etapa 1 pode ser repetido em ambiente de teste, com
Java 25 no PATH, Docker Desktop disponível e PowerShell 7:

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

Os scripts `verify-rest-business.ps1` e `verify-graphql.ps1` são verificadores
históricos de etapas anteriores. Ainda iniciam `prod` usando a mesma identidade
administrativa para API e Flyway, agora recusada pelo controle de privilégios.
Não desative esse controle para executá-los. Para a versão atual, prefira a
matriz Testcontainers acima; `verify-compose.ps1` está ajustado a V9 e a
credenciais separadas, mas seu fluxo completo não foi reexecutado nesta entrega.

Validação da Etapa 1 concluída em 27/10/2026: **44 testes em PostgreSQL 15.19**,
**44 testes em PostgreSQL 16.14** e **44 testes no `clean verify` com H2**,
sem falhas, erros ou testes ignorados. Flyway e Hibernate inicializaram com
sucesso nos dois PostgreSQL. A imagem da aplicação permanece `postgres:15`.

A validação HTTP **anterior à V5** foi executada com **PostgreSQL 15**.
Consulte [o relatório](docs/VALIDACAO.md) e [o roteiro da apresentação](docs/ROTEIRO.md).
A apresentação está em [ServiceHub_API-final.pptx](docs/ServiceHub_API-final.pptx).

## Entrega

O pacote anterior `delivery/ServiceHub_API-entrega.zip` continha o código-fonte, Maven
Wrapper, configuração Docker, testes, evidências e apresentação. `target/`,
logs temporários e dados do banco não fazem parte do pacote.
Esse pacote e a apresentação são históricos e não foram regenerados com as
Etapas 1–8. A imagem Docker é construída diretamente do código atual.

O repositório configurado é <https://github.com/JoseHFilho/ServiceHub_API>.
A entrega atual é o checkout revisado, não o ZIP anterior. Nenhum commit ou
push foi realizado nesta preparação. Revise o inventário e os comandos da
[entrega parcial](docs/ENTREGA_PARCIAL_AUDITORIA.md) antes de versionar na branch
`codex/atualizacao-etapas-1-a-4`. Não publique `.env`, `.tmp`, `target` ou backups.

Referência de compatibilidade: [SpringDoc para Spring Boot 4](https://springdoc.org/).
