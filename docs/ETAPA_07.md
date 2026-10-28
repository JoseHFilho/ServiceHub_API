# Etapa 7 — Análise e otimização de consultas PostgreSQL

Medições realizadas em **27/10/2026**, exclusivamente em bancos temporários
com dados sintéticos. Foram preservados os fontes locais das Etapas 1–6,
JWT, autorização, contratos REST/GraphQL e as migrations V1–V5.

Foram implementadas duas otimizações: sondagens correlacionadas com `EXISTS`
para verificar solicitações abertas e a migration **V6**, que substitui o
índice de categoria por um B-tree composto `(category, id)`, mantendo o
predicado `WHERE is_active` no PostgreSQL. Não foram adicionados outros índices.

As duas regressões completas passaram com **461 execuções cada**, sem falhas,
erros ou ignorados. As evidências e a preservação estão na seção 8.

## 1. Análise inicial e consultas atuais

Foram lidos os relatórios das Etapas 1–6, V1–V5, os quatro repositories,
services, DTOs, controllers REST e resolvers GraphQL. A Aula 11 foi consultada
como referência sobre planos, seletividade, ordenação, índices e custos de
manutenção. Seus comandos demonstrativos não foram executados no banco original.

O Git iniciou na branch `codex/atualizacao-etapas-1-a-4`, HEAD
`33797874b5fe7eac75dae23b3e16ff6abc048e53`, com alterações não commitadas das
Etapas 5/6. Foram capturados hashes antes de editar o projeto.
Spring Boot **4.1.1**, Java/**release 25**, dependências e POM foram mantidos.
O runtime disponível e utilizado é **JDK 26.0.2.1**.

O SQL foi registrado antes de alterar o repository, durante 69 cenários
GraphQL/desativação em PostgreSQL 15, todos aprovados:
[SQL JPA anterior](evidencias/etapa7-sql-jpa-antes.txt) e
[SQL JPA posterior, capturado no Failsafe PostgreSQL 15](evidencias/etapa7-sql-jpa-depois.txt).
As consultas de leitura relevantes e seus parâmetros sintéticos estão nos
arquivos de benchmark vinculados na seção 4.

| Fluxo | Comportamento preservado e diagnóstico |
| --- | --- |
| Serviços ativos | `services.is_active` e prestador ativo; ordenação por ID, página padrão 20 e máximo 100 |
| Categoria/prestador | Predicados cumulativos; categoria parametrizada e predicado booleano literal, com COUNT da página |
| Solicitações | CLIENT ou PROVIDER participante por `client_id OR service.provider_id`; ADMIN sem esse filtro; status/serviço opcionais |
| Avaliações | Avaliador ou prestador participante; filtros por solicitação/serviço; UNIQUE de solicitação reutilizado |
| Conta autenticada | JWT carrega o usuário por PK e confere ativação/papel atuais em cada requisição; login usa e-mail normalizado e UNIQUE |
| Prestadores GraphQL | Projeção pública paginada e lote `IN` de IDs, ativos e com papel PROVIDER |
| Administração | Solicitações/avaliações usam paginação existente; listagem administrativa de usuários ativos continua sem paginação |
| Exclusão lógica | Verificação de solicitações abertas, com preservação de histórico e bloqueios transacionais existentes |

`EntityGraph` faz fetch de relações **to-one**, sem fetch paginado de coleções.
Os DTOs de serviços precisam de `providerName`, e os de solicitações/avaliações
precisam de relações já carregadas. Esses graphs foram mantidos para evitar
lazy loads por linha. O `BatchMapping`/DataLoader de prestadores e sua projeção
pública também foram preservados. A autenticação por requisição não foi
removida para reduzir artificialmente o número de consultas.

As rotas REST e GraphQL usam os mesmos services de listagem, ordenação por ID,
filtros e verificações de propriedade. Nenhum filtro de autorização foi
substituído por um filtro baseado somente no papel atual: usuários promovidos
a PROVIDER podem continuar tendo histórico anterior como clientes.

## 2. Índices existentes e decisões

Havia **12 índices de domínio**, todos B-tree, incluindo os de PK/UNIQUE:

| Tabela | Índices antes da otimização |
| --- | --- |
| `users` | `users_pkey(id)`; `users_email_key(email)` UNIQUE |
| `services` | `services_pkey(id)`; `idx_services_provider(provider_id)`; `idx_services_category(category) WHERE is_active` |
| `service_requests` | `service_requests_pkey(id)`; `idx_requests_service(service_id)`; `idx_requests_client(client_id)`; `idx_requests_status(status)` |
| `reviews` | `reviews_pkey(id)`; `reviews_request_id_key(request_id)` UNIQUE; `idx_reviews_reviewer(reviewer_id)` |

A V5 já havia eliminado o índice simples redundante de e-mail. O índice
UNIQUE de `reviews.request_id` já atende ao filtro dessa coluna; outro índice
equivalente seria redundante. Não foi criado índice separado em `is_active`:
95% dos usuários e 80% dos serviços sintéticos estão ativos, tornando pouco
seletivo esse predicado isolado.

O índice de categoria foi **substituído**, mantendo o nome
`idx_services_category`. Após V6 continuam existindo 12 índices. O prefixo
`category` continua atendido e `id` permite entregar a ordem exigida na
primeira página seletiva, sem percorrer a PK descartando categorias alheias.

Outros candidatos foram avaliados e não adotados:

- `(provider_id, id)` parcial: o índice de prestador atual atende aos filtros
  e FKs, inclusive registros inativos. A consulta de um prestador comum ficou
  próxima de 0,1 ms; não houve justificativa para manter outro índice sobreposto.
- `(client_id, status)` e `(service_id, status)`: as sondagens novas já param
  cedo com os índices existentes. Eles não resolveriam por si só o `OR` entre
  tabelas das listagens autorizadas. Não foi declarado ganho sem testar um
  candidato específico para esse outro formato de consulta.
- Índice parcial de status abertos: o método aceita coleções de estados,
  inclusive conjuntos personalizados. Um plano genérico parametrizado não
  garante a implicação do predicado de um índice parcial de estados fixos.
- Expressão `lower(email)`: a entrada já é normalizada e a consulta compara
  diretamente a coluna, aproveitando o UNIQUE existente.
- Índice adicional de prestadores ativos: a página usa a PK e a contagem faz
  Seq Scan pequeno sobre 30 mil usuários. Não foi demonstrada necessidade de
  acrescentar manutenção para essa distribuição.

Hash, GIN, GiST, BRIN e SP-GiST não correspondem aos padrões medidos de
igualdade, JOIN e ordenação por ID; não foram criados.

## 3. Alterações implementadas

### Sondagens de solicitações abertas

Antes, `hasOpenRequestsForUser` contava todos os pedidos que satisfaziam o
`OR` entre cliente e prestador:

```sql
SELECT count(r.id) > 0
FROM service_requests r JOIN services s ON s.id = r.service_id
WHERE (r.client_id = :userId OR s.provider_id = :userId)
  AND r.status IN (:statuses);
```

O HQL novo gera o equivalente a:

```sql
SELECT count(u.id) > 0
FROM users u
WHERE u.id = :userId
  AND (
    EXISTS (SELECT 1 FROM service_requests r
            WHERE r.client_id = u.id AND r.status IN (:statuses))
    OR EXISTS (SELECT 1 FROM service_requests r
               JOIN services s ON s.id = r.service_id
               WHERE s.provider_id = u.id AND r.status IN (:statuses))
  );
```

O COUNT externo conta **no máximo um usuário por PK**. As duas sondagens
podem terminar no primeiro pedido correspondente e reutilizam os índices de
cliente, serviço e prestador. A consulta continua sendo um único comando SQL.
Ativação, papéis, estados considerados abertos e a ordem dos locks dos services
não foram alterados. Para usuário inexistente, as FKs garantem equivalência
com a ausência de pedidos participantes.

Uma primeira tentativa sem linha externa apresentou leitura incorreta no H2
na sequência consulta/inserção/nova consulta. O teste novo detectou o resultado
incorreto. A correlação com o usuário por PK eliminou o comportamento observado;
o cenário foi mantido para prevenir regressão, com conjuntos de estados vazios,
personalizados e histórico de participação nos dois lados.

### Migration V6

`V6__Order_active_services_by_category.java` executa, no PostgreSQL:

```sql
DROP INDEX idx_services_category;
CREATE INDEX idx_services_category ON services(category, id) WHERE is_active;
```

No H2, mantém um índice completo `(category, id)`, porque esse backend de
testes não suporta índices parciais, conforme a estratégia já utilizada pela V5.
Não há UPDATE/DELETE de dados nem alteração de constraints. As migrations
V1–V5 não foram editadas.

O Flyway executa V6 em transação. O `CREATE INDEX` normal bloqueia escritas
durante a construção; a implantação em um banco existente requer janela
planejada. Não foi usado `CONCURRENTLY` dentro de transação e não houve tentativa
de aplicar a migration no banco original. O teste e o benchmark verificam
upgrade V5→V6 populado, preservação de todos os registros, definição do índice
e ausência de segunda execução pelo Flyway.

V6 é uma migration Java, como V5, sem checksum automático por padrão do
Flyway. Seu código deve permanecer imutável após aplicação; mudanças futuras
devem ser feitas em outra migration.

## 4. Carga sintética e protocolo

Cada fase/versionamento criou um container novo, com credenciais aleatórias,
porta Docker dinâmica, dados em `tmpfs` e sem volumes existentes. O ciclo de
vida dos containers é gerenciado pelo Testcontainers, com reutilização desativada.

| Entidade | Linhas e distribuição |
| --- | --- |
| Usuários | 30.000: 3.000 PROVIDER e 27.000 CLIENT; 95% ativos |
| Serviços | 60.000; 48.000 ativos; 90% LIMPEZA, 9% ELETRICA, 1% CONSULTORIA_TI |
| Solicitações | 600.000: 60.000 PENDING, 30.000 ACCEPTED, 30.000 IN_PROGRESS, 360.000 COMPLETED, 120.000 CANCELLED |
| Avaliações | 180.000, somente sobre solicitações COMPLETED, uma por solicitação avaliada |
| Total por banco | **870.000 registros de domínio** |

O prestador de maior volume tem 3.000 serviços e 29.999 solicitações; o cliente
3007 tem 22 solicitações; o cliente 29999 não tem solicitações. Há serviços e
usuários inativos com histórico preservado. IDs, e-mails `.invalid`, hashes
inutilizáveis para login e timestamps fixos são inteiramente sintéticos.
O gerador está em `src/test/resources/benchmark/postgres-data.sql`.

O protocolo aplica V5 e carrega os dados; a fase posterior aplica V6 sobre
essa carga e compara o hash de todas as linhas/colunas das quatro tabelas.
Depois executa `VACUUM (ANALYZE)` para atualizar estatísticas e visibility map.
Não foi desabilitado Seq Scan para forçar a seleção de índices.

Foram medidas **30 consultas por fase/versão**, com:

1. Resultado e hash, preservando a ordem quando a consulta define ORDER BY.
2. `EXPLAIN (FORMAT JSON)` apenas estimado.
3. Uma execução de aquecimento excluída da mediana.
4. Cinco amostras `EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT JSON)`.
5. Casos adicionais de plano genérico preparado, com `force_generic_plan`,
   restaurando `plan_cache_mode` ao fim da seleção.

Os SQLs normalizados preservam JOINs, colunas carregadas, filtros e ordenação
observados no JPA, com valores sintéticos substituindo os parâmetros. Os casos
genéricos modelam os mesmos parâmetros lógicos; não medem o protocolo de rede
ou a escolha automática de plano de uma sessão de produção.

Também foram medidos INSERT, UPDATE e DELETE de lotes de 1.000 serviços e
1.000 solicitações, cinco vezes, com **ROLLBACK** de cada sequência.
Essas escritas e seus EXPLAIN ANALYZE ocorreram apenas nos containers novos.
O custo futuro de VACUUM para remover entradas mortas de índices não está
incluído no tempo de DELETE.

Dados completos, incluindo planos estimados/reais, loops, linhas filtradas,
buffers, WAL, configurações do servidor e tamanho dos índices:

- [PostgreSQL 15 antes](evidencias/etapa7-postgres15-before.json) e
  [depois](evidencias/etapa7-postgres15-after.json).
- [PostgreSQL 16 antes](evidencias/etapa7-postgres16-before.json) e
  [depois](evidencias/etapa7-postgres16-after.json).
- [Comparação por consulta e nós dos planos](evidencias/etapa7-comparacao.json).
- [CSV comparativo](evidencias/etapa7-comparacao.csv).

## 5. Resultados de EXPLAIN ANALYZE

Tempos abaixo são medianas de cinco execuções, em milissegundos. Buffers são
`shared hit` do plano da amostra mediana; não devem ser somados aos buffers
dos seus filhos. Os casos destacados tiveram zero `shared read` nessa amostra.

| Consulta | PG15 antes → depois | Buffers PG15 | PG16 antes → depois | Buffers PG16 |
| --- | ---: | ---: | ---: | ---: |
| Pedidos abertos, prestador de maior volume | 49,623 → 0,083 | 9.715 → 13 | 53,818 → 0,075 | 9.714 → 13 |
| Pedidos abertos, cliente | 53,123 → 0,055 | 9.715 → 9 | 67,179 → 0,062 | 9.714 → 9 |
| Pedidos abertos, usuário sem pedidos | 46,911 → 0,071 | 9.715 → 8 | 57,128 → 0,048 | 9.715 → 8 |
| Categoria rara, primeira página | 0,480 → 0,079 | 62 → 25 | 0,346 → 0,094 | 62 → 25 |
| Categoria rara, offset 200 | 2,279 → 2,135 | 666 → 668 | 2,294 → 2,112 | 666 → 668 |
| Categoria rara, contagem | 1,893 → 1,714 | 666 → 668 | 1,600 → 1,552 | 666 → 668 |
| Categoria, plano genérico preparado | 0,429 → 0,533 | 62 → 62 | 0,527 → 0,413 | 62 → 62 |

Há ganho claro e repetido nas sondagens de pedidos abertos. No PG15, o caso
de cliente passava por `Bitmap Heap Scan` de 120.000 pedidos e Seq Scan de
60.000 serviços, distribuídos entre três participantes paralelos, seguidos de
JOIN e agregação. Depois, o usuário usa `Index Only Scan` da PK, sem heap
fetch nessa amostra; `idx_requests_client` encontra uma correspondência após
filtrar duas linhas, e o ramo de prestador nem precisa ser executado.

O custo estimado desse caso foi de **14.858,40 para 56,45** no PG15 e de
**14.821,25 para 51,26** no PG16. São unidades do planner, não milissegundos.
Os tempos reais acima são métricas separadas dessas estimativas.
Nos planos genéricos, as três sondagens também passaram de aproximadamente
90–104 ms para 0,079–0,120 ms no PG15; os valores individuais estão no CSV.

Na primeira página da categoria rara, a PK de serviços precisava descartar
1.881 linhas para devolver 20. A V6 permitiu `Index Scan` em
`idx_services_category`, sem esse descarte. O custo estimado do plano caiu de
229,23 para 122,30 no PG15 e de 230,05 para 121,86 no PG16.

O benefício do índice é **específico desse acesso seletivo e ordenado**.
Na página com offset e na contagem, o planner manteve acesso bitmap/sort ou
agregação com trabalho semelhante. As pequenas diferenças de tempo não
demonstram uma melhoria relevante desses casos. O plano genérico continuou
usando a PK, com os mesmos 62 buffers, e seus tempos variaram nos dois sentidos.
Não foi apresentada melhoria garantida para qualquer categoria, offset ou
sessão preparada.

Foram observados Seq Scan, Index Scan, Index Only Scan e Bitmap Scan. Seq Scan
continua sendo uma escolha razoável para retornar a maioria dos usuários
ativos ou contar uma fração considerável dos usuários. Index Only Scan depende
do visibility map; não se pode assumir zero heap fetch sob carga de escrita.

Os hashes das **30 consultas foram iguais em cada comparação 15/16**, incluindo
as páginas ordenadas. O hash de todo o grafo populado também permaneceu igual
após V6. Não foram inventados percentuais de ganho nem convertidas unidades
de custo em tempo.

## 6. Custo de manutenção e limites do ganho

O índice de categoria passou de **335.872 para 1.572.864 bytes** nas duas
versões. Incluir IDs distintos aumenta o tamanho em relação ao B-tree somente
de categoria, que aproveitava a repetição das chaves. Ele substitui o índice
antigo, evitando manter ambos.

| Lote de 1.000 INSERTs em serviços | PG15 antes → depois | PG16 antes → depois |
| --- | ---: | ---: |
| Mediana de execução | 14,883 → 16,342 ms | 13,825 → 13,917 ms |
| WAL da amostra mediana | 337.248 → 442.980 bytes | 337.283 → 350.273 bytes |

Os tempos de UPDATE/DELETE e os controles de solicitações variaram para cima
e para baixo. Por exemplo, o UPDATE de status de solicitações no PG16 variou
de 32,707 para 38,068 ms, sem alteração dos índices dessa tabela. Essa variação
não foi atribuída causalmente à otimização do catálogo. As amostras de escrita
e WAL estão integralmente preservadas, inclusive diferenças de splits/páginas
entre versões/rodadas.

A decisão de manter V6 considera a redução de trabalho na primeira página de
uma categoria seletiva, com um acréscimo de cerca de 1,18 MiB no índice desta
carga. O ganho absoluto do catálogo é inferior a 1 ms aqui; não é justificativa
para prometer aceleração geral da API. Um ambiente com predominância de
escritas ou outros perfis de consulta deve reavaliar esse trade-off com sua
carga real antes da implantação.

O upgrade populado medido levou aproximadamente **198 ms no PG15** e
**216 ms no PG16**, incluindo a chamada Flyway, sobre 60 mil serviços.
Esses valores não são estimativas de janela para bancos maiores.

## 7. Custos que permanecem e confiabilidade

- O `OR` entre cliente e prestador das **listagens autorizadas** não foi
  reescrito. A página de solicitações do cliente mediu aproximadamente 54–57 ms;
  a contagem autorizada, aproximadamente 55–65 ms. Esses planos e custos
  permanecem registrados. Uma alternativa com UNION/semijoin precisa de
  comparação própria de páginas, contagens, usuários com participação dupla
  e planos genéricos, antes de alterar esse caminho de autorização.
- OFFSET exige percorrer/descartar resultados anteriores. Não foi introduzido
  cursor/keyset, pois isso mudaria o contrato de paginação REST/GraphQL.
- A listagem administrativa de usuários continua devolvendo todos os ativos;
  o Seq Scan medido é coerente com 95% de ativação. A latência e o tamanho da
  resposta HTTP dessa operação não foram medidos neste benchmark SQL.
- A carga usa distribuição determinística com três categorias ocupadas,
  prestador de maior volume e histórico sintético. Não representa estatísticas
  observadas de produção, que não foi acessada.
- Os tempos são de execução no servidor, com cache aquecido e `tmpfs`.
  Não representam disco frio, rede, hidratação JPA, JWT, serialização ou latência
  completa de endpoint. EXPLAIN ANALYZE também acrescenta instrumentação.
- As cinco amostras de cada caso mostram variação local, sem teste estatístico
  ou ensaio de carga concorrente de produção. Não há limiar de tempo no JUnit.

## 8. Regressão, arquivos e preservação

| Execução completa | Surefire/H2 | Failsafe/PostgreSQL | Total | Falhas/erros/ignorados |
| --- | ---: | ---: | ---: | --- |
| PostgreSQL 15.19 | 370 | 91 | **461** | **0 / 0 / 0** |
| PostgreSQL 16.14 | 370 | 91 | **461** | **0 / 0 / 0** |

As 370 execuções padrão incluem 100 unitárias, 236 web e 34 de integração.
Os 91 cenários PostgreSQL incluem todos os 84 anteriores e os sete novos.
São execuções de contratos compartilhados em outro backend, não 461 regras
distintas. A seleção de benchmark não está incluída nesses totais.

Evidências: [Maven PostgreSQL 15](evidencias/etapa7-postgres15-maven-final.json)
e [Maven PostgreSQL 16](evidencias/etapa7-postgres16-maven-final.json), com
[detalhamento Surefire](evidencias/etapa7-postgres16-standard.json) e
[detalhamento Failsafe](evidencias/etapa7-postgres16-postgres.json).
Os dois comandos completos usaram `-o` com as dependências já disponíveis;
a rodada 15 habilitou `spring.jpa.show-sql` apenas para registrar o SQL.

Flyway aplicou/validou as seis migrations e Hibernate inicializou com
`ddl-auto=validate` nos dois PostgreSQL. As verificações de JWT, propriedade,
REST/GraphQL, filtros/paginação, estados, preço histórico, avaliação única,
exclusão lógica, concorrência e N+1 continuam passando. As rejeições e rollbacks
de V5 deliberadamente provocados pelos testes de dados legados inválidos
permanecem aprovados; suas mensagens ERROR não são falhas da suíte.

O JaCoCo consolidado da última rodada registrou **92,56% das linhas**
(958/1.035) e **73,17% das ramificações** (420/574), sem mínimo obrigatório.
Os percentuais das camadas controller/service/security/validation/GraphQL
permaneceram iguais aos da Etapa 6; a migration nova acrescenta código ao total.
Consulte o [resumo atual](evidencias/etapa7-cobertura.json),
[CSV por classe](evidencias/etapa7-cobertura.csv) e o HTML em
`target/site/jacoco/index.html`. Nenhum teste de tempo foi criado para inflar
cobertura ou tornar o build dependente da velocidade do host.

Os sete cenários novos da suíte padrão são:

- Cinco estados parametrizados para comparar a consulta nova com a antiga,
  nos lados cliente/prestador e com terceiro sem acesso.
- Um cenário de participação histórica dupla, status personalizados, conjunto
  vazio e releitura após nova inserção.
- Um upgrade V5→V6 com grafo populado, índice correto, dados intactos e
  relacionamentos restritivos preservados.

Os mesmos sete foram acrescentados à seleção PostgreSQL por herança. Os 363
cenários padrão e os 84 PostgreSQL anteriores não foram removidos ou ignorados.
A expectativa dos dois testes de versão atual do schema passou de cinco para
seis migrations; seus objetivos de Flyway/Hibernate foram preservados.

Arquivos principais desta etapa:

| Arquivo | Alteração |
| --- | --- |
| `repository/ServiceRequestRepository.java` | Sondagens correlacionadas de existência |
| `db/migration/V6__Order_active_services_by_category.java` | Substituição do índice de categoria |
| `QueryOptimizationIntegrationTests.java` | Seis casos de equivalência/leitura repetida |
| `IndexUpgradeTests.java` | Upgrade populado e metadados do índice |
| `PostgresQueryOptimizationIT.java`, `PostgresIndexUpgradeIT.java` | Reexecução dos casos novos em PostgreSQL |
| `MigrationIntegrationTests.java`, `PostgresEnvironmentIT.java` | Expectativa da versão atual V6 |
| `PostgresQueryBenchmark.java` | Medição opcional, fora da seleção de regressão |
| `src/test/resources/benchmark/postgres-data.sql` | Gerador de dados sintéticos |
| `README.md`, `docs/TESTES.md`, este relatório | Uso, resultados e limitações |
| `docs/evidencias/etapa7-*` | SQLs, planos, comparações e resultados |

Os nomes de testes são relativos a `src/test/java/com/servicehub`; o repository
e a migration estão em `src/main/java`.

Foram corrigidos problemas encontrados durante o trabalho: a leitura repetida
da primeira consulta com EXISTS no H2; um target Flyway nulo no fixture novo,
substituído por `latest`; e o coletor PowerShell de evidências, que precisava
converter seus registros para PSCustomObject antes de somar os XMLs. As rodadas
intermediárias com falha não foram contabilizadas como aprovadas.

O Docker Engine **29.7.2** permaneceu funcional. A auditoria final confirmou
os **18 containers anteriores**, com mesmos IDs/nomes/imagens/estados, e os
**seis volumes nomeados anteriores**. Não restou container novo ou processo
Java dos testes/API. Os bancos das Etapas 5/6 mantidos para inspeção não foram
alterados. O `servicehub-db` permaneceu parado e seu conteúdo não foi acessado.
Não houve remoção manual, reset ou execução de Compose.

Os hashes de V1–V5 e dos relatórios/evidências anteriores permaneceram iguais.
Somente cinco arquivos preexistentes mudaram nesta etapa: README, TESTES,
ServiceRequestRepository e as duas expectativas de versão do schema. Os demais
arquivos desta etapa são novos. O HEAD continuou inalterado e não houve commit,
push ou alteração de dependências. Evidências:
[preservação dos fontes](evidencias/etapa7-preservacao-fonte.json) e
[recursos Docker](evidencias/etapa7-preservacao-docker.json).

## 9. Reprodução e próximos passos

```powershell
# Regressão completa, com bancos novos gerenciados pelo Testcontainers:
.\mvnw.cmd -Ptestcontainers clean verify
.\mvnw.cmd -Ptestcontainers '-Dpostgres.version=16' clean verify

# Benchmark explícito; use outro diretório para preservar a rodada entregue:
.\mvnw.cmd -Ptestcontainers '-Dtest=PostgresQueryBenchmark' '-Dbenchmark.phase=before' '-Dbenchmark.output=.tmp/minha-medicao' test
.\mvnw.cmd -Ptestcontainers '-Dtest=PostgresQueryBenchmark' '-Dbenchmark.phase=after' '-Dbenchmark.output=.tmp/minha-medicao' test
# Acrescente '-Dpostgres.version=16' para repetir nessa versão.
```

JDK 25 ou superior, Maven Wrapper e Docker funcional são necessários. O comando
sem perfil continua executando a suíte padrão com H2 e gerando JaCoCo. O
benchmark não termina em Test/Tests/IT e só executa por seleção explícita;
nenhum teste de regressão é descartado para comportá-lo.

A validação em uma JVM 25 permanece pendente por indisponibilidade desse
runtime local; release 25 foi mantido. Não foi feita aplicação de V6 no banco
original, medição de produção ou nova verificação HTTP por socket; os 128 checks
HTTP da Etapa 6 continuam sendo evidências históricas, sem reclassificação.

O próximo escopo é a Etapa 8, com empacotamento da API e Compose completo,
configuração de execução/segredos e verificações de saúde/integração. Esse
trabalho **não foi iniciado** nesta etapa. Nenhum commit ou push foi realizado.

## Referências

- [Aula 11 fornecida](<D:/download/Aula 11 (1).pptx>), slides 3–7 e 13–16.
- [PostgreSQL 15: EXPLAIN](https://www.postgresql.org/docs/15/sql-explain.html).
- [PostgreSQL 16: índices compostos](https://www.postgresql.org/docs/16/indexes-multicolumn.html).
- [PostgreSQL 16: índices parciais](https://www.postgresql.org/docs/16/indexes-partial.html).
- [PostgreSQL 16: Index Only Scan](https://www.postgresql.org/docs/16/indexes-index-only-scans.html).
- [PostgreSQL 16: EXISTS](https://www.postgresql.org/docs/16/functions-subquery.html).
