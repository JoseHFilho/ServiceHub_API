# Auditoria final — ServiceHub API, etapas 1 a 8

Data: **27/10/2026**. Classificação: **FUNCIONAL COM PENDÊNCIAS**.

O sistema compila e funciona com Java 25, H2, PostgreSQL 15 e PostgreSQL 16. As regras centrais de propriedade, transições, preço histórico e exclusão lógica têm implementação compartilhada e evidências positivas. Entretanto, há divergências reproduzidas e controles de implantação ainda necessários. O resultado não autoriza exposição pública nem equivale a uma certificação de segurança.

**Nenhuma correção de código foi realizada nesta auditoria.** Código de produção, testes existentes, migrations V1–V6 e arquivos preexistentes foram preservados. Não houve commit, push, conexão ao banco original, exclusão de containers/volumes, reset ou prune.

## 1. Escopo, método e rastreabilidade

Foram examinados os relatórios `ETAPA_01.md` a `ETAPA_08.md`, fontes atuais, entidades, repositories, serviços, controllers REST/GraphQL, segurança, migrations, configurações Maven/Docker e documentação operacional. Os relatórios anteriores foram tratados como histórico e confrontados com código e novas execuções.

Checkout: `codex/atualizacao-etapas-1-a-4`, HEAD `33797874b5fe7eac75dae23b3e16ff6abc048e53`. Havia alterações locais de etapas posteriores antes da auditoria; elas foram preservadas. Os caminhos e números de linha abaixo referem-se a esse estado local, não somente ao conteúdo já publicado no GitHub.

Os testes usaram bancos e containers novos, identificados por `servicehub-audit-568ada5b0e`. A imagem de testes Java 25 da Etapa 8 foi reaproveitada. A comparação SHA-256 dos **119 arquivos em `src` e do `pom.xml`** confirmou igualdade com o checkout. As três rodadas recompilaram os fontes com `clean verify`; não foi realizado um novo build integral da imagem Docker.

Estados usados neste relatório:

- **Confirmado:** comportamento observado ou configuração/código diretamente verificado.
- **Risco possível:** consequência dependente de exposição, volume, configuração ou condição ainda não reproduzida.
- **Não verificado:** sem execução suficiente nesta auditoria; evidência histórica não é contada como teste novo.

Prioridades: **P1**, antes de exposição pública; **P2**, próxima rodada de qualidade/confiabilidade ou antes da operação afetada; **P3**, melhoria opcional. A prioridade operacional pode ser maior que a gravidade de uma falha condicional.

Evidências novas, sem credenciais ou tokens:

| Evidência | Conteúdo |
|---|---|
| [Maven H2](evidencias/auditoria-maven-h2.json) | Resultado, JDK e contagem por classe |
| [Maven PostgreSQL 15](evidencias/auditoria-maven-15.json) | Suíte padrão e testes de upgrade em PostgreSQL |
| [Maven PostgreSQL 16](evidencias/auditoria-maven-16.json) | Suíte padrão e testes de upgrade em PostgreSQL |
| [HTTP e SQL](evidencias/auditoria-http.json) | 40 observações, incluindo comportamentos problemáticos |
| [Paginação](evidencias/auditoria-pagination.json) | Exceções internas associadas às respostas REST |
| [Banco e restauração](evidencias/auditoria-database.json) | Versões, constraints, índices, Flyway e restore sintético |
| [Cobertura](evidencias/auditoria-coverage.json) | Contadores JaCoCo das três rodadas |
| [Fontes da imagem](evidencias/auditoria-source-image.json) | Comparação dos fontes usados nos testes |
| [Docker](evidencias/auditoria-docker.json) | Engine e privilégios efetivos da API de auditoria |
| [Preservação](evidencias/auditoria-preservacao.json) | Comparação de arquivos, HEAD e recursos Docker |
| [Dependências](evidencias/auditoria-dependencies.json) | Versões resolvidas e avisos consultados |

Os logs completos, XMLs/HTMLs de teste e auxiliares estão em `.tmp/auditoria-final`, ignorada pelo Git e fora do contexto Docker. Arquivos privados de ambiente permanecem nessa pasta; não devem ser publicados.

## 2. Situação das oito etapas

| Etapa | Avaliação pelo estado atual |
|---|---|
| 1 — Banco e V5 | Implementada. V1–V6 inicializaram em PostgreSQL 15.19 e 16.14; a suíte de upgrade/preservação passou nas novas rodadas. Metadados confirmam integridade. O banco original não foi migrado. Há cuidados operacionais M05/M06. |
| 2 — Segurança e validação | Implementada, com validação de DTOs, normalização e autorização nos serviços. Cadastro ignora `role`/`active` enviados pelo cliente. Persistem A02, M01 e M04. |
| 3 — JWT | Implementada. Conta ativa e papel atual são recarregados pelo ID. JWT de conta desativada foi recusado; papel antigo no token não concede permissão. Troca de senha não revoga tokens anteriores: M03. |
| 4 — REST e negócio | Fluxos centrais funcionam: contratação, transições, avaliação única, bloqueio de desativação e histórico. Foram reproduzidos M01 e M02. |
| 5 — GraphQL | Usa os mesmos serviços e regras de autorização. Possui projeções, batching e limites de execução. Introspecção de produção bloqueada. A paginação extrema foi rejeitada corretamente, diferentemente do REST. |
| 6 — Testes | Suíte padrão de 370 testes aprovada em três ambientes nesta auditoria. Perfil Testcontainers adicional não foi reexecutado, conforme restrição de não excluir containers. Existem lacunas descritas na seção 6. |
| 7 — Consultas | EntityGraphs, projeções, batching e índice V6 presentes. Testes de consultas passaram. O benchmark de grande volume é histórico; não houve novo ensaio de capacidade. |
| 8 — Docker e integração | Engine disponível, Compose válido e API de auditoria saudável com usuário sem privilégios de root. Restore sintético executado. A configuração entregue continua sendo base local; A01–A03 impedem tratá-la como configuração pública pronta. |

## 3. Resultados executados nesta auditoria

### Maven e compatibilidade

| Ambiente | JDK | Testes | Falhas / erros / ignorados | Resultado |
|---|---|---:|---|---|
| H2 | 25.0.4.1 | 370 | 0 / 0 / 0 | BUILD SUCCESS |
| PostgreSQL 15.19 | 25.0.4.1 | 370 | 0 / 0 / 0 | BUILD SUCCESS |
| PostgreSQL 16.14 | 25.0.4.1 | 370 | 0 / 0 / 0 | BUILD SUCCESS |

São **1.110 execuções de testes**, referentes à mesma suíte padrão de 370 testes em três ambientes. Não são 1.110 casos distintos. Nas rodadas PostgreSQL, as propriedades de datasource substituíram as configurações H2 e `migration.test.*` direcionou os testes de upgrade para os bancos novos.

Comandos Maven usados dentro dos runners Java 25:

```text
./mvnw -B -ntp -o clean verify
./mvnw -B -ntp -o -Dmigration.test.url=jdbc:postgresql://<container-auditoria>:5432/auditdb -Dmigration.test.username=audit_owner clean verify
```

As credenciais foram fornecidas por arquivos privados de ambiente. O Maven estava offline para dependências, usando o cache existente; o wrapper pôde baixar sua distribuição. Não houve adaptação de testes para produzir resultados positivos.

Spring Boot **4.1.1**, Spring Security **7.1.1**, Hibernate **7.4.5.Final**, Flyway **12.4.0**, GraphQL Java **25.0**, pgJDBC **42.7.13** e Jackson **3.1.5/2.21.5** constam do classpath resolvido. A presença das duas linhas Jackson não comprova, por si, conflito. Não foi observada falha de ligação ou inicialização nas execuções. JUnit, Mockito com agente explícito e JaCoCo funcionaram no Java 25.

**O perfil adicional `-Ptestcontainers` não foi executado agora.** Seus testes e lifecycle foram revisados, mas o encerramento automático dos containers conflita com a restrição desta auditoria. Os resultados de 461 testes por versão registrados na Etapa 8 são históricos e não foram somados aos resultados acima. As novas rodadas com PostgreSQL real foram feitas com containers gerenciados explicitamente e preservados.

### Fluxos HTTP/SQL adicionais

As 40 observações ocorreram em perfil `prod`, numa API nova e no banco `audit_http` novo, PostgreSQL 15. Elas incluem resultados positivos e anomalias; não são apresentadas como 40 testes aprovados.

| Verificação | Resultado observado |
|---|---|
| Cadastro com `role=ADMIN` e `active=false` | 201; usuário efetivamente CLIENT e ativo; senha ausente na resposta |
| Tentativa de trocar proprietário/preço/status pelo JSON | Proprietário e cliente vieram do token; solicitação PENDING e preço 125,50 do catálogo |
| Usuário, serviço e solicitação de terceiros | 403 nas operações sondadas |
| REST/GraphQL sem token | 401 |
| Cliente tenta concluir solicitação | 403 |
| Prestador avança sequência válida | 200 em ACCEPTED, IN_PROGRESS e COMPLETED |
| Avaliação de concluída / avaliação duplicada | 201 / 409 |
| Serviço com solicitação aberta | Desativação recusada com 409 |
| Após encerrar/cancelar solicitações | Serviço desativado com 204 e ocultado do catálogo com 404 |
| Histórico após desativação | Solicitação e avaliação continuaram acessíveis ao participante; preço preservado |
| Preservação SQL após exclusão lógica | Usuário/serviço existentes e inativos; duas solicitações e uma avaliação mantidas na fixture |
| Conta desativada com JWT anterior | 401 |
| Papel alterado no banco sintético com JWT anterior | Nova criação de serviço recebeu 403 |
| OpenAPI com CLIENT em produção | 403 |
| Introspecção GraphQL em produção | Resposta com erro, sem `__schema` |
| Health público | 200, UP, sem detalhes de conexão |

As anomalias de paginação, troca de papel, troca de senha e tamanho de corpo estão detalhadas abaixo. Não houve carga concorrente ofensiva nem sondagem de contas reais.

## 4. Problemas confirmados e riscos classificados

Não foi confirmada falha **CRÍTICA** no escopo executado. Foram registrados **3 itens ALTOS, 9 MÉDIOS e 1 BAIXO**. Isso não significa ausência de vulnerabilidades fora desse escopo.

### A01 — ALTO — API compartilha o superusuário de bootstrap do PostgreSQL

**Localização:** `compose.yaml:12`, `compose.yaml:40`; datasource em `src/main/resources/application.properties`.

**Evidência/estado:** configuração confirmada. O mesmo `DB_USERNAME` alimenta o usuário criado pela imagem PostgreSQL e a conexão da API. Nos dois bancos novos, a consulta a `pg_roles` confirmou `rolsuper=true` para esse usuário de bootstrap. O Compose não cria um papel de execução separado.

**Impacto:** uma eventual execução de SQL indevida ou comprometimento da API teria privilégios excessivos no banco. Não foi encontrada ou explorada SQL injection nesta auditoria.

**Recomendação/prioridade:** **P1**. Separar credenciais de migration e de execução; conceder somente as operações necessárias ao runtime e testar startup/upgrade com os novos papéis. Não reduzir privilégios do banco existente sem inventário e procedimento revisado.

### A02 — ALTO — Ausência de controle de tentativas e frequência no acesso público

**Localização:** `config/SecurityConfig.java:69`, `controller/AuthController.java:26`, `security/PasswordHashService.java:17`, `compose.yaml`.

**Evidência/estado:** ausência confirmada no código/configuração fornecidos: não há limiter, política de tentativas ou gateway configurado. Cinco logins inválidos sequenciais receberam 401, sem 429; essa sondagem limitada isoladamente não provaria a ausência de qualquer limite externo. BCrypt usa custo 12, inclusive no cadastro público.

**Impacto possível:** credential stuffing, abuso de cadastro e consumo de CPU/conexões quando exposto à internet. Não foi feito ensaio de exaustão nem demonstrada indisponibilidade.

**Recomendação/prioridade:** **P1**. Aplicar controles por origem e conta, concorrência e frequência, com resposta previsível e observabilidade. Validar no ingresso real, evitando bloqueios de conta que facilitem negação de serviço contra usuários.

### A03 — ALTO — HTTPS de entrada ainda depende de implantação externa

**Localização:** `compose.yaml:18`; `docs/DOCKER.md:199`.

**Evidência/estado:** pendência de implantação confirmada, já reconhecida na documentação. A API publica HTTP em loopback por padrão; não há proxy/certificado TLS no stack entregue. Nenhuma exposição pública atual foi demonstrada.

**Impacto possível:** publicar diretamente essa porta permitiria transporte sem proteção de senhas e Bearer tokens. O bind local padrão é adequado ao uso local e não constitui exposição pública por si só.

**Recomendação/prioridade:** **P1 antes de acesso externo**. Provisionar HTTPS no ingresso, impedir acesso direto à API e validar certificado, redirecionamento, headers e confiança nos proxies efetivamente usados. Não resolver apenas mudando o bind para `0.0.0.0`.

### M01 — MÉDIO — Paginação extrema gera exceção e 401 incorreto no REST

**Localização:** `controller/ServiceOfferingController.java:53`, `controller/ServiceRequestController.java:53`, `controller/ReviewController.java:57`; contraste com `graphql/PageInput.java:12`; `exception/GlobalExceptionHandler.java:20` e `config/SecurityConfig.java:74`.

**Evidência/estado:** defeito reproduzido. Com Bearer válido, `GET /api/{services,requests,reviews}?page=2147483647&size=100` retornou **401** nas três rotas. O servidor registrou `InvalidDataAccessApiUsageException: Page offset exceeds Integer.MAX_VALUE`. O GraphQL rejeitou o mesmo offset como `BAD_REQUEST`, sem chegar à consulta. O redespacho de erro protegido, sem contexto persistido, explica o 401 que mascara a exceção; essa relação também decorre da configuração inspecionada.

**Impacto:** erro de entrada parece perda de autenticação; clientes podem iniciar login indevidamente e o servidor produz stack traces desnecessários. Não houve vazamento desses traces na resposta HTTP.

**Recomendação/prioridade:** **P2**, antes da homologação dos contratos de erro. Centralizar a validação de offset REST/GraphQL e testar o redespacho de erro para preservar o status adequado, sem abrir endpoints de negócio.

### M02 — MÉDIO — Prestador que perde o papel continua oferecendo serviços contratáveis

**Localização:** `service/ServiceOfferingService.java:97`, `:108`; `service/ServiceRequestService.java:45`; `repository/UserRepository.java:21`.

**Evidência/estado:** inconsistência reproduzida após alteração **administrativa direta somente na fixture**. Um PROVIDER ativo com serviço foi alterado para CLIENT. O catálogo REST ainda retornou 200 e uma nova contratação recebeu 201/PENDING. No GraphQL, `provider(id)` retornou NOT_FOUND e `service.provider` ficou nulo. O token antigo não permitiu criar outro serviço, confirmando que a autorização usa o papel atual.

**Impacto:** catálogo e diretório de prestadores discordam; podem surgir novas solicitações cujo prestador perdeu permissão para avançá-las. Não existe endpoint público de alteração de papel, portanto isso não demonstra elevação remota de privilégios. O risco depende do procedimento de administração/importação de papéis.

**Recomendação/prioridade:** **P2**, antes de permitir alterações de papel em operação. Definir e aplicar uma política consistente para catálogo, contratação e serviços abertos ao trocar o papel, preservando o acesso ao histórico. Acrescentar regressões para as duas interfaces.

### M03 — MÉDIO — Trocar senha não invalida JWT anteriormente emitido

**Localização:** `service/UserService.java:127`; `security/CurrentUserJwtConverter.java:19`; `security/JwtService.java:60`; `security/JwtProperties.java:8`.

**Evidência/estado:** comportamento confirmado e já descrito como decisão da Etapa 3. Após PATCH da senha, a senha antiga recebeu 401, a nova autenticou e o JWT emitido antes da mudança continuou recebendo 200 na consulta do próprio usuário. O converter verifica conta/papel, sem versão de credencial ou comparação entre emissão do token e troca da senha.

**Impacto possível:** uma troca de senha não encerra imediatamente acesso por token previamente comprometido. O TTL padrão de 15 minutos reduz a janela; a configuração permite até uma hora.

**Recomendação/prioridade:** **P2**, ou P1 se troca de senha for parte do procedimento de recuperação de conta comprometida. Definir política de revogação, comunicar o comportamento e avaliar versão de credencial/data de revogação. Não é regressão de um requisito já implementado.

### M04 — MÉDIO — REST não possui orçamento explícito de corpo equivalente ao GraphQL

**Localização:** `controller/AuthController.java:26`, `model/dto/LoginRequest.java`, `graphql/GraphQlBodyLimitFilter.java:16`; configurações `application*.properties`.

**Evidência/estado:** confirmado. Um login válido contendo campo desconhecido de **98.304 bytes** recebeu 200 e emitiu token; corpo equivalente no GraphQL recebeu **413**, por limite de 65.536 bytes. A aplicação não configura limite global específico para corpos JSON REST. Limites de formulário/multipart não devem ser presumidos como limites de JSON; Jackson também tem limites próprios, portanto não se afirma aceitação ilimitada.

**Impacto possível:** maior consumo de rede e parsing em endpoints públicos antes da validação de negócio. Não foi executado teste de negação de serviço.

**Recomendação/prioridade:** **P2**, alinhada a A02 antes de exposição pública. Definir orçamento por endpoint/ingresso e testar Content-Length, transferência em chunks e erro 413 sem exposição de detalhes.

### M05 — MÉDIO — Flyway não detecta alteração de conteúdo das migrations Java por checksum

**Localização:** `db/migration/V5__Align_reference_schema.java:13` e `V6__Order_active_services_by_category.java:8`.

**Evidência/estado:** confirmado nos dois PostgreSQL: V1–V4 têm checksum, enquanto V5/V6 constam como JDBC com `checksum=null`. Ambas herdam `BaseJavaMigration` sem implementar checksum. Isso não impediu aplicação ou validação de startup; é uma limitação de detecção de alteração posterior.

**Impacto:** um `flyway validate` bem-sucedido não prova que essas classes mantêm o conteúdo originalmente aplicado.

**Recomendação/prioridade:** **P2**. Preservar V1–V6 e controlar imutabilidade por hashes/artefatos e revisão no pipeline. Planejar checksum estável para futuras migrations Java; qualquer evolução deve entrar em nova migration. Não alterar V5/V6 já distribuídas para simplesmente fazer desaparecer o achado.

### M06 — MÉDIO — Espera por locks/consultas sem limite operacional definido

**Localização:** `db/migration/V5__Align_reference_schema.java:31`, `db/migration/V6__Order_active_services_by_category.java:16`, `service/BusinessLocks.java:26`; `src/main/resources/application-prod.properties:2`.

**Evidência/estado:** configuração confirmada nos bancos novos: `lock_timeout=0` e `statement_timeout=0`. V5 usa ACCESS EXCLUSIVE nas quatro tabelas; V6 cria índice transacional normal, que bloqueia escritas durante a construção. As operações de negócio também usam locks pessimistas. O timeout Hikari de três segundos limita aquisição de conexão, não a execução SQL já iniciada.

**Impacto possível:** transação longa concorrente pode prolongar upgrade ou requisições e ocupar o pool. Os testes de concorrência passaram, mas não reproduzem toda a carga ou um upgrade concorrente em base grande.

**Recomendação/prioridade:** **P2**, obrigatória antes de upgrade em banco existente. Definir janela, orçamento de espera por operação/papel, monitoramento e procedimento de abortar/repetir com segurança; ensaiar em cópia. Não editar migrations já aplicadas nem mudar parâmetros do banco original nesta auditoria.

### M07 — MÉDIO — Listagem administrativa de usuários sem paginação

**Localização:** `controller/UserController.java:68`, `service/UserService.java:86`, `repository/UserRepository.java:40`.

**Evidência/estado:** confirmado por código. `GET /api/users`, restrito a ADMIN, carrega todos os usuários ativos e materializa uma lista de respostas. Não há `Pageable` ou limite nesse caminho.

**Impacto possível:** crescimento de memória, latência e tamanho de resposta com o volume. Não foi demonstrado acesso por CLIENT/PROVIDER nem esgotamento de recursos.

**Recomendação/prioridade:** **P2**, antes de uso administrativo em base volumosa. Evoluir o contrato para paginação/limite com compatibilidade planejada e teste de volume representativo.

### M08 — MÉDIO — pgJDBC resolvido pertence a faixas com avisos de segurança

**Localização:** `pom.xml:108`, dependência gerenciada pelo parent; classpath resolvido dos testes e imagem correspondente: **42.7.13**.

**Evidência/estado:** versão confirmada; exploração na aplicação **não confirmada**. Avisos oficiais publicados em 07/10/2026 incluem essa versão e indicam correção em **42.7.14**:

- [CVE-2026-107314 / GHSA-rhp9-mr79-r74h](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-rhp9-mr79-r74h): depende de `requireAuth` configurado para excluir todos os métodos; essa opção não foi encontrada na configuração entregue.
- [CVE-2026-107315 / GHSA-f64h-wr5q-3qf3](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-f64h-wr5q-3qf3): depende de comprimentos inconsistentes em APIs específicas de escrita binária/stream. Não foram encontrados usos diretos dessas APIs ou campos LOB nas entidades examinadas.

**Impacto possível:** as condições dos avisos podem expor informações se introduzidas por código/configuração ou integração posterior. Não se afirma vazamento atual nem SQL injection por apenas usar essa versão.

**Recomendação/prioridade:** **P1 de manutenção antes de implantação**, gravidade MÉDIA pela aplicabilidade condicional. Atualizar para versão corrigida compatível, revisar a árvore resolvida e repetir a matriz. Não foi executado scanner SCA completo; estes dois avisos não formam uma lista exaustiva de vulnerabilidades.

### M09 — MÉDIO — Imagem PostgreSQL selecionada por tag mutável

**Localização:** `compose.yaml:38`.

**Evidência/estado:** confirmado. O PostgreSQL usa `postgres:${POSTGRES_MAJOR:-15}`. As bases Temurin do Dockerfile estão fixadas por digest, mas a imagem do banco não está. Nesta execução, as imagens disponíveis produziram 15.19 e 16.14.

**Impacto possível:** um pull futuro pode trazer outro patch/conteúdo sem alteração do repositório. Isso dificulta reproduzir precisamente um ambiente; não prova comprometimento da imagem.

**Recomendação/prioridade:** **P2** para implantação reproduzível. Registrar/pinar a imagem aprovada por digest e estabelecer atualizações periódicas testadas. Fixação sem manutenção não substitui aplicação de patches.

### B01 — BAIXO — Cobertura é publicada sem critério automatizado de regressão

**Localização:** `pom.xml:176` a `:215`; `.github/workflows`, sem workflow de CI no checkout examinado.

**Evidência/estado:** confirmado. JaCoCo prepara, agrega e publica relatório, mas não há goal `check` com limite de cobertura. Não foi identificada automação versionada que obrigue a matriz completa. Pode existir automação externa não disponível no checkout.

**Impacto:** futuras alterações podem reduzir cobertura sem reprovar o build. A quantidade de testes, isoladamente, não garante os caminhos de autorização.

**Recomendação/prioridade:** **P3**. Definir critérios por risco e execução automatizada da matriz, priorizando testes úteis para os achados acima em vez de elevar números artificialmente.

Os caminhos abreviados Java desta seção são relativos a `src/main/java/com/servicehub`, exceto `db/migration`, relativo a `src/main/java`.

## 5. Segurança, arquitetura e banco: avaliação complementar

### Controles verificados

Controllers REST e GraphQL delegam para os mesmos serviços transacionais; DTOs públicos evitam serialização direta de entidades/credenciais. A separação entre transporte, autorização, negócio e persistência é adequada ao tamanho atual. APIs internas de compatibilidade existem, mas não foi identificado endpoint alternativo que permita contornar as regras por meio delas. Não há fundamento para remover esses métodos sem revisar seus consumidores.

O JWT usa HS256, segredo obrigatório em Base64 com tamanho validado, issuer e claims temporais/identidade/papel validados, assinatura e algoritmo restritos. O login emite o token pelo ID da identidade autenticada. O converter consulta a conta atual por ID: não depende somente do e-mail nem concede o papel desatualizado do token. Alteração de e-mail, inatividade e troca de papel não foram confundidas com identidade nova. Não foi encontrada aceitação de token por query string, formulário, cookie ou Basic.

BCrypt usa custo 12. A compatibilidade PBKDF2 tem parâmetros limitados e comparação constante; atualização do hash é condicional ao hash anterior e à conta ativa. Senhas legadas longas usam pré-hash identificado para evitar truncamento silencioso do BCrypt. Os caminhos de senha e token possuem representações de log protegidas.

`@PreAuthorize`, propriedade e participação são aplicados também nos serviços. As sondagens confirmaram BOLA bloqueada nos caminhos escolhidos, mass assignment ignorado nos campos protegidos e delegação de identidade restrita ao ADMIN. Isso não substitui prova exaustiva de todas as combinações de endpoints/papéis.

CSRF desabilitado é coerente com Bearer explícito e ausência de sessão/cookie de autenticação. Não foi encontrada política CORS permissiva global. Permitir CORS para um frontend futuro exigirá configuração explícita e teste; desabilitar CSRF, nesse desenho, não foi classificado como vulnerabilidade isolada.

No GraphQL, limites de corpo, documento, profundidade, custo, aliases, seleções e campos raiz estão presentes; há restrição a uma operação e uma mutation raiz. Produção bloqueia introspecção/GraphiQL e protege documentação para ADMIN. Scalars preservam precisão decimal e o contrato de horário local. EntityGraphs e batching evitam a forma principal de N+1 coberta pelos testes. A autorização é feita antes da exposição de histórico privado.

### Integridade e evolução do banco

Os metadados reais de ambos os PostgreSQL confirmaram **18 constraints** nas quatro tabelas e **12 índices**, incluindo primárias/únicos. Há cinco FKs com `ON DELETE RESTRICT`, unicidade de e-mail, e-mail em minúsculas, papéis válidos, 21 categorias, preços positivos, estados válidos, nota entre 1 e 5 e uma avaliação por solicitação. A unicidade de e-mail também abrange registros inativos.

V6 deixou o índice parcial `idx_services_category` em `(category, id) WHERE is_active`. Os índices de acesso a cliente/serviço/status/reviewer permanecem presentes. Hibernate validou o esquema e Flyway registrou as versões 1 a 6 com sucesso nas duas versões do PostgreSQL.

V5 faz verificações prévias antes da transformação; os testes de upgrade exercitam preservação e rejeição de dados legados inválidos. No PostgreSQL, Flyway executa as alterações transacionalmente. Isso reduz o risco de estado parcial, mas não garante que uma base desconhecida satisfará os pré-requisitos: colisões após normalização, categorias não mapeadas e preços inválidos precisam ser tratados em procedimento próprio, antes de migrar a base real.

Exclusão lógica não é cascata física. Os testes HTTP/SQL novos mantiveram usuário, serviço, solicitações e avaliação; histórico e preço capturado continuaram disponíveis ao participante. Os locks são adquiridos de modo ordenado e revalidam o estado após a obtenção do lock. Os testes de concorrência passaram, inclusive o de mudança de preço versus contratação. Não foi observado mecanismo que reescreva preço de solicitações antigas ao mudar o catálogo.

**Risco possível de desempenho:** `ServiceRequestService.list`, na especificação de participantes, combina cliente/prestador com OR e paginação/count. A Etapa 7 já documenta consultas mais custosas em massa de 600 mil solicitações; esses tempos são históricos e não foram medidos novamente. Impacto depende de distribuição, filtros, página e infraestrutura. Prioridade P2 para monitorar planos e latência com dados representativos; o índice V6 não deve ser apresentado como melhoria universal.

**Aplicação em banco existente:** exige backup restaurável, ensaio numa cópia, janela e avaliação de locks/espaço. Rollback da imagem não desfaz migrations. Uma falha de preflight deve resultar em diagnóstico dos dados, não em `repair`, reset ou edição de V1–V6 sem análise. Nada disso foi executado no banco original.

## 6. Cobertura e lacunas de testes

| Rodada | Linhas JaCoCo | Ramos JaCoCo |
|---|---:|---:|
| H2 | 92,37% | 72,47% |
| PostgreSQL 15 | 92,66% | 72,13% |
| PostgreSQL 16 | 92,66% | 72,13% |

Na rodada H2, segurança atingiu 95,27% de linhas / 76,25% de ramos; serviços 89,68% / 69,23%; GraphQL 85,62% / 73,68%; validação 100% / 69,23%. Controllers atingiram 100% das linhas instrumentadas. Esses números são das novas execuções, não cópia da Etapa 6. A cobertura não inclui as sondagens HTTP contra a imagem runtime.

JUnit/Mockito, MockMvc, integração JPA, migrações, JWT, GraphQL, desativação e concorrência foram executados pela suíte padrão. As classes Testcontainers exigem Docker disponível e não estão configuradas para ignorar silenciosamente essa ausência. Sua infraestrutura adicional não foi revalidada agora pelo motivo já exposto.

Lacunas prioritárias para uma rodada futura de correções:

1. Offset REST acima de `Integer.MAX_VALUE`, status correto e redespacho de erro — regressão de M01 em servidor real, além de MockMvc.
2. Troca administrativa de papel com serviço ativo/solicitação aberta — política consistente entre catálogo, providers e contratação, sem prejudicar histórico.
3. Política explícita para JWT anterior à mudança de senha; revogação e recuperação de conta conforme a decisão de produto.
4. Limites REST, chunks, campos desconhecidos grandes, frequência/concorrência de login/cadastro e 413/429 no ingresso.
5. Runtime com privilégios mínimos no PostgreSQL; migration com credencial separada e falhas de permissão.
6. Branches negativas de JWT/AccessPolicy, exceções nos serviços e scalars, conflitos de lock/timeout/recuperação. Os relatórios mostram ramos não exercitados; percentuais não demonstram que todos sejam defeitos.
7. Paginação administrativa e planos em dados representativos, sob a carga esperada.
8. Matriz completa Testcontainers novamente em tarefa cuja política permita seu lifecycle, HTTPS real, frontend/CORS e Swagger interativo no ambiente de destino.

Não foram criados testes artificiais, alteradas asserções ou desabilitados casos para elevar cobertura.

## 7. Docker, persistência, backup e atualização

O Docker estava operacional: **Engine 29.7.2 / Compose 5.3.1**. Não foi necessário reset, reparo ou reinício do Docker Desktop. O erro de inicialização relatado na Etapa 1 não se reproduziu nesta auditoria; isso não estabelece retroativamente sua causa.

`docker compose ... config --quiet` foi aprovado sem imprimir valores privados. A API nova ficou healthy, com usuário **10001:10001**, root filesystem somente leitura, capabilities removidas, `no-new-privileges`, limite de 1 GiB e publicação apenas em loopback. O healthcheck usa Java disponível no runtime. Dockerfile usa build multi-stage e JRE final; a allowlist de `.dockerignore` exclui `.env`, Git, dumps, relatórios e temporários. Não foi encontrada credencial versionada nos caminhos de ambiente examinados; isso não constitui varredura de todo o histórico Git.

O Compose mantém banco sem porta publicada por padrão e rede de banco interna. O nome do volume inclui projeto e major, reduzindo adoção acidental do volume antigo. A API tem uma rede de entrada separada. Os auxiliares de auditoria usam uma rede própria, sem porta PostgreSQL no host; não substituem a validação de um firewall de produção.

Foi executado `pg_restore --exit-on-error --no-owner --no-privileges` sobre um dump **sintético da Etapa 8/PostgreSQL 16**, para o banco novo `audit_restore`. A restauração terminou com código 0 e a leitura posterior confirmou **4 usuários inativos, 2 serviços, 2 solicitações, 2 avaliações, 5 FKs e Flyway em V6**. O hash do dump está na evidência. Nenhum código do produto foi alterado.

Esse resultado valida restauração funcional de uma fixture pequena. **Não valida RPO/RTO de produção, integridade de todos os arquivos de um backup real, recuperação após perda de host, PITR ou migração 15→16.** Política de retenção, cópia externa protegida, alertas e ensaio com volume representativo continuam pendentes. A documentação distingue atualização da aplicação e upgrade de major do PostgreSQL; trocar a tag não converte o diretório de dados.

A base de implantação ainda requer os controles A01–A03, limites operacionais e revisão de dependências/imagens. Não foi feito scan completo de CVEs da imagem/SO nem teste de TLS externo, falha de disco, indisponibilidade prolongada ou recuperação em outro host. Um healthcheck saudável sozinho não comprova essas propriedades.

### Recursos preservados

Antes: **22 containers, 8 volumes, 11 redes**. Depois: **28 containers, 8 volumes, 12 redes**. Todos os IDs e estados dos containers preexistentes, volumes e redes foram preservados. O banco original `servicehub-db` permaneceu **exited**, ID `71fb5634975e`, sem conexão a seu conteúdo.

Novos recursos, mantidos por determinação desta auditoria:

| Recurso | Estado ao encerrar |
|---|---|
| `servicehub-audit-568ada5b0e-pg15` | Em execução; bancos sintéticos `auditdb` e `audit_http` |
| `servicehub-audit-568ada5b0e-pg16` | Em execução; bancos sintéticos `auditdb` e `audit_restore` |
| `servicehub-audit-568ada5b0e-api` | Em execução/healthy; HTTP em `127.0.0.1:56229` |
| `servicehub-audit-568ada5b0e-tests-h2` | Encerrado com sucesso, preservado |
| `servicehub-audit-568ada5b0e-tests-15` | Encerrado com sucesso, preservado |
| `servicehub-audit-568ada5b0e-tests-16` | Encerrado com sucesso, preservado |
| Rede `servicehub-audit-568ada5b0e` | Preservada |

Os bancos de auditoria usam tmpfs, sem novos volumes persistentes; seu conteúdo depende de os containers permanecerem em execução. As evidências em disco foram salvas. A autorização antiga para excluir dois containers temporários não foi usada: a restrição atual proíbe exclusões.

## 8. Itens não verificados e limites de conclusão

- Banco original: sem leitura de conteúdo, alteração ou migration. Não é possível atestar seus dados reais ou autorizar upgrade com base somente nas fixtures.
- Perfil Testcontainers adicional: sem nova execução; resultados anteriores permanecem históricos.
- Segurança dinâmica exaustiva: não houve pentest, fuzzing amplo, brute force, teste de exaustão, varredura completa de dependências/SO ou prova de ausência de outras CVEs.
- Infraestrutura pública: proxy, domínio, certificados, proteção de borda, gestão de segredos, métricas/alertas, backup externo e comportamento de frontend ainda dependem do ambiente de destino.
- Capacidade e recuperação: sem novo benchmark de 600 mil registros, teste prolongado, restauração de backup real ou garantia de RPO/RTO.
- Swagger: proteção HTTP revisada; fluxo interativo completo de navegador em produção não foi repetido.

Não há evidência suficiente para classificar como falha de segurança o CSRF desabilitado no desenho Bearer atual, a mera coexistência das linhas Jackson, ou os arquivos antigos de entrega que a documentação já identifica como históricos. Os temporários de testes/segredos estão fora do Git/contexto Docker, mas precisam continuar protegidos localmente.

## 9. Ordem recomendada de correções e pendências de implantação

| Ordem | Ação | Critério para encerrar |
|---|---|---|
| P1 | A01 — separar papéis do banco | API funciona com menor privilégio; migration possui credencial própria; testes positivos e negativos aprovados |
| P1 | A02 — controlar abuso de login/cadastro | Política aplicada no ingresso real e validada com limites/respostas/monitoramento |
| P1 | A03 — entregar HTTPS de entrada | Certificado e proxy validados; acesso direto da API restrito |
| P1 | M08 — atualizar pgJDBC e revisar componentes | Versão corrigida resolvida; matriz aprovada; análise SCA/imagem registrada |
| P2 | M01 — alinhar paginação e erros | Entrada extrema retorna erro de validação coerente nas duas interfaces |
| P2 | M02 — definir troca de papel | Nenhuma nova contratação inconsistente; histórico e solicitações existentes tratados explicitamente |
| P2 | M03 — definir revogação ao trocar senha | Política documentada e testada, incluindo cenário de conta comprometida |
| P2 | M04/M07 — limites REST e paginação administrativa | Contratos e limites definidos, testes de fronteira e volume aprovados |
| P2 | M05/M06 — imutabilidade e operação de migrations | Artefatos verificáveis, ensaio em cópia, backup restaurável e orçamento de locks |
| P2 | M09 — imagem PostgreSQL reproduzível | Digest aprovado e rotina de atualização documentados |
| P2 | Operação e capacidade | RPO/RTO, backups externos, alertas, planos de consulta e carga representativa aprovados |
| P3 | B01 — automação e critério de regressão | Pipeline e critérios por risco documentados/versionados |

As ações desta tabela são **recomendações**, não mudanças já feitas. Nenhuma etapa nova foi iniciada e nenhum achado foi corrigido automaticamente.

## 10. Parecer de prontidão

**FUNCIONAL COM PENDÊNCIAS.** Há evidência atual de compilação Java 25, compatibilidade com PostgreSQL 15/16, inicialização Hibernate/Flyway, funcionamento dos fluxos centrais e preservação do histórico. Os 370 testes passaram em cada um dos três ambientes, e a restauração sintética funcionou.

A classificação fica abaixo de homologação concluída/implantação controlada porque os achados reproduzidos M01/M02 ainda exigem decisão e regressões, e os controles A01–A03 e a revisão M08 precisam preceder qualquer implantação pública. A aprovação das suítes existentes não cobre essas lacunas. Após as correções autorizadas e validações pendentes, a prontidão deve ser reavaliada para o ambiente e a carga pretendidos.

Verificação de preservação: **243 arquivos preexistentes sem diferença de SHA-256**, HEAD inalterado, todos os recursos Docker anteriores presentes e sem mudança de estado. As únicas entregas no repositório são este relatório e novas evidências; auxiliares privados ficaram em `.tmp`.
