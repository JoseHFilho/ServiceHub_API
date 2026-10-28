# Testes da ServiceHub API

A suíte padrão contém **370 cenários**, incluindo os 363 das Etapas 1–6.
A seleção PostgreSQL/Testcontainers acrescenta **91 execuções**, reutilizando
os testes de banco existentes. São execuções dos mesmos contratos em outro
backend, e não 91 regras novas da aplicação.

## Requisitos

JDK 25 ou superior, JAVA_HOME correto e Maven Wrapper do projeto. O build
compila com release 25. As Etapas 6/7 usaram o JDK 26.0.2.1 do host.
Na Etapa 8, o build e as duas matrizes completas foram executados em
**Java 25.0.4.1 em containers**, preservando a mesma suíte.

Para Testcontainers: Docker funcional e permissão de acesso ao Engine.
Nenhum PostgreSQL previamente iniciado é necessário. O primeiro build pode
baixar dependências Maven e imagens postgres:15/16 e Ryuk.
PowerShell 7 só é necessário para os verificadores .ps1, não para Testcontainers.

As dependências de teste são gerenciadas pelo Spring Boot 4.1.1:
Testcontainers 2.0.5, JUnit Jupiter 6.0.3 e Failsafe/Surefire 3.5.6.
O projeto já usava Jupiter gerenciado pelo Boot; a extensão Jupiter do
Testcontainers foi integrada sem forçar downgrade das dependências.
JaCoCo 0.8.15 suporta as versões Java utilizadas.

## Execução padrão, sem Docker

~~~powershell
.\mvnw.cmd clean verify
~~~

Executa **todos os 370 testes padrão** com H2 onde há persistência, gera
o JAR e os relatórios JaCoCo. Não executa os arquivos terminados em IT.
Não há testes ignorados por falta de Docker nessa execução.

## Grupos separados

As tags JUnit classificam os testes sem mover ou reescrever a suíte:

| Perfil | Cenários | Conteúdo |
| --- | ---: | --- |
| unit | 100 | Regras puras, hash/JWT, validação/scalars, Mockito e controllers diretos |
| web | 236 | Contratos MockMvc/HttpGraphQlTester com cadeia Spring/JWT real |
| integration | 34 | Contexto, repositories, Flyway/upgrade, desativação e concorrência em H2 |

~~~powershell
.\mvnw.cmd -Punit clean verify
.\mvnw.cmd -Pweb clean verify
.\mvnw.cmd -Pintegration clean verify
~~~

Use esses três perfis separadamente. Os testes web existentes são integrações
com contexto Spring e H2, não slices WebMvcTest: isso preserva a cobertura
real de segurança, autorização e negócio. Os perfis não alteram o datasource
da aplicação nem iniciam Docker. Seleção vazia é erro de build.

## Testcontainers e Failsafe

~~~powershell
.\mvnw.cmd -Ptestcontainers clean verify
.\mvnw.cmd -Ptestcontainers '-Dpostgres.version=16' clean verify
~~~

Sem outro filtro, cada comando executa **370 cenários padrão pelo Surefire**
e depois **91 em PostgreSQL pelo Failsafe**: 461 execuções.
O padrão é PostgreSQL 15; somente 15/16 são aceitos.
Não combine com os perfis seletivos unit/web/integration para uma verificação
completa.

A seleção PostgreSQL contém:
- PostgresConcurrencyIT: cinco disputas entre transações reais.
- PostgresDeactivationIT: nove cenários de exclusão lógica/histórico.
- PostgresGraphQlIT: 60 cenários GraphQL, autorização, REST e N+1.
- PostgresSchemaUpgradeIT: nove cenários de upgrade, constraints e registros.
- PostgresEnvironmentIT: uma verificação do datasource real, Flyway e Hibernate.
- PostgresQueryOptimizationIT: seis cenários de equivalência da consulta de existência.
- PostgresIndexUpgradeIT: um cenário de upgrade V5→V6 populado e preservação do índice/dados.

Para executar **somente** esses 91 cenários, sem a fase de empacotamento:

~~~powershell
.\mvnw.cmd -Ptestcontainers '-Dtest=Postgres*IT' clean test
.\mvnw.cmd -Ptestcontainers '-Dpostgres.version=16' '-Dtest=Postgres*IT' clean test
~~~

Esse filtro explícito executa os IT pelo Surefire na fase test.
Não o combine com verify, pois o Failsafe também executaria os IT.
Para cobertura consolidada e uso habitual, prefira o primeiro comando completo.

## Datasource, isolamento e ciclo de vida

PostgresContainerConfiguration declara um PostgreSQLContainer como bean de
teste, com ServiceConnection. O Boot fornece URL, porta e credenciais
automaticamente, com precedência sobre H2. O contêiner começa antes de
Flyway/Hikari/JPA e termina depois do fechamento desses beans. O contexto
pode ser reutilizado entre classes sem apontar para um banco já encerrado.

O upgrade JDBC usa a extensão JUnit Testcontainers/Container, em outro banco
temporário, e um schema aleatório por cenário. O hook de conexão não modifica
system properties ou variáveis globais. As asserções antigas foram mantidas.

Todos os bancos são novos, com nome/usuário/senha aleatórios e dados em tmpfs.
As portas são alocadas pelo Docker. Reuse fica desativado e nenhum volume ou
container da aplicação é usado. A suíte encerra somente seus próprios
containers; não faz docker prune, reset ou remoção por nome genérico.

Testes transacionais fazem rollback; as disputas independentes removem
somente seus fixtures por IDs. Os testes são sequenciais por padrão.
Flyway aplica V1–V6 e Hibernate continua com ddl-auto=validate.
PostgresEnvironmentIT impede sucesso por fallback acidental para H2.

Docker indisponível faz a execução solicitada falhar. Não foi habilitado
disabledWithoutDocker e nenhum IT é marcado como aprovado ou ignorado
automaticamente.

## JaCoCo e Mockito

~~~powershell
.\mvnw.cmd clean verify
# Ou, para incluir as execuções PostgreSQL:
.\mvnw.cmd -Ptestcontainers clean verify
~~~

Relatórios:
- target/site/jacoco/index.html: HTML navegável.
- target/site/jacoco/jacoco.xml: XML para ferramentas.
- target/site/jacoco/jacoco.csv: CSV por classe.
- target/surefire-reports: suíte padrão.
- target/failsafe-reports: seleção PostgreSQL na execução completa.

Os agentes usam propriedades diferentes: jacocoUnitArgLine e
jacocoItArgLine. O argLine adicional e o agente Mockito existente são
preservados. As coletas jacoco-unit.exec/jacoco-it.exec são combinadas em
jacoco.exec antes do relatório. Não há percentual mínimo obrigatório.

Use clean ao comparar execuções isoladas. XMLs/execs de uma execução parcial
podem permanecer em target quando Maven é chamado sem clean; isso não
significa que os testes antigos rodaram novamente.

## Ordem, horário e evidências

Para uma rodada reproduzível em ordem aleatória:

~~~powershell
.\mvnw.cmd '-Djunit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random' '-Djunit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random' '-Djunit.jupiter.execution.order.random.seed=20261027' clean verify
~~~

Os testes temporais de JWT usam Clock fixo ou deslocamentos amplos do
instante atual, sem esperas artificiais. Concorrência usa barreiras e futures
com timeout. As configurações padrão não habilitam execução paralela.

## Scripts PowerShell preservados

Nenhum IT depende dos scripts. Eles continuam úteis para:
- Executar **toda a suíte padrão**, inclusive casos sem necessidade de
  PostgreSQL, na matriz externa 15/16.
- Validar o JAR por socket HTTP real, fora do runner de testes.

~~~powershell
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
~~~

Esses dois comandos mantêm seus containers por padrão para inspeção.
Isso difere do ciclo de vida automático dos containers criados pelos IT.

Os verificadores HTTP agora aceitam destino e etapa da evidência, sem
alterar seus valores padrão anteriores:

~~~powershell
.\scripts\verify-graphql.ps1 -ContainerName 'servicehub-v5-test-<ID>' -ReportPath 'docs/evidencias/etapa6-http-graphql.json' -EvidenceStage 6
.\scripts\verify-rest-business.ps1 -ContainerName 'servicehub-v5-test-<ID>' -ReportPath 'docs/evidencias/etapa6-http-rest.json' -EvidenceStage 6
~~~

Assim, uma rodada nova não sobrescreve as evidências históricas.
Os scripts aceitam apenas os bancos de teste isolados previstos, geram
fixtures sintéticos, desativam logicamente as contas ao concluir com sucesso
e encerram sua própria API. Relatórios não incluem senhas ou JWTs.

Consulte [ETAPA_06.md](ETAPA_06.md) para os resultados realmente executados,
cobertura por camada, problemas encontrados e limitações.

## Benchmark PostgreSQL da Etapa 7

`PostgresQueryBenchmark` é uma medição opcional, fora da seleção de regressão.
Não termina em Test/Tests/IT e só roda por seleção explícita. Não há asserção
de tempo que possa deixar o build instável. Cada comando cria seu próprio
container, aplica V5, carrega somente dados sintéticos e mede a linha de base
ou faz upgrade populado para V6 antes de medir a versão otimizada.

~~~powershell
.\mvnw.cmd -Ptestcontainers '-Dtest=PostgresQueryBenchmark' '-Dbenchmark.phase=before' test
.\mvnw.cmd -Ptestcontainers '-Dtest=PostgresQueryBenchmark' '-Dbenchmark.phase=after' test
# Acrescente '-Dpostgres.version=16' para repetir na outra versão.
~~~

As fases usam SQL normalizado a partir das consultas JPA capturadas, com
parâmetros sintéticos fixos. O benchmark não faz chamadas HTTP nem mede latência
completa da API. Salva EXPLAIN estimado, cinco amostras ANALYZE/BUFFERS/WAL,
resultados por hash, planos genéricos preparados, distribuição dos dados,
tamanho dos índices e escritas revertidas. As evidências são
`docs/evidencias/etapa7-postgres<15|16>-<before|after>.json`;
`-Dbenchmark.output=<diretório>` permite guardar outra rodada sem sobrescrevê-las.
Use somente a fase `test` nesse comando explícito.

O dataset e o protocolo completo estão em [ETAPA_07.md](ETAPA_07.md).

## Java 25 e ambiente completo em Docker

~~~powershell
# Não requer JDK/Maven instalados no Windows:
.\scripts\verify-tests-java25.ps1
# Para cache local parcial ou primeira execução sem utilizá-lo:
.\scripts\verify-tests-java25.ps1 -NoHostCache
~~~

O script constrói o estágio JDK do Dockerfile e executa Maven Wrapper com
Testcontainers em PostgreSQL 15/16, independentemente da aplicação Compose.
Os 370/91 cenários continuam os mesmos. O socket Docker é montado somente
nesse runner descartável; ele não é montado no serviço de API.
Os relatórios XML/JaCoCo ficam em `.tmp/etapa8-tests-<ID>` e os resumos em
`docs/evidencias/etapa8-java25-*`.

O Dockerfile também executa `clean verify` padrão durante a construção da
imagem. O estágio opcional `test-results` permite exportar os relatórios:

~~~powershell
docker build --target test-results --output type=local,dest=.tmp/java25-build-tests .
~~~

Validação HTTP do JAR **dentro da imagem final**, com projeto e volume novos:

~~~powershell
.\scripts\verify-compose.ps1 -PostgresVersion 15
.\scripts\verify-compose.ps1 -PostgresVersion 16
~~~

Os procedimentos e requisitos operacionais estão em [DOCKER.md](DOCKER.md),
e os resultados reais e a preservação dos recursos, em [ETAPA_08.md](ETAPA_08.md).
