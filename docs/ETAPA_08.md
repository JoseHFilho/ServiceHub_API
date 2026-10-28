# Etapa 8 — Docker Compose e integração completa

Concluída em **27/10/2026**, em ambientes novos e isolados. A API foi construída
e executada em **Java 25.0.4.1**, mantendo **Spring Boot 4.1.1 e release 25**.
As duas instalações Compose ficaram saudáveis, em PostgreSQL **15.19** e
**16.14**. A aplicação original e seu banco não foram iniciados ou migrados.

| Validação realmente executada | Resultado | Falhas/erros/ignorados |
| --- | ---: | --- |
| Maven `clean verify` na construção JDK 25, H2 | **370 testes** | **0 / 0 / 0** |
| Matriz completa JDK 25 + Testcontainers PostgreSQL 15 | **370 + 91 = 461 execuções** | **0 / 0 / 0** |
| Matriz completa JDK 25 + Testcontainers PostgreSQL 16 | **370 + 91 = 461 execuções** | **0 / 0 / 0** |
| Compose PostgreSQL 15, imagem final em prod | **121 verificações aprovadas** | **0 verificações reprovadas** |
| Compose PostgreSQL 16 novo, mesma imagem em prod | **119 verificações aprovadas** | **0 verificações reprovadas** |

As verificações Compose incluem HTTP real, asserções semânticas, saúde,
persistência, configuração e auditoria; não representam 240 requisições HTTP
distintas. As matrizes JUnit reexecutam contratos compartilhados em H2 e
PostgreSQL. Os 370/91 cenários anteriores foram integralmente preservados,
sem editar ou desabilitar testes.

Não houve commit, push, frontend, funcionalidade REST/GraphQL nova, alteração
de regra de negócio ou edição das migrations **V1–V6**.

## 1. Diagnóstico e preservação inicial

Foram considerados os relatórios das Etapas 1–7 e revisados README, POM,
Compose, perfis Spring, SecurityConfig, JWT e as migrations. O Compose anterior
iniciava somente PostgreSQL 15, com nome fixo `servicehub-db`, porta de banco
publicada em loopback e credencial de demonstração no arquivo. Não havia
Dockerfile para a API.

O ambiente tinha **Docker Engine 29.7.2**, **Compose 5.3.1**, **18 containers**,
**seis volumes nomeados** e **sete redes**. O banco original `servicehub-db`
estava parado. Seu volume é `servicehub-api_servicehub-postgres-data`, e a
rede original é `servicehub-api_default`. Outros recursos pertenciam a Bookify,
NFC Fácil e validações anteriores; todos foram mantidos.

A branch permaneceu `codex/atualizacao-etapas-1-a-4`, HEAD
`33797874b5fe7eac75dae23b3e16ff6abc048e53`. As alterações locais anteriores
das Etapas 5–7 foram preservadas. Foram capturados hashes dos arquivos antes
de alterá-los e inventários Docker; a comparação final abrange 211 arquivos.

## 2. Dockerfile e imagem real

As imagens oficiais Eclipse Temurin foram verificadas no registro com
`docker buildx imagetools inspect`, antes da escolha. O Dockerfile fixa os
digests dos manifests de `25-jdk-noble` e `25-jre-noble`; os valores completos
estão no arquivo e na [auditoria da imagem](evidencias/etapa8-image.json).
O ambiente executado foi Linux/amd64 no Docker Desktop/WSL2.

O Dockerfile tem:

1. Estágio `build`: JDK 25, Maven Wrapper, cache de dependências e execução
   efetiva de `./mvnw -B -ntp clean verify`, sem pular os 370 testes padrão.
2. Estágio opcional `test-results`: exportação dos relatórios Surefire/JaCoCo.
   A exportação reutilizou a camada aprovada; não foi contada como outro teste.
3. Estágio `runtime`: JRE 25, JAR e classe mínima de healthcheck, com usuário
   **10001:10001**, sem JDK/Maven na imagem final.

O `.dockerignore` usa uma lista de inclusão de Maven Wrapper, POM, fontes e
healthcheck. `.env`, `.git`, `.tmp`, `target`, documentação, backups e arquivos
de entrega não são enviados ao build. As configurações reais de banco/JWT
entram somente ao criar os containers.

A imagem construída é `servicehub-api:etapa8-6c8f0fedebaa`, com aproximadamente
**162,59 MiB**, medida pelo Engine. A auditoria do JAR confirmou bytecode
**major 69/release 25**, perfil prod e V6 presentes, ausência de arquivos `.env`
e ausência de Testcontainers, H2, Mockito, JaCoCo e DevTools no runtime.
As bibliotecas OAuth2 necessárias à autenticação foram preservadas.

O serviço executa com filesystem somente leitura, `/tmp` em tmpfs, todas as
capabilities removidas e `no-new-privileges`. O healthcheck usa o Java do JRE,
com heap pequeno e timeouts; não depende de curl/wget ou instalação posterior.

## 3. Compose e comunicação

O `compose.yaml` agora contém os dois serviços principais:

| Serviço | Configuração |
| --- | --- |
| `api` | Imagem construída pelo Dockerfile, perfil prod padrão, porta configurável em loopback, JWT por ambiente e healthcheck da imagem |
| `postgres` | Major configurável 15/16, usuário/senha/banco por ambiente, volume nomeado e `pg_isready` |

O JDBC da API usa **`postgres:5432`**, o nome do serviço na rede interna.
Não usa localhost para comunicação entre os containers. A API aguarda
`depends_on: condition: service_healthy`; a dependência também declara
`restart: true` para operações explícitas do Compose.

PostgreSQL participa somente da rede `database`, com `internal: true`, e não
publica porta no host por padrão. A API também participa de `edge` e publica
apenas sua porta em `127.0.0.1`. O arquivo opcional `compose.host-db.yaml`
mantém a possibilidade de acesso JDBC local, somente quando informado
explicitamente, também em loopback.

O projeto padrão novo é **servicehub-stack**. O volume inclui projeto e major,
por exemplo `servicehub-stack-postgres-15-data`, evitando adoção implícita do
volume antigo. Isso não converte nem importa dados de uma instalação anterior.

## 4. Variáveis e segurança

`.env.example` documenta nomes e valores não secretos, com DB_PASSWORD e
JWT_SECRET vazios. `scripts/prepare-env.ps1` gera senha de banco de 32 bytes e
chave JWT de 64 bytes, ambas aleatórias em Base64, sem exibi-las. Recusa
sobrescrever o arquivo de destino. Os arquivos reais usados nesta validação
ficaram somente em `.tmp/`, ignorada pelo Git e pelo contexto Docker.

O Compose fornece SPRING_PROFILES_ACTIVE, DB_URL, DB_USERNAME, DB_PASSWORD,
JWT_SECRET, JWT_TTL, JWT_ISSUER e SERVER_PORT. O fallback de senha de
demonstração foi retirado de `application.properties`; a senha agora depende
do ambiente. Não houve mudança no algoritmo JWT, autorização ou serviços.

A imagem real recusou três inicializações inseguras, com encerramento não zero
e a mensagem de validação esperada:

- JWT_SECRET vazio.
- Segredo Base64 que decodifica para somente cinco bytes.
- JWT_TTL de duas horas, acima do máximo de uma hora.

Esses testes utilizaram containers descartáveis sem porta publicada, ligados
somente ao banco sintético já validado. Não alteraram a chave da API principal.
Os demais casos de assinatura, expiração, claims, issuer e configuração
continuaram cobertos pelas suítes automatizadas.

A auditoria não encontrou os valores reais de senhas/chaves nos arquivos
destinados ao Git, na configuração da imagem, no histórico da imagem ou nos
logs de execução. Os verificadores também conferiram ausência de tokens e
senhas dos fixtures nos logs. Evidência: [segurança](evidencias/etapa8-security.json).
Nenhum segredo é reproduzido neste relatório.

O perfil prod mantém Swagger/OpenAPI restritos a ADMIN e introspecção/GraphiQL
fechados. Não é criado administrador pela inicialização normal. O verificador
promove somente IDs de contas sintéticas criadas em seu próprio banco isolado.
A conta de banco do Compose também é usada pelo Flyway nesta instalação
local; privilégios e credenciais de execução devem ser planejados para uma
infraestrutura de produção.

## 5. Flyway, Hibernate e saúde

As instalações novas aplicaram automaticamente **V1–V6**, inicializaram JPA e
validaram o schema com `ddl-auto=validate`. O índice confirmado nos dois bancos
foi:

```sql
CREATE INDEX idx_services_category
ON public.services USING btree (category, id) WHERE is_active;
```

As seis migrations mantêm os hashes iniciais:
[migrations preservadas](evidencias/etapa8-migrations.json).
Não foi criado V7 nem aplicada qualquer migration ao banco original.

O Actuator público retorna apenas a saúde agregada em prod, sem detalhes de
componentes. A verificação inclui o banco. O perfil prod limita a espera por
conexão Hikari a três segundos e a validação a um segundo, permitindo observar
a indisponibilidade sem uma espera longa do pool.

Foram executados, em ambos os Compose:

- Inicialização com banco vazio e ambos os serviços healthy.
- Reinício da API, com dados e histórico Flyway idênticos.
- Reinício explícito do PostgreSQL, com volume e histórico idênticos.
- Parada real do banco: health HTTP **503/DOWN** e API marcada **unhealthy**.
- Retorno do banco: API healthy e REST/GraphQL funcionando novamente.
- Recuperação do pool **sem reiniciar o processo da API** na fase de falha,
  confirmada pelo mesmo `StartedAt` do container.

`unless-stopped` trata encerramento do processo; não reinicia automaticamente
um processo apenas por estar unhealthy. A recuperação observada foi da conexão.
Chamadas durante a indisponibilidade podem falhar; não há retry automático de
mutações adicionado nesta etapa.

## 6. HTTP real: REST e GraphQL

Os verificadores apontaram para as portas publicadas dos containers reais,
com perfil prod, sem subir JAR externo. As 240 verificações finais cobriram:

- Cadastro, login e Bearer; 401 sem token/token inválido; 403 sem permissão.
- Consulta do próprio usuário, recusa de terceiros e PATCH sem mass assignment
  de papel/ativação.
- Criação de serviços por PROVIDER, filtros/paginação e edição pelo proprietário.
- Contratação por CLIENT, estado inicial e preço histórico imutável após editar
  o catálogo; bloqueio de exclusão lógica com pedidos abertos.
- Transições, recusa de avanço indevido e impedimento de reabrir estado final.
- Avaliação após conclusão, nota fracionária inválida e avaliação duplicada.
- **Oito queries e seis mutations GraphQL**, autorização, erro FORBIDDEN/CONFLICT,
  scalar Decimal e correspondência dos registros lidos pelo REST.
- Swagger/OpenAPI em prod, introspecção fechada, exclusão lógica e JWT de conta
  inativa recusado.

Resultados detalhados: [Compose/PostgreSQL 15](evidencias/etapa8-compose-postgres15.json)
e [Compose/PostgreSQL 16](evidencias/etapa8-compose-postgres16.json).
A rodada 16 não repetiu os três testes de configuração JWT da mesma imagem;
foi uma instalação nova e incluiu a confirmação de banco vazio. Isso explica
a diferença de 121 para 119 verificações.

## 7. Persistência e fixtures preservados

O verificador compara fingerprints de todas as colunas das quatro tabelas e
do histórico Flyway antes/depois de cada reinício e recuperação. Os valores
estão nas evidências Compose; não houve reaplicação ou recriação de tabelas.
O histórico Flyway também permaneceu igual após os testes negativos de JWT.

Uma primeira tentativa PG15 foi interrompida após reiniciar a API porque
`API_PORT=0` recebeu outra porta do host. Os dados já criados foram mantidos.
O verificador passou a redescobrir a porta após reinícios. Os fixtures dessa
tentativa, todos com pedidos concluídos, foram desativados logicamente por IDs
exatos, somente no banco de teste, antes da repetição. Nada foi apagado.
A tentativa interrompida não foi contada como rodada aprovada:
[registro da interrupção](evidencias/etapa8-primeira-tentativa.json).

A consulta final, somente nos bancos novos, confirmou:

| Banco sintético | Usuários inativos | Serviços inativos | Solicitações COMPLETED | Avaliações preservadas |
| --- | ---: | ---: | ---: | ---: |
| PG15, incluindo a tentativa anterior | 8 | 4 | 4 | 4 |
| PG16 novo | 4 | 2 | 2 | 2 |

Os preços históricos continuam 150,00/250,00, apesar dos preços de catálogo
terem sido editados para 200,00/275,00. Não há usuário ativo ou dado real nesses
bancos. Evidência: [auditoria de fixtures](evidencias/etapa8-fixtures.json).

Foram criados dumps custom dos dois bancos sintéticos, copiados com `docker cp`
para evitar corrupção de binário pelo pipeline PowerShell, e conferidos com
`pg_restore --list`: quatro tabelas de domínio e histórico Flyway presentes.
Os dumps privados ficaram em `.tmp/`. **Não foi executada restauração nem
backup do banco original.** O procedimento de restauração em volume novo está
documentado em [DOCKER.md](DOCKER.md).
Evidência: [backups de teste](evidencias/etapa8-backups.json).

## 8. Testes automatizados em Java 25

O build e as duas matrizes usaram **Eclipse Temurin 25.0.4.1** e Maven Wrapper
**3.9.16**. Assim, a pendência anterior de executar a suíte em JVM 25 foi
resolvida em containers, sem instalar ou substituir o JDK do Windows.

- [370 testes da construção](evidencias/etapa8-java25-build-tests.json).
- [461 execuções com PG15](evidencias/etapa8-java25-postgres15-final.json),
  [Surefire](evidencias/etapa8-java25-postgres15-standard.json) e
  [Failsafe](evidencias/etapa8-java25-postgres15-postgres.json).
- [461 execuções com PG16](evidencias/etapa8-java25-postgres16-final.json),
  [Surefire](evidencias/etapa8-java25-postgres16-standard.json) e
  [Failsafe](evidencias/etapa8-java25-postgres16-postgres.json).

Os comandos foram `./mvnw -B -ntp clean verify` no build e
`./mvnw -B -ntp -o -Ptestcontainers -Dpostgres.version=15|16 clean verify`
no estágio JDK. Não há testes ignorados. A coleta conferiu as quantidades de
cada suíte da Etapa 7 e a versão Java nos XMLs.

`scripts/verify-tests-java25.ps1` monta o socket Docker somente no runner de
testes, com `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. Os bancos
Testcontainers continuam com nomes/credenciais/portas próprios e dados em
tmpfs, sem usar o datasource dos projetos Compose. As verificações de ambiente
PostgreSQL confirmaram essa conexão efetiva. O socket não é montado na API.

Na rodada executada, o repositório Maven do host foi montado apenas para
leitura e Maven usou modo offline. A distribuição Linux do Wrapper ficou em
tmpfs executável próprio: a distribuição/cache do Windows não foi modificada.
O modo `-NoHostCache`, documentado para cache ausente/parcial, não foi usado
nessas duas rodadas. Relatórios XML/JaCoCo foram preservados em `.tmp/`.

## 9. Recursos mantidos e auditoria

Os **18 containers preexistentes** preservaram ID, nome, imagem e estado; os
**seis volumes originais** continuaram presentes e as **sete redes anteriores**
mantiveram seus IDs/configuração. `servicehub-db` continuou `exited`.
O conteúdo do banco original não foi lido, montado em outro container ou migrado.
Não houve prune, reset, `down -v` ou remoção de recursos originais.

Dois projetos novos permanecem **em execução e saudáveis**, para inspeção:

| Projeto | Containers | Volume | API local |
| --- | --- | --- | --- |
| `servicehub-etapa8-6c8f0fedebaa` | `servicehub-etapa8-6c8f0fedebaa-api-1` e `servicehub-etapa8-6c8f0fedebaa-postgres-1` | `servicehub-etapa8-6c8f0fedebaa-postgres-15-data` | [127.0.0.1:32783](http://127.0.0.1:32783/actuator/health) |
| `servicehub-etapa8-fd96db7fc168` | `servicehub-etapa8-fd96db7fc168-api-1` e `servicehub-etapa8-fd96db7fc168-postgres-1` | `servicehub-etapa8-fd96db7fc168-postgres-16-data` | [127.0.0.1:32786](http://127.0.0.1:32786/actuator/health) |

São quatro containers, dois volumes e quatro redes novos, além dos recursos
anteriores. Containers do runner, Testcontainers e testes negativos de JWT
encerraram/removeram somente seus próprios recursos transitórios. Os volumes
Compose e seus registros foram mantidos. A imagem de runtime e o estágio JDK
de testes ficaram locais; nada foi enviado a um registro remoto.

Evidências: [recursos Docker](evidencias/etapa8-preservacao-docker.json),
[fontes/Git](evidencias/etapa8-preservacao-fonte.json),
[imagem](evidencias/etapa8-image.json) e
[Compose expandido sem segredos](evidencias/etapa8-compose-config.json).

Para parar **somente os projetos desta validação**, preservando volumes, use
os arquivos privados retornados pelo preparador e os nomes exatos acima:

```powershell
docker compose -p servicehub-etapa8-6c8f0fedebaa --env-file .tmp/etapa8-6c8f0fedebaa.env stop
docker compose -p servicehub-etapa8-fd96db7fc168 --env-file .tmp/etapa8-fd96db7fc168.env stop
```

Esses comandos de parada são instruções para o operador; os projetos foram
deixados ativos ao concluir a validação.

## 10. Arquivos e correções

Criados:

- `Dockerfile`, `.dockerignore` e `compose.host-db.yaml`.
- `docker/Healthcheck.java` e `docker/run-tests.sh`.
- `scripts/prepare-env.ps1`, `scripts/verify-compose.ps1` e
  `scripts/verify-tests-java25.ps1`.
- `src/main/resources/application-prod.properties`.
- `docs/DOCKER.md`, este relatório e as evidências `etapa8-*`.

Modificados: `compose.yaml`, `.env.example`, `.gitignore`, `.gitattributes`, `README.md`,
`docs/TESTES.md` e `src/main/resources/application.properties`.
São sete arquivos preexistentes alterados, todos de configuração/documentação.
POM, Java de negócio, testes existentes, relatórios/evidências anteriores e
migrations V1–V6 permaneceram intactos por comparação de hashes.

Problemas encontrados e resolvidos:

- Nome fixo de container/volume e credencial de demonstração no Compose antigo:
  substituídos por namespace separado, volume por projeto/major e ambiente
  obrigatório, preservando o recurso antigo.
- Windows retornava dois candidatos para o executável Docker: o verificador
  passou a selecionar explicitamente o primeiro executável encontrado.
- Porta aleatória mudou após restart: descoberta da porta refeita antes da
  próxima chamada HTTP; os fixtures da tentativa anterior foram preservados.
- Cache do Maven Wrapper do Windows não era uma distribuição Linux executável
  no runner: Wrapper separado em tmpfs com execução permitida, mantendo o
  repositório de dependências do host somente para leitura.
- Espera longa por conexão dificultaria observar a falha do banco: configurados
  timeouts limitados em prod e verificada recuperação real sem alterar services.
- Scripts shell precisam de LF para rodar no Linux após checkout Windows:
  `.gitattributes` passou a preservar LF em `*.sh`.

Nenhuma falha de teste foi ignorada ou convertida em sucesso. Tentativas de
preparação/interrupções não foram somadas aos resultados finais aprovados.

## 11. Executar em outro computador e limites

Com Docker Desktop em modo Linux e PowerShell **7.2+**, na raiz do código:

```powershell
.\scripts\prepare-env.ps1
docker compose --env-file .env config --quiet
docker compose --env-file .env build api
docker compose --env-file .env up -d --no-build --wait
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Não é necessário Java/Maven no host para esse caminho. O preparador não
sobrescreve `.env`. Comandos de logs, login, Swagger/GraphQL, reinício, parada,
backup/restauração e atualização estão em [DOCKER.md](DOCKER.md). O guia também
explica instalação **nova** em 15/16 com projetos/volumes distintos: trocar a
imagem sobre um diretório de dados existente não é um upgrade de PostgreSQL.

A instalação fornecida é local e reproduzível. **Acesso externo de produção
precisa de HTTPS em proxy/infraestrutura apropriada**, gestão de segredos,
privilégios e backup operacional. HTTP publicado diretamente não foi tratado
como configuração segura de produção. Não foram implantados TLS, proxy,
orquestração de produção, frontend ou funcionalidades fora das Etapas 1–8.

O ambiente validado usou PowerShell **7.6.5**, Linux/amd64 e as versões exatas
registradas. Outras arquiteturas e Windows containers não foram exercitados.
Não foi feita restauração de banco real, upgrade 15→16 de instalação existente
ou aplicação de V6 no banco original; esses processos exigem autorização e
validação próprias. Os ZIP/PPTX históricos não foram regenerados.

Não há bloqueio funcional observado no ambiente isolado. A entrega termina
na Etapa 8, sem commit, push ou implantação no ambiente original.

## Referências técnicas

- [Imagem oficial Eclipse Temurin](https://hub.docker.com/_/eclipse-temurin).
- [Compose: ordem de inicialização e service_healthy](https://docs.docker.com/compose/how-tos/startup-order/).
- [Compose: redes e nome de serviço](https://docs.docker.com/compose/how-tos/networking/).
- [Compose: nome do projeto e interpolação](https://docs.docker.com/reference/compose-file/version-and-name/).
- [Testcontainers: testes em container com Docker sibling](https://java.testcontainers.org/supported_docker_environment/continuous_integration/dind_patterns/).
