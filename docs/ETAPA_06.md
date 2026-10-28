# Etapa 6 — Consolidação de testes e Testcontainers

Validação executada em **27/10/2026**. A Etapa 6 foi implementada e validada,
preservando os 360 testes das Etapas 1–5. A suíte padrão passou a ter **363
cenários**. A execução completa com Testcontainers passou com **447 execuções**:
363 pelo Surefire e 84 pelo Failsafe, sem falhas, erros ou testes ignorados.

Não houve alteração em `src/main`, nas migrations V1–V5 ou nas regras de
negócio nesta etapa. A Etapa 7 não foi iniciada. Não foi realizado commit ou push.

## 1. Diagnóstico da estrutura anterior

Foram lidos os relatórios `ETAPA_01.md` a `ETAPA_05.md`, o POM, as configurações
de persistência e os verificadores PowerShell. A linha de base continha 360
cenários em 24 classes, já aprovados em H2 e PostgreSQL 15/16 na etapa anterior.

| Grupo identificado | Classes anteriores | Cenários anteriores | Organização atual |
| --- | ---: | ---: | --- |
| Unitários | 9 | 100 | Tag e perfil `unit` |
| Web, segurança e GraphQL | 9 | 234 | Tag e perfil `web`; agora 236 cenários em 10 classes |
| Integração de persistência e contexto | 6 | 26 | Tag e perfil `integration`; agora 27 cenários |
| HTTP por socket, fora do JUnit | 2 scripts | 83 GraphQL/REST e 45 REST | Scripts mantidos para verificar o JAR em `prod` |

O inventário anterior está em [etapa6-inventario.json](evidencias/etapa6-inventario.json).
Os testes web existentes usam contexto Spring, segurança real e H2, com
MockMvc/HttpGraphQlTester. Eles foram classificados sem converter tudo em
`WebMvcTest`, o que reduziria as verificações de autorização e negócio.
`HelloControllerTest` continua sendo teste unitário de chamada direta.

As verificações semelhantes em camadas diferentes têm propósitos distintos:
transições puras verificam a matriz de estados, testes web verificam o contrato
e a identidade, e concorrência verifica transações reais. Não foi encontrada
duplicação que justificasse remover testes. A repetição selecionada em
PostgreSQL é intencional e usa herança dos testes existentes.

Antes desta etapa já havia alterações locais da Etapa 5. Por isso, o diff em
relação ao commit não representa exclusivamente a Etapa 6. Foram capturados
hashes do estado inicial para conferir a preservação dos fontes atuais.

## 2. Dependências e execução

Foram acrescentadas somente dependências de teste:

- `spring-boot-testcontainers`, gerenciado pelo Spring Boot **4.1.1**.
- `testcontainers-junit-jupiter` e `testcontainers-postgresql`, versão **2.0.5**
  gerenciada pelo BOM do Boot.
- JaCoCo Maven Plugin **0.8.15**, com relatórios HTML, XML e CSV.
- Failsafe **3.5.6**, gerenciado pelo parent, ativado pelo perfil `testcontainers`.

Surefire permanece na versão gerenciada **3.5.6**. O projeto usa a API Jupiter;
a versão efetivamente resolvida pelo Boot é **JUnit Jupiter 6.0.3**. A extensão
Jupiter do Testcontainers foi integrada sem impor downgrade para JUnit 5.
O agente Mockito existente foi mantido e funcionou junto com o JaCoCo.
As bibliotecas Testcontainers, Mockito e os agentes não estão no `BOOT-INF/lib`
do JAR da aplicação verificado.

O projeto mantém **release 25**. Nesta máquina, todos os comandos foram
executados com **JDK 26.0.2.1**, o JDK disponível; a JVM 25 não foi executada.
O Maven Wrapper utilizado foi **3.9.16**.

Comandos, requisitos de outra máquina e detalhes estão em [TESTES.md](TESTES.md).

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd -Punit clean verify
.\mvnw.cmd -Pweb clean verify
.\mvnw.cmd -Pintegration clean verify
.\mvnw.cmd -Ptestcontainers clean verify
.\mvnw.cmd -Ptestcontainers '-Dpostgres.version=16' clean verify
```

O comando sem perfil executa os 363 cenários padrão e gera cobertura sem
precisar de Docker. O perfil Testcontainers acrescenta os IT pelo Failsafe;
o PostgreSQL padrão é 15 e a propriedade aceita somente 15 ou 16.
Os três perfis seletivos devem ser executados separadamente e não devem ser
combinados ao comando de verificação completa. Seleção vazia é erro de build.
Docker indisponível provoca falha nos IT solicitados; não há descarte automático
dos testes por ausência do Docker.

## 3. PostgreSQL automático e isolamento

`PostgresContainerConfiguration` declara um bean `PostgreSQLContainer` com
`@ServiceConnection`. O Boot fornece a URL JDBC, a porta e as credenciais,
com precedência sobre a configuração H2. O container inicia antes dos beans
de persistência e encerra depois de JPA/Hikari; o cache de contexto Spring
pode reutilizá-lo entre classes sem receber um datasource já encerrado.

`PostgresSchemaUpgradeIT` usa separadamente a extensão JUnit
`@Testcontainers`/`@Container` e uma conexão JDBC por cenário. O hook protegido
de `SchemaUpgradeTests` permite fornecer essa conexão sem modificar propriedades
globais do processo. Cada cenário usa um schema aleatório.

Os bancos têm nomes, usuários e senhas aleatórios, dados em `tmpfs`, portas
atribuídas pelo Docker e reutilização desativada. Nenhum banco ou volume da
aplicação é utilizado. O Flyway aplica V1–V5 e Hibernate mantém
`ddl-auto=validate`. Um teste confere o produto e a versão PostgreSQL, URL e
usuário do container, cinco migrations aplicadas e validação do Flyway/Hibernate.
Isso impede aprovação por fallback acidental para H2.

Os testes transacionais mantêm rollback. Os testes concorrentes usam transações
independentes e limpam somente seus fixtures por IDs. A execução permanece
sequencial por padrão, com barreiras e futures de timeout limitado.

| Classe PostgreSQL | Execuções | Objetivo |
| --- | ---: | --- |
| `PostgresConcurrencyIT` | 5 | Corridas de reserva/desativação, solicitação/desativação de usuário, avaliações e preço histórico |
| `PostgresDeactivationIT` | 9 | Exclusão lógica, impedimentos e preservação de histórico |
| `PostgresGraphQlIT` | 60 | Queries/mutations, autorização, filtros, paginação, scalars, limites, N+1 e interoperabilidade REST |
| `PostgresSchemaUpgradeIT` | 9 | Upgrade V4→V5, constraints, índices, relacionamentos, categorias, registros e rollback de migração inválida |
| `PostgresEnvironmentIT` | 1 | Datasource PostgreSQL efetivo, Flyway e Hibernate |
| **Total** | **84** | **Contratos executados contra PostgreSQL real** |

São 82 cenários anteriores reexecutados, uma nova corrida de preço e um novo
teste de ambiente. Os 447 resultados da execução completa não representam
447 regras distintas da aplicação.

## 4. Revisão funcional e novos cenários

Foram preservadas as verificações existentes de SecurityFilterChain, assinatura
e expiração JWT, papéis ADMIN/PROVIDER/CLIENT, propriedade dos recursos,
BCrypt/PBKDF2 legado, conta inativa, Bean Validation, BOLA/mass assignment e
respostas 400/401/403/404/409. Os testes GraphQL mantêm as oito queries e seis
mutations, paginação/filtros, scalars, formato de erros, limites de profundidade,
custo e abuso, ausência de campos sensíveis e prevenção de N+1.

Foram acrescentados somente três cenários à suíte padrão:

1. JWT expirado deve ser rejeitado em REST e GraphQL; uma requisição seguinte
   com JWT válido continua funcionando sem sessão.
2. Payload JWT adulterado para trocar CLIENT por ADMIN, preservando a assinatura
   original, deve ser rejeitado nos dois transportes, também sem contaminar a
   requisição seguinte.
3. Criação concorrente de solicitação e atualização de preço do catálogo deve
   preservar o preço capturado na solicitação. O snapshot pode ser o preço
   anterior ou posterior, conforme a ordem de aquisição dos locks; uma edição
   posterior do catálogo não pode reescrevê-lo.

Os dois primeiros estão em `TransportSecurityIntegrationTests`. O terceiro
foi adicionado a `BusinessConcurrencyIntegrationTests` e também roda nos IT.
Não foi necessário corrigir código de produção: os comportamentos passaram.

## 5. Resultados realmente executados

Todas as rodadas abaixo terminaram com **zero falhas, erros ou ignorados**.
As versões observadas foram **PostgreSQL 15.19** e **16.14**.

| Execução | Resultado | Evidência |
| --- | ---: | --- |
| `-Punit clean verify` | 100 aprovados | [Unitários](evidencias/etapa6-unit.json) |
| `-Pweb clean verify` | 236 aprovados | [Web](evidencias/etapa6-web.json) |
| `-Pintegration clean verify` | 27 aprovados | [Integração H2](evidencias/etapa6-integration.json) |
| IT isolados, PostgreSQL 15 | 84 aprovados | [Testcontainers 15](evidencias/etapa6-testcontainers15.json) |
| IT isolados, PostgreSQL 16 | 84 aprovados | [Testcontainers 16](evidencias/etapa6-testcontainers16.json) |
| `clean verify`, ordem aleatória, seed 20261027 | 363 aprovados | [Ordem aleatória](evidencias/etapa6-random-order.json) |
| `-Ptestcontainers clean verify` completo | **363 + 84 = 447 aprovados** | [Maven final](evidencias/etapa6-maven-final.json) |
| Verificador legado PostgreSQL 15 | **363 aprovados** | [Matriz 15](evidencias/etapa6-postgres15.json) |
| Verificador legado PostgreSQL 16 | **363 aprovados** | [Matriz 16](evidencias/etapa6-postgres16.json) |
| JAR em `prod`, HTTP GraphQL/REST, PostgreSQL 15 | 83 verificações aprovadas | [HTTP GraphQL](evidencias/etapa6-http-graphql.json) |
| JAR em `prod`, HTTP REST, PostgreSQL 15 | 45 verificações aprovadas | [HTTP REST](evidencias/etapa6-http-rest.json) |

As execuções foram sequenciais entre comandos Maven para evitar disputa por
`target`. Algumas rodadas usaram `-o` após o download das dependências; os
comandos exatos constam nos JSON. A primeira rodada isolada dos IT 15 não usou
`clean`: sua evidência considera somente `TEST-com.servicehub.Postgres*IT.xml`,
sem contar XMLs antigos presentes no diretório. As rodadas completas e a
rodada isolada 16 usaram `clean`.

O Flyway e Hibernate inicializaram nos dois PostgreSQL. As mensagens de erro
de Flyway provocadas deliberadamente nos cenários de dados legados inválidos
são acompanhadas de asserções de rejeição/rollback; não são falhas da suíte.
As constraints, relacionamentos, índices, categorias e preservação dos dados
foram revalidados pelos cenários de upgrade e persistência.

Os 128 checks HTTP validaram o JAR por socket em `prod`. Uma consulta final,
somente no banco temporário, confirmou oito usuários e dois serviços
desativados logicamente, duas solicitações `COMPLETED` com preço histórico
150,00 e duas avaliações preservadas: [fixtures HTTP](evidencias/etapa6-http-fixtures.json).

## 6. Cobertura JaCoCo medida

O relatório foi gerado ao final do `-Ptestcontainers clean verify`, combinando
as coletas Surefire e Failsafe. Não houve exclusão de classes da aplicação ou
criação de testes apenas para elevar os números. Não foi imposto mínimo.

| Pacote/camada | Linhas cobertas | Ramificações cobertas |
| --- | ---: | ---: |
| Controllers (`com.servicehub.controller`) | **100,00%** | Não há ramificações instrumentadas |
| Services (`com.servicehub.service`) | **89,35%** | **68,75%** |
| Security (`com.servicehub.security`) | **95,27%** | **76,25%** |
| Validation (`com.servicehub.validation`) | **100,00%** | **69,23%** |
| GraphQL (`com.servicehub.graphql`) | **85,62%** | **73,68%** |
| **Projeto completo** | **92,59% — 949/1.025** | **73,06% — 415/568** |

Instruções: **91,37%**; métodos: **93,00%**. O relatório analisou 72 classes
compiladas. Cem por cento de linhas não significa cobertura integral dos
caminhos condicionais.

- HTML navegável: `target/site/jacoco/index.html`.
- XML: `target/site/jacoco/jacoco.xml`.
- [Resumo persistido por pacote e classe](evidencias/etapa6-cobertura.json).
- [CSV persistido por classe](evidencias/etapa6-cobertura.csv).

As principais áreas para futura revisão de cobertura são:

- Services: caminhos internos legados de `save`, `findAll` e exclusão
  administrativa de solicitações/avaliações. Esses deletes não são expostos
  pelos endpoints atuais. `ReviewService` tem 78,26% de linhas e
  `ServiceRequestService`, 83,33%; os contratos públicos principais estão
  cobertos, inclusive concorrência em banco real.
- Tratamento global de erros: caminhos de timeout de lock e respostas de
  fallback merecem cenários dedicados que reproduzam uma falha real. Não
  foram forçadas exceções sem relação com o uso apenas para aumentar cobertura.
- Scalar `Long`: coerção de entrada tem cobertura baixa; o schema atual usa
  esse scalar como saída de contagens, sem argumentos de entrada desse tipo.
- Adaptador Servlet do limite de corpo: callbacks de leitura não bloqueante
  e acessores alternativos não são usados pelo fluxo MVC síncrono exercitado.
- Segurança: permanecem caminhos excepcionais de infraestrutura criptográfica;
  assinatura, expiração, credenciais inválidas e PBKDF2 legado têm testes.

Os processos HTTP externos não receberam agente JaCoCo; seus checks são
evidência funcional separada, sem inflar a cobertura do runner Maven.

## 7. Problemas encontrados e correções na infraestrutura

- A seleção do backend no teste de upgrade dependia do ambiente global.
  Foi criado um hook de conexão para que o IT injete seu próprio container,
  preservando as nove verificações antigas e a conexão mantida pelo H2.
- Uma falha durante a construção desse fixture poderia deixar sua conexão
  aberta. O construtor agora fecha a conexão antes de propagar o erro,
  preservando a falha original e eventual erro de fechamento como suprimido.
- JaCoCo e Mockito precisam coexistir no `argLine`. Foram usadas propriedades
  separadas `jacocoUnitArgLine` e `jacocoItArgLine`, mantendo o argumento
  adicional anterior; as coletas são unidas antes do relatório de `verify`.
- Os scripts HTTP gravavam diretamente nas evidências das Etapas 4/5.
  `ReportPath` e `EvidenceStage` foram acrescentados com os padrões anteriores
  preservados. As novas rodadas não sobrescreveram o histórico.
- Relatórios de execuções parciais podem coexistir em `target` sem `clean`.
  A contagem das evidências foi delimitada ao runner/classe correto e essa
  precaução foi documentada.

Não foi observada dependência de ordem na rodada aleatória, problema de rollback
ou vazamento dos processos Java do runner/API. Os testes temporais existentes
usam relógio fixo ou margens amplas; não foram introduzidos sleeps para expiração.
Uma única seed não demonstra todas as permutações possíveis.

## 8. Arquivos desta etapa

| Arquivos | Alteração |
| --- | --- |
| `pom.xml` | Dependências de teste, grupos, Failsafe e JaCoCo |
| 24 classes existentes em `src/test/java` | Tags `unit`, `web` ou `integration`, conforme inventário |
| `SchemaUpgradeTests.java` | Hook JDBC e fechamento em falha de construção |
| `BusinessConcurrencyIntegrationTests.java` | Cenário de corrida com atualização de preço |
| `security/TransportSecurityIntegrationTests.java` | Dois casos novos de JWT entre REST/GraphQL |
| `testing/PostgresTestContainers.java` | Fábrica de bancos temporários isolados |
| `testing/PostgresContainerConfiguration.java` | Container Spring com `@ServiceConnection` |
| `testing/PostgresIntegration.java` | Anotação de composição para os IT Spring |
| `PostgresConcurrencyIT.java`, `PostgresDeactivationIT.java`, `PostgresGraphQlIT.java` | Reuso dos contratos nos IT |
| `PostgresSchemaUpgradeIT.java`, `PostgresEnvironmentIT.java` | Upgrade JDBC e garantia do ambiente real |
| `scripts/verify-graphql.ps1`, `scripts/verify-rest-business.ps1` | Destino/etapa configuráveis para evidências |
| `README.md`, `docs/TESTES.md`, `docs/ETAPA_06.md` | Execução, requisitos, resultados e limitações |
| `docs/evidencias/etapa6-*.json` e `etapa6-cobertura.csv` | Evidências persistidas sem tokens ou senhas |

Os caminhos Java da tabela são relativos a `src/test/java/com/servicehub`.
As 24 classes com tags estão individualizadas no inventário vinculado acima.
Os arquivos de produção que aparecem no diff Git pertencem à Etapa 5 anterior
e não receberam alterações nesta etapa.

## 9. Docker, preservação e pendências

O Docker Engine **29.7.2** esteve funcional nas execuções. Não foi necessário
resetar Docker Desktop, apagar volumes ou alterar containers anteriores.
O comparativo final confirmou os **16 containers anteriores**, com mesmos
IDs, nomes, imagens e estados, e os **seis volumes nomeados anteriores**.
`servicehub-db` permaneceu parado e seu banco não foi acessado.

Os containers criados pelos IT foram encerrados automaticamente. Os dois bancos
dos verificadores legados foram mantidos pelo uso explícito de
`-KeepTestContainer`, com dados em memória e porta publicada em loopback:

- PostgreSQL 15: `servicehub-v5-test-51e3b2ab083b4e1caa665e25ec2f2df6`.
- PostgreSQL 16: `servicehub-v5-test-d4574cdd3046425f8dafc20b902000e4`.

Não houve remoção manual. Esses dois recursos permanecem disponíveis para
inspeção; sua eventual remoção fica para uma autorização específica.

Os hashes confirmaram V1–V5 intactas, **76 arquivos de `src/main` intactos**
e **31 evidências anteriores intactas**. O HEAD permaneceu
`33797874b5fe7eac75dae23b3e16ff6abc048e53`.
Evidências: [Docker](evidencias/etapa6-preservacao.json),
[fontes/histórico/processos](evidencias/etapa6-preservacao-fonte.json) e
[migrations](evidencias/etapa6-migrations.json).

Nenhum IT depende de PowerShell. Os scripts continuam opcionais para executar
toda a suíte padrão nos dois backends e verificar o JAR por HTTP fora do runner.
Eles preservam os bancos de inspeção por padrão, diferentemente dos IT.

A pendência de ambiente é repetir a matriz em uma **JVM 25**, pois somente
JDK 26 estava instalado. A compilação permaneceu em release 25. A extensão
Jupiter integrada usa a versão 6 gerenciada pelo Boot, conforme explicado
acima. Docker é obrigatório somente quando a seleção PostgreSQL é solicitada.
Não há bloqueio de Docker nem teste PostgreSQL pendente nesta execução.

As verificações executadas confirmam a regressão das Etapas 1–5: migração e
persistência, segurança/validação, JWT, regras REST e GraphQL continuam passando.
A entrega termina na Etapa 6, sem implementação da Etapa 7.
