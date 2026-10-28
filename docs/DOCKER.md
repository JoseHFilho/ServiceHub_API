# Executar ServiceHub com Docker

O ambiente inclui somente API e PostgreSQL. O Dockerfile compila com Maven
Wrapper/JDK 25, executa a suíte padrão e copia o JAR para JRE 25. O host precisa
de Docker Desktop com containers Linux, Compose e PowerShell 7.2+; Java/Maven no
host só são necessários para a alternativa de desenvolvimento sem container
de API. A primeira construção baixa imagens e dependências.

## Preparar e iniciar

Na raiz do repositório:

```powershell
.\scripts\prepare-env.ps1
docker compose --env-file .env config --quiet
docker compose --env-file .env build api
docker compose --env-file .env up -d --no-build --wait
docker compose --env-file .env ps
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

O preparador gera senha de banco e JWT_SECRET aleatórios, sem exibi-los.
Ele não sobrescreve `.env`. Para preencher manualmente, copie `.env.example`
para um arquivo novo e forneça os valores vazios. Não versione arquivos reais
de ambiente, dumps ou tokens. Evite publicar a saída de `compose config` sem
`--quiet`, porque a configuração expandida inclui variáveis secretas.

| Variável | Uso/padrão |
| --- | --- |
| `COMPOSE_PROJECT_NAME` | `servicehub-stack`; identifica containers, redes e volume |
| `SERVICEHUB_IMAGE` | `servicehub-api:java25`; tag local da imagem |
| `POSTGRES_MAJOR` | 15; 15/16 são as versões validadas, em volumes distintos |
| `DB_NAME` / `DB_USERNAME` | Nome do banco/usuário; `servicehub` no preparador |
| `DB_PASSWORD` | Obrigatória; gerada aleatoriamente pelo preparador |
| `SPRING_PROFILES_ACTIVE` | `prod` por padrão |
| `API_BIND_ADDRESS` / `API_PORT` | `127.0.0.1` / 8080 no host |
| `SERVER_PORT` | 8080 dentro do container |
| `JWT_SECRET` | Obrigatória, 32–128 bytes aleatórios em Base64 |
| `JWT_TTL` / `JWT_ISSUER` | `15m` / `servicehub-api`; TTL permitido entre 1m e 1h |
| `POSTGRES_PORT` | 55433; só usado no override opcional de acesso JDBC pelo host |

A API recebe `DB_URL=jdbc:postgresql://postgres:5432/<DB_NAME>`, calculada pelo
Compose. `postgres` é o nome do serviço na rede interna. Não use localhost
para conectar a API ao outro container. PostgreSQL não publica porta por
padrão. A API participa da rede interna do banco e de uma rede de acesso
separada, publicando somente sua porta em loopback.

O volume padrão é `servicehub-stack-postgres-15-data`. O nome inclui projeto
e major; `-p` prevalece sobre o nome do arquivo de ambiente. O projeto antigo
`servicehub-api` e seu volume `servicehub-api_servicehub-postgres-data` não
são reutilizados automaticamente pela configuração nova.

Os endpoints de saúde são públicos e agregados, sem detalhes de infraestrutura
em `prod`. O healthcheck PostgreSQL usa `pg_isready`; o da API usa uma pequena
classe Java, presente na imagem real, sem depender de curl/wget instalado.
`depends_on: service_healthy` aguarda o banco antes de iniciar a API.

## Login e chamadas HTTP sem exibir o token

Cadastros públicos criam CLIENT. Não existe administrador padrão. O exemplo
abaixo cria uma conta nova sintética/local e guarda as credenciais somente em
variáveis da sessão; adapte o nome/e-mail aos dados autorizados do seu ambiente.

```powershell
$base = 'http://127.0.0.1:8080'
$credential = Get-Credential -Message 'Conta nova: e-mail e senha de 8–72 caracteres, até 72 bytes UTF-8'
$signup = @{fullName='Conta local';email=$credential.UserName;password=$credential.GetNetworkCredential().Password} | ConvertTo-Json
$user = Invoke-RestMethod "$base/api/users" -Method Post -ContentType 'application/json' -Body $signup
$body = @{email=$credential.UserName;password=$credential.GetNetworkCredential().Password} | ConvertTo-Json
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $body
$headers = @{Authorization="Bearer $($login.accessToken)"}
Invoke-RestMethod "$base/api/users/$($user.id)" -Headers $headers
$graphql = @{query='{services(page:{size:5}){content{id title price} totalElements}}'} | ConvertTo-Json
Invoke-RestMethod "$base/graphql" -Method Post -Headers $headers -ContentType 'application/json' -Body $graphql
```

Não imprima `$login`, a senha ou o header Authorization em relatórios.
Swagger/OpenAPI exigem ADMIN em `prod`. Contas privilegiadas devem ser
provisionadas por um operador autorizado, com identificação explícita do
usuário; a API não oferece promoção de papel pelo corpo do cadastro/PATCH.
O verificador usa esse provisionamento somente em seu banco sintético isolado.
GraphiQL e introspecção permanecem fechados em produção.

Abrir a URL do Swagger no navegador sem enviar Authorization resulta em 401
em prod. Consulte OpenAPI com um cliente que envie o Bearer de ADMIN ou com
infraestrutura de acesso autorizada. Para Swagger interativo local público,
use `dev` explicitamente; os endpoints de negócio continuam exigindo JWT.
Não coloque tokens na URL.

Exemplos REST estão no README; os documentos completos GraphQL estão em
[GRAPHQL.md](GRAPHQL.md). As permissões são as mesmas nos dois transportes.

## Logs, reinícios e parada

```powershell
docker compose --env-file .env logs --tail 100 api
docker compose --env-file .env logs --tail 100 postgres
docker compose --env-file .env restart api
docker compose --env-file .env restart postgres
docker compose --env-file .env ps
docker compose --env-file .env stop
# Retomar os mesmos serviços e o mesmo volume:
docker compose --env-file .env up -d --no-build --wait
```

`stop` preserva containers e volume. Não use `down -v` para parar a instalação.
Não use prune/reset como rotina de manutenção. A política `unless-stopped`
reinicia processos que encerram inesperadamente; ela não reinicia um processo
apenas por estar unhealthy. A dependência com `restart: true` pode reiniciar
a API quando o banco é reiniciado explicitamente pelo Compose.

Em queda real do banco, o processo da API permanece ativo e o health passa
a DOWN/503. Hikari pode recuperar as conexões quando o banco volta. O timeout
de conexão em `prod` é 3 segundos; isso não é um mecanismo de fila/retry de
mutações e chamadas durante a indisponibilidade podem falhar. Não repita uma
escrita sem avaliar se ela foi confirmada.

Use porta de host fixa para clientes persistentes. `API_PORT=0`, usado nos
testes para evitar colisões, deixa o Docker escolher a porta e ela pode mudar
após restart. Consulte `docker compose --env-file .env port api 8080` novamente
nessa situação; substitua 8080 se alterar SERVER_PORT.

## Backup e restauração em banco novo

Estes são procedimentos manuais; não foram executados no banco original.
Proteja os dumps, que podem conter dados privados e hashes de senha.
O diretório `backups/` está ignorado pelo Git e pelo contexto Docker.

Backup do projeto selecionado pelo `.env`, sem redirecionar binário pelo
pipeline do PowerShell:

```powershell
$null = New-Item -ItemType Directory -Force backups
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path (Get-Location) "backups/servicehub-$stamp.dump"
docker compose --env-file .env exec -T postgres sh -c 'umask 077; pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/servicehub-backup.dump'
if ($LASTEXITCODE -ne 0) { throw 'Backup falhou' }
$db = docker compose --env-file .env ps -q postgres
docker cp "${db}:/tmp/servicehub-backup.dump" $backup
if ($LASTEXITCODE -ne 0) { throw 'Cópia do backup falhou' }
```

Restaure primeiro em **outro projeto com volume novo**:

```powershell
.\scripts\prepare-env.ps1 -Path .env.restore -ProjectName servicehub-restore -ApiPort 8081 -PostgresVersion 15
$restore = @('compose','--project-name','servicehub-restore','--env-file','.env.restore')
docker @restore config --quiet
docker @restore up -d --wait postgres
$target = docker @restore ps -q postgres
docker cp $backup "${target}:/tmp/servicehub-restore.dump"
docker @restore exec -T postgres sh -c 'pg_restore --exit-on-error --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB" /tmp/servicehub-restore.dump'
if ($LASTEXITCODE -ne 0) { throw 'Restauração falhou; interrompa antes de iniciar a API' }
docker @restore up -d --build --wait
```

Esse destino deve estar vazio. Não foi incluído `--clean` para apagar objetos
existentes. Valide contagens, histórico Flyway, índice V6, health, login e fluxos
antes de mudar os clientes. Conserve o ambiente anterior para rollback.

Para migrar o ambiente original, obtenha autorização operacional, faça backup
com a ferramenta da versão de origem, restaure/valide em uma instalação nova
e planeje a janela de aplicação de V5/V6. V5 pode bloquear tabelas e recusar
dados inválidos; V6 reconstrói índice e bloqueia escritas durante a construção.
Não execute esses passos automaticamente contra `servicehub-db`.

## PostgreSQL 15 e 16

Para uma instalação **nova** com PostgreSQL 16:

```powershell
.\scripts\prepare-env.ps1 -Path .env.pg16 -ProjectName servicehub-pg16 -PostgresVersion 16 -ApiPort 8082
docker compose --project-name servicehub-pg16 --env-file .env.pg16 config --quiet
docker compose --project-name servicehub-pg16 --env-file .env.pg16 up -d --build --wait
```

Para 15, utilize outro arquivo/projeto com `-PostgresVersion 15`.
O volume recebe sufixo `postgres-15-data` ou `postgres-16-data` e os dados
da outra instalação não são importados automaticamente. **Mudar a tag da imagem
não atualiza um banco existente.** Para 15→16, use dump/restore ou um processo
planejado de pg_upgrade com ferramentas compatíveis, backup e validação; não
conecte PostgreSQL 16 ao diretório de dados 15.

Trocar DB_PASSWORD no `.env` também não altera a senha de um cluster já
inicializado. Uma rotação exige procedimento coordenado no banco e na API.
Guarde os valores reais em mecanismo de segredos apropriado para produção.

## Atualizar a API preservando dados

```powershell
# Antes: backup validado e revisão das migrations novas.
docker compose --env-file .env build api
docker compose --env-file .env up -d --no-deps --no-build --wait api
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
```

Mantenha projeto, major do PostgreSQL, volume e variáveis consistentes.
Não altere migrations já aplicadas. Preserve a imagem anterior para rollback;
rollback da imagem não desfaz migrations de banco. A configuração fornecida
é para execução local/reproduzível: acesso externo requer HTTPS em proxy ou
infraestrutura equivalente, controle do Docker host e gestão de segredos.

## Desenvolvimento com JAR no host

Esta alternativa exige JDK 25+ local. O override opcional publica somente o
banco em loopback; `up ... postgres` não inicia a API do Compose:

```powershell
docker compose --env-file .env -f compose.yaml -f compose.host-db.yaml up -d --wait postgres
# Leia apenas variáveis conhecidas, sem executar o conteúdo do .env como código:
Get-Content .env | ForEach-Object {
    if ($_ -match '^(DB_USERNAME|DB_PASSWORD|JWT_SECRET|JWT_TTL|JWT_ISSUER)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')
    }
}
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55433/servicehub'
./mvnw.cmd clean verify
java -jar target/servicehub-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
```

O exemplo considera DB_NAME e POSTGRES_PORT padrão. Ajuste DB_URL se alterá-los.
`dev` torna Swagger público por escolha explícita; prod não foi enfraquecido.
Em Linux/macOS, use `sh ./mvnw` e exporte as variáveis do ambiente.

## Repetir as validações isoladas

```powershell
# Cria projeto/volume novos; mantém dados e containers ao terminar:
.\scripts\verify-compose.ps1 -PostgresVersion 15
.\scripts\verify-compose.ps1 -PostgresVersion 16
# Matriz Java 25/Testcontainers independente do Compose da aplicação:
.\scripts\verify-tests-java25.ps1
```

Os verificadores guardam relatórios resumidos em `docs/evidencias`, sem senhas
ou JWTs, e arquivos privados em `.tmp/`. O verificador Compose só aceita nomes
`servicehub-etapa8-<12 hex>`, banco interno, volume próprio e perfil prod. Os
papéis privilegiados são atribuídos somente aos fixtures identificados desse
banco; ao concluir, contas/serviços são desativados logicamente e o histórico
permanece. `Resume` é apenas para retomar conscientemente um projeto de teste
próprio já existente, sem exigir banco vazio; não apaga dados.

O verificador de testes usa o estágio JDK, monta o socket Docker para criar
os próprios containers Testcontainers e, se disponível, monta o cache Maven
local somente para leitura. No Docker Desktop, utiliza
`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. Sem cache local, baixa as
dependências no container descartável. Se o cache do host estiver incompleto,
use `-NoHostCache` para baixar as dependências no container, sem modificá-lo.
Esses mounts não existem no serviço
da API e a imagem final não contém JDK, Maven ou bibliotecas de teste.

Resultados e recursos realmente mantidos nesta entrega: [ETAPA_08.md](ETAPA_08.md).
