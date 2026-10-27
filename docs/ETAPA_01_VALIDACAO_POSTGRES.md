# Etapa 1 — validação em PostgreSQL concluída

Data: 27/10/2026, horário de Brasília (UTC−03:00).

A V5 foi revisada e validada em PostgreSQL **15.19** e **16.14**, incluindo
inicialização Spring Boot/Flyway/Hibernate e execução de toda a suíte em cada
banco. O `clean verify` final com H2 também passou. A Etapa 2 não foi iniciada.

## Diagnóstico do Docker

O log histórico do Docker Desktop registrou, às 09:20 e novamente às 09:31,
uma falha de inicialização do Inference Manager ao tentar remover/abrir o
endpoint transitório:

```text
%LOCALAPPDATA%/Docker/run/dockerInference
The file cannot be accessed by the system.
The filename, directory name, or volume label syntax is incorrect.
```

O registro está no arquivo rotacionado
`%LOCALAPPDATA%/Docker/log/host/com.docker.backend.exe.log.20261027-094613.755`.
A falha observada era o acesso ao socket durante o startup do Docker Desktop.
Os logs consultados não permitem determinar qual processo ou condição do
Windows impedia esse acesso.

Nesta retomada, o Engine **já estava funcionando** antes de qualquer alteração:

| Componente | Resultado observado |
| --- | --- |
| Docker Desktop | 4.86.0, build 236216 |
| Docker Engine | 29.7.2, API 1.55, resposta de `docker version` com exit code 0 |
| Plataforma | Linux amd64 sobre WSL2 |
| Kernel reportado | 6.6.114.1-microsoft-standard-WSL2 |
| Backend atual | Processo iniciado às 09:45:57 |

Nenhum reparo no Docker foi necessário nesta retomada. Não houve reset de
fábrica, remoção de sockets, exclusão de containers/volumes, mudança de
configurações do Docker ou reinicialização do Windows/WSL por esta validação.
Não foi possível atribuir a recuperação a uma ação específica pelos registros
consultados.

## Alterações efetuadas nesta validação

O verificador `scripts/verify-schema-postgres.ps1` executava somente
`SchemaUpgradeTests`. Isso validava o SQL da migration, mas não exercitava o
contexto JPA nem a exclusão lógica contra PostgreSQL.

O script foi ampliado para executar **todos os 44 testes** no banco temporário,
incluindo os testes Spring/MockMvc. Ele configura URL, usuário, driver e dialeto
PostgreSQL durante a execução e fornece a senha temporária por variável de
ambiente. Restaura as variáveis ao terminar, inclusive a senha do datasource.
As configurações normais da aplicação não foram modificadas.

Também foi adicionada a opção `-KeepTestContainer`, habilitada por padrão.
Nesse modo, não usa `--rm` e mantém o container de teste ao terminar. A remoção
automática do container criado pelo script só é habilitada se o operador
informar explicitamente `-KeepTestContainer:$false`.

O código da V5 não precisou de correção. As migrations V1–V4, as entidades e
as regras de negócio não foram modificadas nesta retomada. Nenhum teste foi
removido, desativado ou ignorado.

## Resultados reais

| Execução | Término | Testes | Falhas | Erros | Ignorados | Resultado |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| PostgreSQL 15.19 | 09:50:10 | 44 | 0 | 0 | 0 | BUILD SUCCESS |
| PostgreSQL 16.14 | 09:51:21 | 44 | 0 | 0 | 0 | BUILD SUCCESS |
| Maven `clean verify`, H2 2.4.240 | 09:52:34 | 44 | 0 | 0 | 0 | BUILD SUCCESS |

Foram **132 execuções de testes** ao todo. Ambiente Java: JDK 26.0.2.1,
compilando com `release 25`; Spring Boot 4.1.1 e Maven Wrapper 3.9.16.
Não foi executado um JDK 25 nesta sessão. O build final gerou
`target/servicehub-api-0.0.1-SNAPSHOT.jar`.

Comandos executados, após selecionar o JDK instalado via `JAVA_HOME`:

```powershell
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 15 -KeepTestContainer
.\scripts\verify-schema-postgres.ps1 -PostgresVersion 16 -KeepTestContainer
.\mvnw.cmd -o clean verify
```

Os dois primeiros comandos usam PostgreSQL real em containers separados,
portas locais aleatórias e armazenamento `tmpfs`. Não usam volumes da aplicação
nem o banco `servicehub`. Os schemas de cenários de upgrade recebem nomes únicos.

### Constraints, relacionamentos, índices e preservação

Nos dois PostgreSQL, a suíte verificou:

- Upgrade de V4 com registros para V5, com validação Flyway e repetição sem
  reaplicar migrations já executadas.
- Preservação dos usuários, hashes PBKDF2 armazenados, campos de perfil,
  ativação, serviços, solicitações, avaliações e vínculos existentes nos fixtures.
- Preservação do preço histórico da solicitação, mesmo quando diferente do
  preço atual do serviço.
- Conversão das seis categorias legadas para os nomes em português e aceitação
  das 21 categorias do enum/schema de referência.
- CHECKs de papel, e-mail em minúsculas, preços positivos, categorias, estados
  de solicitação e rating entre 1 e 5.
- Unicidade de e-mail e de avaliação por solicitação; rejeição de FKs inválidas
  e de exclusões físicas que violariam os relacionamentos.
- Índices esperados, índice parcial de categoria para serviços ativos e ausência
  de índices redundantes para e-mail e avaliação por solicitação.
- Exclusão lógica e retenção dos registros após DELETE, ocultação dos inativos,
  HTTP 404 para novas operações sobre eles e bloqueio de desativação com pedidos
  `PENDING`, `ACCEPTED` ou `IN_PROGRESS`.
- Preservação dos papéis/ativação diante de propriedades indevidas no JSON dos
  DTOs existentes.

Além dos testes, consultas somente de leitura ao catálogo `public` dos bancos
temporários confirmaram, em **ambas as versões**:

| Verificação de catálogo | Resultado |
| --- | --- |
| Flyway V5 | `success=true` |
| FKs | 5, todas com `ON DELETE RESTRICT` (`confdeltype='r'`) |
| CHECK constraints | 7 |
| Índices | 14, incluindo os 2 índices da tabela de histórico Flyway |

Cada execução PostgreSQL também confirmou **cinco rollbacks esperados**: colisão
de e-mails normalizados, preço zero e negativo de serviço, snapshot não positivo
e categoria desconhecida. As mensagens de falha de migration nesses cenários
são deliberadas; os testes exigem a rejeição e verificam a preservação dos
registros. A suíte terminou sem erros.

### Inicialização Hibernate e Flyway

Os testes Spring Boot de cada execução usaram o driver/dialeto PostgreSQL e o
servidor real correspondente. Os logs registraram a aplicação de V1–V5, a V5
como versão atual e `Initialized JPA EntityManagerFactory`. O Hibernate
inicializou com `spring.jpa.hibernate.ddl-auto=validate`, sem recriação de schema.

## Evidências salvas

- [Resumo PostgreSQL 15](evidencias/etapa1-postgres15.json)
- [Resumo PostgreSQL 16](evidencias/etapa1-postgres16.json)
- [Resumo Maven final](evidencias/etapa1-maven-final.json)
- [Catálogo real PostgreSQL 15](evidencias/etapa1-catalogo-postgres15.json)
- [Catálogo real PostgreSQL 16](evidencias/etapa1-catalogo-postgres16.json)

Os resumos registram resultado, contagem por suíte, versões e inicialização,
sem senhas ou tokens. Logs completos locais, não versionados:
`.tmp/etapa1-postgres15.log`, `.tmp/etapa1-postgres16.log` e
`.tmp/etapa1-maven-final.log`.

## Preservação do ambiente e pendências

Antes dos testes, havia **8 containers originais** e **6 volumes**. A conferência
final constatou todos os containers originais presentes e nenhuma mudança nos
nomes dos volumes. O container original `servicehub-db` continuou parado, com
seu volume existente. Nenhum teste apontou para esse banco ou para os bancos
das outras aplicações. Não houve comandos de exclusão de containers ou volumes.

Os dois containers novos de teste foram mantidos em execução:

| PostgreSQL | Container exclusivo de validação |
| --- | --- |
| 15 | `servicehub-v5-test-5f8591ba9b9b43c1959957a7700d8cc2` |
| 16 | `servicehub-v5-test-015f7b3a88d64ee99abe35abda6a1221` |

Eles contêm apenas dados sintéticos da suíte em `tmpfs` e não possuem volume
nomeado. Sua exclusão não foi realizada.

**Não resta bloqueio para a validação da Etapa 1.** A aplicação da migration no
banco original não foi realizada e não faz parte desta verificação isolada.
A Etapa 2 permanece sem início, conforme solicitado.

O repositório segue na branch `appmod/java-upgrade-20260830185720`, sem commit,
push ou reset. Nesta retomada foram alterados somente o verificador e a
documentação, e adicionadas as evidências descritas acima.

## Limpeza autorizada posteriormente

Após salvar e conferir as evidências, o usuário autorizou expressamente a
remoção dos dois containers temporários identificados na tabela acima. Ambos
foram parados e removidos por nome exato, sem opção de exclusão de volumes.
Os outros 8 containers continuaram presentes e os 6 volumes foram preservados.
O registro está em [evidência da limpeza](evidencias/etapa1-limpeza-containers.json).
As evidências dos testes e dos catálogos PostgreSQL permanecem salvas.
