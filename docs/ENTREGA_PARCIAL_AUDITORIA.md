# Entrega parcial da auditoria — ServiceHub API

Preparação em **27/10/2026**, por solicitação de encerramento do planejamento.
Branch: **`codex/atualizacao-etapas-1-a-4`**. HEAD de partida:
`33797874b5fe7eac75dae23b3e16ff6abc048e53`.

**Classificação: funcional com pendências.** A implementação em andamento era
a **Fase 7 — M03, revogação JWT por versão de credenciais**. Essa integração foi
concluída; nenhuma fase ou funcionalidade nova foi iniciada após a interrupção.
O planejamento de 18 fases está encerrado nesta entrega e só deve ser retomado
mediante nova solicitação. Não houve `git add`, commit, push, reset ou clean.

## 1. Trabalho anterior preservado

As **Etapas 1–8 do projeto** são anteriores ao plano de correção da auditoria e
não devem ser confundidas com suas 18 fases:

| Etapa anterior | Entrega preservada |
|---|---|
| 1 | V5, constraints, categorias, relacionamentos, índices, exclusão lógica e upgrade preservando registros |
| 2 | Segurança, validação de entrada e normalização |
| 3 | Autenticação JWT e autorização com estado atual da conta |
| 4 | REST e regras de contratação, transições, avaliações e histórico |
| 5 | GraphQL com serviços compartilhados, projeções, batching e limites |
| 6 | Grupos de testes, JaCoCo e Testcontainers isolados |
| 7 | Otimização de consultas, índice V6 e medições históricas |
| 8 | Dockerfile Java 25, Compose e integração local com PostgreSQL 15/16 |

Os relatórios `ETAPA_01.md` a `ETAPA_08.md`, `AUDITORIA_FINAL.md` e suas evidências
são históricos e foram preservados. Os números antigos não descrevem a suíte
ampliada desta entrega. A apresentação e o ZIP anterior também não foram
regenerados. A versão entregue corresponde aos fontes atuais do checkout.

## 2. Situação das correções da auditoria

| Fase | Situação real ao encerrar |
|---|---|
| 1 — Análise e preparação | Análise, inventário e cópia de referência realizados; alterações locais anteriores identificadas e preservadas |
| 2 — A01, privilégios PostgreSQL | Implementada e testada em instalações novas isoladas: bootstrap, migrator e runtime distintos; runtime sem administração/DDL/escrita no histórico; validação de startup em prod |
| 3 — A02, abuso de autenticação | Implementada: quotas compartilhadas PostgreSQL por origem/conta/operação, concorrência limitada, HMAC das chaves, 429/Retry-After, falha do armazenamento em 503 e métricas agregadas |
| 4 — A03, HTTPS | **Parcial**: proxy/configuração e 15 verificações TLS locais concluídos; domínio, DNS, certificado público, firewall e renovação no destino não validados |
| 5 — M01, paginação extrema | Implementada e testada por HTTP real em H2 e PostgreSQL: limites de tamanho/offset, ordenação estável e erro 400 para entrada extrema |
| 6 — M02, papéis e histórico | Implementada: catálogo/contratação exigem PROVIDER atual; mudança interna exige ADMIN; alterações são bloqueadas com solicitações abertas; V8 protege SQL de operador; histórico encerrado preservado |
| 7 — M03, revogação JWT | **Concluída nesta finalização**: V9, claim `cv`, comparação com banco, atualização de senha e upgrade de hash integrados, com regressões de concorrência |
| 8 — M04, corpos REST | **Parcial, não concluída**: a Fase 3 já trouxe limites para login/cadastro e escritas POST/PUT/PATCH; cobertura completa de métodos, incluindo corpos GET/DELETE, continua pendente |
| 9 — M05, integridade das migrations Java | Pendente: manifesto/gate para V5/V6 históricos; V8/V9 novas já possuem checksum derivado do SQL. V1–V6 não foram alteradas |
| 10 — M06, timeouts/locks | Pendente: política operacional completa de statement/lock timeout e testes correspondentes |
| 11 — M07, usuários administrativos | Pendente: GET administrativo de usuários permanece sem paginação |
| 12 — M08, dependências | Pendente: atualização/revisão do pgJDBC e análise de dependências/SCA |
| 13 — M09, imagens | Pendente: PostgreSQL ainda usa tags major mutáveis; fixação/atualização controlada completa não implementada |
| 14 — B01, CI/regressão | Pendente: pipeline GitHub e gates de cobertura não implementados |
| 15 — Operação | Pendente: consolidação operacional de backups, restore da versão atual, monitoramento e alertas |
| 16 — Regressão do plano completo | Não concluída como fase do plano; a regressão da **entrega parcial** foi executada e está abaixo |
| 17 — Nova auditoria de segurança | Não executada; revisão de arquivos para publicação não equivale à auditoria completa |
| 18 — Relatório do plano completo | Não concluído; este documento registra somente a entrega parcial solicitada |

Não há implementação de código deixada pela metade na Fase 7 nem reversão de
trabalho anterior. As pendências são de escopo, infraestrutura e operação.
Os limites já funcionais de M04 e a configuração TLS local foram mantidos.

## 3. Revogação JWT finalizada

- `users.credential_version` começa em 0; JWTs novos levam `cv` numérico.
- REST e GraphQL exigem versão igual à atual, além de conta ativa e papel atual.
- Alterar a senha invalida todos os JWTs antigos daquela conta, incluindo o
  usado na alteração; contas diferentes continuam autenticadas. É necessário
  fazer login novamente. Não há refresh token ou logout individual nesta versão.
- Tokens anteriores, sem `cv`, são tratados como versão 0 somente enquanto a
  conta permanecer em 0. Não permanecem válidos após a primeira troca de senha.
- PostgreSQL incrementa a versão inclusive quando o hash é alterado por SQL e
  recusa retrocesso da versão. H2 aplica a regra na camada de serviço.
- Upgrade de PBKDF legado usa atualização condicional por hash/versão. O login
  relê a conta e revalida a senha se houve mudança da versão durante a
  autenticação, evitando emitir token novo para uma senha antiga concorrente.
- Nome/e-mail sem troca de senha não revogam tokens; versão/hash não são
  expostos no DTO nem aceitos por mass assignment.

Foi corrigida uma declaração Java inválida com `var` composto no teste novo de
corrida de autenticação. As asserções foram preservadas; a correção permitiu
compilar o teste. Imports/injeção sem uso da integração antiga do suporte JWT
foram removidos. Não se alteraram testes para ocultar falhas.

## 4. Migrations

| Migration | Origem e resultado |
|---|---|
| V1–V5 | Trabalho anterior preservado integralmente |
| V6 `V6__Order_active_services_by_category.java` | Já existia localmente desde a Etapa 7; ainda precisa entrar no Git; bytes preservados |
| V7 `V7__Create_auth_admission_buckets.sql` | Nova nesta correção; tabela de contadores compartilhados |
| V8 `V8__Guard_user_role_changes.java` | Nova nesta correção; trigger PostgreSQL contra mudança de papel com solicitações abertas |
| V9 `V9__Version_user_credentials.java` | Nova nesta correção; coluna/check e trigger de versão de credenciais |

As novas migrations são aditivas e não excluem registros de domínio. V8 exige
READ COMMITTED para mudanças de papel; isolamentos com snapshot persistente são
recusados para evitar corrida com contratação. Os upgrades sintéticos e a
regressão exercitam Flyway/Hibernate e preservação de dados. Não foi aplicada
nenhuma migration ao banco original. Uma implantação real requer backup e
ensaio em cópia restaurada, incluindo avaliação de locks e volume de dados.

## 5. Resultados executados

Execução real em **Eclipse Temurin Java 25.0.4.1**, Maven **3.9.16**, Spring Boot
**4.1.1** e `javac --release 25`, dentro de containers de teste. Não foi usado o
Java padrão do Windows. Cada execução compilou uma cópia dos fontes atuais e
rodou `clean verify`; não se contou resultado da imagem antiga como teste novo.

| Execução | Resultado | Falhas / erros / ignorados |
|---|---|---|
| `./mvnw -B -ntp -o clean verify` | **403 testes; BUILD SUCCESS; JAR gerado** | **0 / 0 / 0** |
| `./mvnw -B -ntp -o -Ptestcontainers -Dpostgres.version=15 clean verify` | **520 = 403 padrão + 117 PostgreSQL 15.19; BUILD SUCCESS** | **0 / 0 / 0** |
| `./mvnw -B -ntp -o -Ptestcontainers -Dpostgres.version=16 clean verify` | **520 = 403 padrão + 117 PostgreSQL 16.14; BUILD SUCCESS** | **0 / 0 / 0** |

São **1.443 execuções aprovadas** nas três rodadas, com repetição intencional
de contratos entre ambientes. Todos os 370 testes padrão e 91 PostgreSQL
anteriores foram mantidos nas respectivas classes; a suíte cresceu para
403/117. Flyway aplicou/validou V1–V9 e Hibernate inicializou corretamente nos
dois PostgreSQL. A cópia compilada na rodada final PostgreSQL 16 corresponde
aos fontes e ao `pom.xml` entregues.

Evidências públicas sanitizadas:

- [Maven padrão](evidencias/entrega-parcial-maven-standard.json).
- [Maven PostgreSQL 15](evidencias/entrega-parcial-maven-pg15.json).
- [Maven PostgreSQL 16](evidencias/entrega-parcial-maven-pg16.json).
- [TLS local](evidencias/entrega-parcial-tls-local.json), da Fase 4, antes da V9.
- [Preservação e revisão](evidencias/entrega-parcial-preservacao.json).
- [Inventário completo de alterações](evidencias/entrega-parcial-arquivos.csv).

As suítes incluem JWT/claims inválidos, revogação REST/GraphQL, rehash e corrida
de login, autorização, regras de negócio, paginação por socket real,
constraints/relacionamentos/categorias/índices, exclusão lógica, upgrade e
preservação de registros. Os testes PostgreSQL usam bancos aleatórios, tmpfs e
credenciais próprias. Testcontainers gerencia somente os recursos descartáveis
que acabou de criar, sem adotar bancos/volumes da aplicação.

Não foram reexecutados: benchmark de grande volume, teste de capacidade/ataque
prolongado, HTTPS público/renovação ACME, fluxo completo do verificador Compose
após V9, restore operacional da versão V9, SCA e CI no GitHub. Não havia
infraestrutura pública fornecida; os demais itens ficaram fora da finalização
restrita solicitada. Não se declara aprovação desses itens.

## 6. Arquivos criados e modificados

O [CSV](evidencias/entrega-parcial-arquivos.csv) lista **cada arquivo local novo
ou modificado**, seu estado Git e se já existia no checkpoint anterior às
correções. Isso inclui o trabalho ainda não versionado das Etapas 5–8, não
somente as alterações desta auditoria. Nenhum arquivo anterior foi descartado.

Grupos centrais a revisar/incluir:

- Fontes REST/JWT/repositories/services existentes, `graphql/`, DTO de prestador,
  `Pagination`, `RoleAdministrationService`, classes de admissão e segurança do
  banco; migrações V6–V9 e recursos GraphQL.
- Testes JWT, credenciais, admissão, papéis, paginação, consultas e classes
  `Postgres*IT`, mais utilitários `testing/` e fixture sintético do benchmark.
- `Dockerfile`, `.dockerignore`, `compose.yaml`, `compose.host-db.yaml`,
  `compose.tls.yaml`, `docker/postgres/10-provision.sh`, `docker/proxy/`,
  healthcheck e executor Java 25.
- `pom.xml`, perfis e propriedades da aplicação/testes, Maven Wrapper,
  `.env.example`, `.gitattributes`, `.gitignore`, scripts, README e documentos
  das Etapas 5–8, auditoria, produção e desta entrega, com evidências sanitizadas.

Os verificadores Java 25 e Compose foram integrados às migrations/suíte novas:
contagens reais, mínimos históricos com verificação por classe e relatórios
novos em pasta privada única, preservando evidências antigas. O benchmark
histórico passou a fixar explicitamente o alvo V6 na comparação V5→V6; não
foi executado novamente nem houve alteração da migration V6.
Os sete scripts PowerShell passaram no parser. A geração de relatórios do
verificador Java 25 foi executada sobre os XMLs reais PostgreSQL 15 e produziu
403/117, preservando a verificação das classes históricas. Isso não é uma nova
execução de todo o orquestrador Docker/Compose.

`.gitignore` mantém `.env*` reais, `.tmp/`, `target/`, backups e entrega gerada
fora do Git; acrescenta extensões de logs, dumps e chaves privadas. O contexto
Docker usa allowlist. `.env.example` continua vazio nos campos secretos.
Nenhum arquivo necessário de fonte, migration, proxy, teste ou Wrapper foi
encontrado indevidamente ignorado. A varredura combinou padrões de tokens/chaves
com comparação dos valores privados conhecidos, sem imprimir esses valores.
Fixtures de testes e SQL de benchmark são sintéticos; não são dumps reais.
Essa revisão não equivale a uma garantia absoluta ou serviço externo de SCA.

## 7. Docker, banco e preservação

Docker Engine **29.7.2** disponível e respondendo; não houve bloqueio de Docker
nesta finalização. Nenhum reset, prune ou limpeza manual foi executado.
O container original **`servicehub-db` (`71fb5634975e`)** permaneceu parado;
seu volume `servicehub-api_servicehub-postgres-data` não foi montado nem lido
pelos testes. Nenhum banco original foi acessado, iniciado ou modificado.

Os containers/volumes/redes anteriores foram preservados. Runners Java 25 de
correção permanecem parados para inspeção; o projeto TLS isolado
`servicehub-fix-tls-a8c5c4fd2f` foi mantido. Os serviços antigos continuam com
suas imagens anteriores: o checkout preparado não foi implantado sobre eles.
Logs, XMLs/HTMLs, cópias de referência e arquivos privados ficaram em
`.tmp/correcoes-auditoria`, ignorada pelo Git; não publique essa pasta.

A conferência final preservou os **28 containers, 8 volumes e 12 redes** do
inventário anterior às correções, inclusive os estados dos containers. Os
recursos de correção já existentes também permaneceram presentes. Nenhum dos
256 arquivos de referência foi removido: 219 ficaram idênticos e 37 receberam
as alterações identificadas desta entrega/correção. Os seis hashes V1–V6
permaneceram iguais; os 148 arquivos, contando `src` e `pom.xml` em conjunto,
correspondem à cópia compilada na rodada final.

## 8. Problemas conhecidos e limites de produção

Não há falha conhecida nos testes aprovados acima. Permanecem pendentes A03
público, parte de M04 e M05–M09/B01/operação. A versão ainda não deve ser
classificada como produção pública pronta. Não há novo endpoint para mudança
de papel, recuperação de senha, refresh token ou revogação individual.

Arquivos privados antigos não têm necessariamente as três identidades novas.
O provisionador roda apenas no primeiro initdb de volume vazio; não corrige
automaticamente permissões de um volume existente. Um runtime privilegiado é
recusado por segurança. Não reutilize `.env`/projeto/volume anterior sem plano
de migração revisado. A proteção em memória do H2 não é proteção distribuída.

`verify-rest-business.ps1` e `verify-graphql.ps1` são históricos e ainda usam a
mesma conta administrativa em prod para runtime/Flyway; essa configuração é
recusada pelo novo controle. Sua adaptação permanece pendente; não se deve
desabilitar o controle para fazê-los passar. Use a matriz Testcontainers atual.
`verify-schema-postgres.ps1` é histórico e não substitui a matriz atual inteira.

Não foi reconstruída/implantada uma imagem final da V9 nos projetos existentes.
As evidências antigas de Compose e TLS não devem ser apresentadas como novo
teste de implantação da V9. O Dockerfile compila os fontes ao construir uma
imagem nova; esse build requer rede/cache de dependências adequado.

## 9. Retomar e versionar posteriormente

**Pronto para revisão e versionamento no GitHub como entrega parcial**, com
as limitações acima registradas. Estado final: **50 arquivos rastreados
modificados, 166 novos não rastreados, nenhum removido e nenhum staged**.
O inventário completo contém 298 candidatos ao repositório; nenhum arquivo
necessário foi encontrado indevidamente ignorado. `git diff --check` passou.
A revisão encontrou zero correspondências de segredos nos candidatos, incluindo
49 entradas XML da apresentação e comparação com 11 valores privados conhecidos
de oito arquivos de ambiente. Não se publicam valores privados na evidência.

1. Revisar este relatório, o CSV de arquivos, `git status` e `git diff`.
2. Preservar arquivos privados/evidências locais. Não limpar recursos ou
   arquivos para retomar. Trabalhar em cópia/bancos isolados; nunca adotar o
   volume original implicitamente.
3. Para repetir com JDK 25 no PATH: `./mvnw.cmd clean verify`, depois
   `./mvnw.cmd -Ptestcontainers -Dpostgres.version=15 clean verify` e versão 16.
   Alternativa Docker sem JDK no host: `./scripts/verify-tests-java25.ps1`.
4. Após nova autorização para o plano, confirmar infraestrutura/política e
   retomar as pendências identificadas, sem editar V1–V6 nem tratar fases
   restantes como concluídas. Novas mudanças de schema exigem novas migrations.

Comandos **recomendados para execução manual após sua revisão**:

```powershell
git branch --show-current
git switch codex/atualizacao-etapas-1-a-4
git status --short
git diff --check
git diff
git add -- .env.example .gitattributes .gitignore .dockerignore Dockerfile compose.yaml compose.host-db.yaml compose.tls.yaml pom.xml README.md .mvn mvnw mvnw.cmd src scripts docker docs
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "feat: consolida etapas e correcoes parciais da auditoria"
git push -u origin codex/atualizacao-etapas-1-a-4
```

O remoto esperado é `https://github.com/JoseHFilho/ServiceHub_API.git`. A branch
local acompanha `origin/main` atualmente; o `push -u` acima cria/atualiza a
branch de mesmo nome e ajusta seu upstream, sem enviar diretamente para main.
Não use force push. Os comandos não foram executados nesta preparação.
