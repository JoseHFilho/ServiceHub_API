#Requires -Version 7.2
param(
    [ValidatePattern('^servicehub-etapa8-[a-f0-9]{12}$')][string]$ProjectName = ('servicehub-etapa8-' + [Guid]::NewGuid().ToString('N').Substring(0,12)),
    [ValidateSet('15','16')][string]$PostgresVersion = '15',
    [string]$EnvFile,
    [switch]$SkipBuild,
    [switch]$Resume,
    [switch]$SkipNegativeJwt
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$dockerExecutable=(Get-Command docker -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$work=Join-Path $root ".tmp/$ProjectName"
$null=New-Item -ItemType Directory -Force -Path $work
if (-not $EnvFile) {
    $EnvFile=Join-Path $work '.env'
    & (Join-Path $PSScriptRoot 'prepare-env.ps1') -Path $EnvFile -PostgresVersion $PostgresVersion -ProjectName $ProjectName -ImageName "servicehub-api:$ProjectName" -ApiPort 0
}
$EnvFile=[IO.Path]::GetFullPath($EnvFile)
$compose=@('compose','--project-name',$ProjectName,'--env-file',$EnvFile,'-f',(Join-Path $root 'compose.yaml'))
$checks=[Collections.Generic.List[object]]::new()
$secrets=[Collections.Generic.List[string]]::new()
$handler=[Net.Http.HttpClientHandler]::new()
$handler.AllowAutoRedirect=$false; $handler.UseCookies=$false
$http=[Net.Http.HttpClient]::new($handler); $http.Timeout=[TimeSpan]::FromSeconds(12)
$dbId='';$apiId='';$baseUrl='';$success=$false;$failure='';$lifecycle=[ordered]@{}
$fixtures=@();$serviceIds=@();$requestIds=@();$reviewIds=@();$negative=@()
$image='';$volume='';$dbUser='';$dbName='';$javaVersion=''

function Docker([string[]]$Arguments,[switch]$AllowFailure,[string]$InputText) {
    $output=if ($PSBoundParameters.ContainsKey('InputText')) { $InputText | & $dockerExecutable @Arguments 2>&1 | Out-String } else { & $dockerExecutable @Arguments 2>&1 | Out-String }
    $script:dockerCode=$LASTEXITCODE
    if ($script:dockerCode -ne 0 -and -not $AllowFailure) {
        $output | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'docker-error.log')
        throw 'Comando Docker falhou; detalhes somente no log privado da validação.'
    }
    return $output.Trim()
}
function Check([string]$Name,[bool]$Passed) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed})
    if (-not $Passed) { throw "$Name : verificação falhou." }
}
function Inspect([string]$Id) { (Docker @('inspect',$Id) | ConvertFrom-Json)[0] }
function Assert-Database {
    $db=Inspect $dbId
    if ($db.Config.Labels.'com.docker.compose.project' -ne $ProjectName -or $db.Config.Image -ne "postgres:$PostgresVersion" -or
        @($db.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Name -eq $volume -and $_.Destination -eq '/var/lib/postgresql/data' }).Count -ne 1) { throw 'Banco fora do projeto de validação autorizado.' }
}
function Sql([string]$Query) {
    Assert-Database
    Docker -Arguments @('exec','-i',$dbId,'psql','-U',$dbUser,'-d',$dbName,'-X','-v','ON_ERROR_STOP=1','-A','-t') -InputText $Query
}
function Snapshot {
    Sql @'
SELECT json_build_object(
 'latestMigration',(SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL),
 'successfulMigrations',(SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL),
 'historyDigest',(SELECT md5(string_agg(row_to_json(h)::text,'' ORDER BY installed_rank)) FROM flyway_schema_history h),
 'indexDefinition',(SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND indexname='idx_services_category'),
 'domainDigest',(SELECT md5(string_agg(x.digest,'' ORDER BY x.name)) FROM (
   SELECT 'users' name,coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'empty') digest FROM users t
   UNION ALL SELECT 'services',coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'empty') FROM services t
   UNION ALL SELECT 'requests',coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'empty') FROM service_requests t
   UNION ALL SELECT 'reviews',coalesce(md5(string_agg(row_to_json(t)::text,'' ORDER BY id)),'empty') FROM reviews t) x),
 'users',(SELECT count(*) FROM users),'services',(SELECT count(*) FROM services),
 'requests',(SELECT count(*) FROM service_requests),'reviews',(SELECT count(*) FROM reviews));
'@ | ConvertFrom-Json
}
function Test-Http([string]$Name,[string]$Method,[string]$Path,[int]$Expected,[string]$Token='', $Body=$null) {
    $message=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),"$baseUrl$Path")
    try {
        if ($Token) { $message.Headers.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token) }
        if ($null -ne $Body) { $message.Content=[Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 20),[Text.Encoding]::UTF8,'application/json') }
        try { $response=$http.SendAsync($message).GetAwaiter().GetResult() }
        catch {
            $checks.Add([pscustomobject]@{name=$Name;expected=$Expected;actual='connection-error';passed=$false})
            throw "$Name : falha de conexão no ambiente de teste."
        }
        try {
            $code=[int]$response.StatusCode
            $checks.Add([pscustomobject]@{name=$Name;expected=$Expected;actual=$code;passed=($code -eq $Expected)})
            if ($code -ne $Expected) { throw "$Name : HTTP esperado $Expected, recebido $code." }
            $bodyText=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($bodyText -and $response.Content.Headers.ContentType.MediaType -match '(^application/json$|\+json$)') { return $bodyText | ConvertFrom-Json -Depth 70 }
        } finally { $response.Dispose() }
    } finally { $message.Dispose() }
}
function Gql([string]$Name,[string]$Document,[hashtable]$Variables,[string]$Token,[string]$ErrorCode='') {
    $response=Test-Http $Name POST /graphql 200 $Token @{query=$Document;variables=$Variables}
    if ($ErrorCode) { Check "$Name : erro $ErrorCode" (@($response.errors | Where-Object {$_.extensions.code -eq $ErrorCode}).Count -gt 0) }
    else { Check "$Name : sem erros GraphQL" (-not $response.errors) }
    return $response.data
}
function Account([string]$Name) {
    $password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $secrets.Add($password)
    $email="$Name-$([Guid]::NewGuid().ToString('N'))@compose-runtime.test"
    $user=Test-Http "Cadastro $Name" POST /api/users 201 '' @{fullName="Teste $Name";email=$email;password=$password}
    Check "ID $Name válido" ([long]$user.id -gt 0)
    return @{id=[long]$user.id;email=$email;password=$password}
}
function Login($Account,[string]$Name) {
    $response=Test-Http "Login $Name" POST /api/auth/login 200 '' @{email=$Account.email;password=$Account.password}
    Check "Login $Name retorna Bearer" ($response.tokenType -eq 'Bearer' -and [string]$response.accessToken -match '^[^.]+\.[^.]+\.[^.]+$')
    $secrets.Add([string]$response.accessToken)
    return [string]$response.accessToken
}
function Wait-Healthy([string]$Id,[int]$Seconds=120) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $state=(Inspect $Id).State
        if ($state.Health.Status -eq 'healthy') { return }
        if (-not $state.Running) { throw 'Container de validação encerrou durante a espera de saúde.' }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Timeout aguardando saúde do container de validação.'
}
function Update-ApiAddress {
    $api=Inspect $apiId
    $internalPort=[string]$config.services.api.environment.SERVER_PORT
    $binding=@($api.NetworkSettings.Ports.PSObject.Properties | Where-Object Name -eq "$internalPort/tcp")[0].Value
    if ($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1') { throw 'Porta da API fora de loopback.' }
    # Docker can allocate a different host port on restart when API_PORT=0.
    $script:baseUrl='http://127.0.0.1:'+$binding[0].HostPort
}

try {
    $existing=Docker @('ps','-a','--filter',"label=com.docker.compose.project=$ProjectName",'--format','{{.ID}}')
    if ($existing -and -not $Resume) { throw 'O projeto de validação já existe; use outro nome ou Resume somente para retomar seu próprio teste.' }
    $null=Docker ($compose+@('config','--quiet'))
    $config=Docker ($compose+@('config','--format','json')) | ConvertFrom-Json
    $image=$config.services.api.image
    $volume=$config.volumes.'servicehub-postgres-data'.name
    $dbUser=$config.services.postgres.environment.POSTGRES_USER
    $dbName=$config.services.postgres.environment.POSTGRES_DB
    if ($volume -ne "$ProjectName-postgres-$PostgresVersion-data" -or $config.services.postgres.image -ne "postgres:$PostgresVersion" -or
        $config.services.postgres.ports -or $dbUser -notmatch '^[a-z_][a-z0-9_]{0,62}$' -or $dbName -notmatch '^[a-z_][a-z0-9_]{0,62}$' -or
        $config.services.api.environment.DB_URL -ne "jdbc:postgresql://postgres:5432/$dbName" -or $config.services.api.environment.SPRING_PROFILES_ACTIVE -ne 'prod') { throw 'Configuração não corresponde ao projeto PostgreSQL isolado em prod.' }
    $secrets.Add([string]$config.services.api.environment.DB_PASSWORD)
    $secrets.Add([string]$config.services.api.environment.SPRING_FLYWAY_PASSWORD)
    $secrets.Add([string]$config.services.postgres.environment.POSTGRES_PASSWORD)
    $secrets.Add([string]$config.services.api.environment.JWT_SECRET)
    $beforeVolumes=@((Docker @('volume','ls','--format','{{.Name}}')) -split '\r?\n' | Where-Object {$_})
    if ($volume -in $beforeVolumes -and -not $Resume) { throw 'O volume de validação já existe; não será reutilizado implicitamente.' }
    Check 'Compose config válido e banco sem porta publicada' $true
    if (-not $SkipBuild) { $null=Docker ($compose+@('build','api')) }
    $null=Docker ($compose+@('up','-d','--no-build','--wait','--wait-timeout','180'))
    $dbId=Docker ($compose+@('ps','-q','postgres'))
    $apiId=Docker ($compose+@('ps','-q','api'))
    Assert-Database
    $api=Inspect $apiId
    Check 'API pertence ao projeto isolado' ($api.Config.Labels.'com.docker.compose.project' -eq $ProjectName)
    $internalPort=[string]$config.services.api.environment.SERVER_PORT
    $binding=@($api.NetworkSettings.Ports.PSObject.Properties | Where-Object Name -eq "$internalPort/tcp")[0].Value
    Check 'API publicada somente em loopback' ($binding.Count -eq 1 -and $binding[0].HostIp -eq '127.0.0.1')
    $baseUrl='http://127.0.0.1:'+$binding[0].HostPort
    $javaVersion=Docker @('exec',$apiId,'java','-version')
    Check 'Runtime Java 25 real' ($javaVersion -match 'version "25\.')
    Check 'Processo da API não-root' ((Docker @('exec',$apiId,'id','-u')) -eq '10001')
    $null=Test-Http 'Saúde inicial' GET /actuator/health 200
    $initial=Snapshot
    Check 'Flyway V1–V9 no banco novo' ($initial.latestMigration -eq 9 -and $initial.successfulMigrations -eq 9)
    Check 'Índice composto parcial da V6' ($initial.indexDefinition -like '*USING btree (category, id) WHERE is_active')
    if (-not $Resume) { Check 'Banco novo sem registros de domínio' ($initial.users -eq 0 -and $initial.services -eq 0 -and $initial.requests -eq 0 -and $initial.reviews -eq 0) }

    $null=Test-Http 'REST sem JWT' GET /api/services 401
    $null=Test-Http 'GraphQL sem JWT' POST /graphql 401 '' @{query='{services{content{id}}}'}
    $null=Test-Http 'Swagger sem JWT em prod' GET /v3/api-docs 401
    $null=Test-Http 'Bearer inválido' GET /api/services 401 'invalid-token'
    $provider=Account provider; $client=Account client; $stranger=Account stranger; $admin=Account admin
    $fixtures=@($provider.id,$client.id,$stranger.id,$admin.id)
    $null=Sql "UPDATE users SET role='PROVIDER' WHERE id=$($provider.id); UPDATE users SET role='ADMIN' WHERE id=$($admin.id);"
    $pt=Login $provider provider; $ct=Login $client client; $st=Login $stranger stranger; $at=Login $admin admin
    $null=Test-Http 'CLIENT não lista usuários' GET /api/users 403 $ct
    $null=Test-Http 'ADMIN lista usuários' GET /api/users 200 $at
    $null=Test-Http 'Usuário consulta a própria conta' GET "/api/users/$($client.id)" 200 $ct
    $null=Test-Http 'Terceiro não consulta perfil privado' GET "/api/users/$($client.id)" 403 $st
    $updated=Test-Http 'Atualiza usuário sem atribuir papel/ativação' PATCH "/api/users/$($client.id)" 200 $ct @{fullName='Cliente atualizado';role='ADMIN';active=$false}
    Check 'Mass assignment não altera papel ou ativação' ($updated.role -eq 'CLIENT' -and $updated.active)
    $null=Test-Http 'CLIENT não cria serviço' POST /api/services 403 $ct @{title='Indevido';price=150;category='LIMPEZA'}
    $service=Test-Http 'REST cria serviço' POST /api/services 201 $pt @{title='Serviço Compose';price=150;category='LIMPEZA';providerId=$stranger.id}
    $sid=[long]$service.id; $serviceIds+=$sid
    Check 'Prestador vem da identidade autenticada' ($service.providerId -eq $provider.id)
    $page=Test-Http 'Lista serviços por categoria e prestador' GET "/api/services?category=LIMPEZA&providerId=$($provider.id)&size=1" 200 $ct
    Check 'Filtro e paginação REST' ($page.content.Count -eq 1 -and $page.content[0].id -eq $sid -and $page.totalElements -eq 1)
    $null=Test-Http 'Terceiro não atualiza serviço' PATCH "/api/services/$sid" 403 $st @{price=1}
    $request=Test-Http 'REST cria solicitação com preço do catálogo' POST /api/requests 201 $ct @{serviceId=$sid;totalPrice=1;status='COMPLETED'}
    $rid=[long]$request.id; $requestIds+=$rid
    Check 'Solicitação inicia PENDING e guarda 150' ($request.status -eq 'PENDING' -and [decimal]$request.totalPrice -eq 150)
    $null=Test-Http 'Solicitação aberta impede desativar serviço' DELETE "/api/services/$sid" 409 $pt
    $null=Test-Http 'Solicitação aberta impede desativar cliente' DELETE "/api/users/$($client.id)" 409 $ct
    $null=Test-Http 'CLIENT não aceita solicitação' PATCH "/api/requests/$rid/status" 403 $ct @{status='ACCEPTED'}
    $null=Test-Http 'Terceiro não lê solicitação' GET "/api/requests/$rid" 403 $st
    $null=Gql 'GraphQL recusa terceiro' 'query($id:ID!){request(id:$id){id}}' @{id="$rid"} $st FORBIDDEN
    $null=Test-Http 'Não avalia antes da conclusão' POST /api/reviews 409 $ct @{requestId=$rid;rating=5}
    $null=Gql 'GraphQL atualiza preço do catálogo' 'mutation($id:ID!){updateService(id:$id,input:{price:"200.00"}){id price}}' @{id="$sid"} $pt
    $history=Test-Http 'Preço histórico permanece 150' GET "/api/requests/$rid" 200 $ct
    Check 'Snapshot preservado após edição do catálogo' ([decimal]$history.totalPrice -eq 150)
    $null=Test-Http 'Proprietário atualiza serviço por REST' PATCH "/api/services/$sid" 200 $pt @{title='Serviço Compose atualizado'}
    $null=Gql 'GraphQL aceita solicitação' 'mutation($id:ID!){changeRequestStatus(id:$id,input:{status:ACCEPTED}){id status}}' @{id="$rid"} $pt
    $null=Test-Http 'REST inicia execução' PATCH "/api/requests/$rid/status" 200 $pt @{status='IN_PROGRESS'}
    $null=Gql 'GraphQL conclui solicitação' 'mutation($id:ID!){changeRequestStatus(id:$id,input:{status:COMPLETED}){id status}}' @{id="$rid"} $pt
    $null=Test-Http 'Estado final não reabre' PATCH "/api/requests/$rid/status" 409 $at @{status='PENDING'}
    $null=Test-Http 'Nota fracionária inválida' POST /api/reviews 400 $ct @{requestId=$rid;rating=1.5}
    $review=Test-Http 'REST cria avaliação após conclusão' POST /api/reviews 201 $ct @{requestId=$rid;rating=5;comment='Validação sintética'}
    $vid=[long]$review.id; $reviewIds+=$vid
    $null=Test-Http 'REST impede avaliação duplicada' POST /api/reviews 409 $ct @{requestId=$rid;rating=4}
    $null=Gql 'GraphQL impede avaliação duplicada' 'mutation($id:ID!){createReview(input:{requestId:$id,rating:5}){id}}' @{id="$rid"} $ct CONFLICT
    $null=Test-Http 'Terceiro não lê avaliação' GET "/api/reviews/$vid" 403 $st

    $s2=Gql 'GraphQL cria serviço' 'mutation{createService(input:{title:"Serviço GraphQL Compose",price:"250.00",category:ELETRICA}){id}}' @{} $pt
    $sid2=[long]$s2.createService.id; $serviceIds+=$sid2
    $r2=Gql 'GraphQL cria solicitação' 'mutation($id:ID!){createRequest(input:{serviceId:$id}){id totalPrice status}}' @{id="$sid2"} $ct
    $rid2=[long]$r2.createRequest.id; $requestIds+=$rid2
    Check 'Scalar Decimal e estado inicial GraphQL' ($r2.createRequest.totalPrice -eq '250.00' -and $r2.createRequest.status -eq 'PENDING')
    $null=Test-Http 'REST edita catálogo GraphQL' PATCH "/api/services/$sid2" 200 $pt @{price=275}
    foreach($status in @('ACCEPTED','IN_PROGRESS','COMPLETED')) { $null=Test-Http "REST avança segunda solicitação para $status" PATCH "/api/requests/$rid2/status" 200 $pt @{status=$status} }
    $v2=Gql 'GraphQL cria avaliação' 'mutation($id:ID!){createReview(input:{requestId:$id,rating:5,comment:"Validação Compose"}){id}}' @{id="$rid2"} $ct
    $vid2=[long]$v2.createReview.id; $reviewIds+=$vid2
    $query='query($s:ID!,$p:ID!,$r:ID!,$v:ID!){services(category:LIMPEZA,page:{size:2}){content{id provider{id fullName}} totalElements} service(id:$s){id price} providers(page:{size:2}){content{id}} provider(id:$p){id fullName} requests(status:COMPLETED,serviceId:$s,page:{size:2}){content{id totalPrice} totalElements} request(id:$r){id status totalPrice} reviews(requestId:$r,serviceId:$s,page:{size:2}){content{id rating} totalElements} review(id:$v){id rating}}'
    $data=Gql 'Oito queries GraphQL com filtros e relações' $query @{s="$sid";p="$($provider.id)";r="$rid";v="$vid"} $ct
    Check 'Resultados REST e GraphQL correspondem' ([long]$data.request.id -eq $rid -and $data.request.totalPrice -eq '150.00' -and $data.request.status -eq 'COMPLETED' -and $data.reviews.totalElements -eq 1 -and $data.review.rating -eq 5)
    $publicJson=$data.provider | ConvertTo-Json
    Check 'Projeção de prestador sem dados sensíveis' ($publicJson -notmatch 'email|password|phone|role')
    $introspection=Test-Http 'Introspecção fechada em prod' POST /graphql 200 $ct @{query='{__schema{queryType{name}}}'}
    Check 'Produção recusa introspecção' ([bool]$introspection.errors)
    $null=Test-Http 'CLIENT não acessa OpenAPI em prod' GET /v3/api-docs 403 $ct
    $null=Test-Http 'ADMIN acessa OpenAPI em prod' GET /v3/api-docs 200 $at
    $null=Test-Http 'ADMIN acessa Swagger UI em prod' GET /swagger-ui/index.html 200 $at

    $beforeRestart=Snapshot
    $null=Docker ($compose+@('restart','api'))
    Wait-Healthy $apiId
    Update-ApiAddress
    $afterApi=Snapshot
    $lifecycle.beforeRestart=$beforeRestart
    $lifecycle.afterApiRestart=$afterApi
    Check 'Reinício da API preserva dados e histórico Flyway' ($beforeRestart.domainDigest -eq $afterApi.domainDigest -and $beforeRestart.historyDigest -eq $afterApi.historyDigest)
    $null=Test-Http 'JWT continua válido após reinício da API' GET "/api/requests/$rid" 200 $ct
    $lifecycle.apiRestartPreserved=$true
    $null=Docker ($compose+@('restart','postgres'))
    Wait-Healthy $dbId; Wait-Healthy $apiId
    Update-ApiAddress
    $afterDb=Snapshot
    $lifecycle.afterDatabaseRestart=$afterDb
    Check 'Reinício do PostgreSQL preserva volume, dados e migrations' ($beforeRestart.domainDigest -eq $afterDb.domainDigest -and $beforeRestart.historyDigest -eq $afterDb.historyDigest)
    $lifecycle.databaseRestartPreserved=$true
    $null=Docker @('stop','--time','15',$dbId)
    $down=Test-Http 'Health detecta falha real do banco' GET /actuator/health 503
    Check 'Health agregado informa DOWN' ($down.status -eq 'DOWN' -and -not $down.components)
    $deadline=[DateTime]::UtcNow.AddSeconds(65)
    while ((Inspect $apiId).State.Health.Status -ne 'unhealthy' -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Seconds 1 }
    Check 'Healthcheck Docker marca API unhealthy sem banco' ((Inspect $apiId).State.Health.Status -eq 'unhealthy')
    $startedBeforeRecovery=(Inspect $apiId).State.StartedAt
    $null=Docker @('start',$dbId)
    Wait-Healthy $dbId; Wait-Healthy $apiId
    Update-ApiAddress
    $lifecycle.databaseOutageDetected=$true
    $lifecycle.recoveredWithoutApiRestart=((Inspect $apiId).State.StartedAt -eq $startedBeforeRecovery)
    Check 'Pool recupera conexão sem reiniciar API' $lifecycle.recoveredWithoutApiRestart
    $recovered=Snapshot
    $lifecycle.afterRecovery=$recovered
    Check 'Falha e recuperação preservam registros e migrations' ($beforeRestart.domainDigest -eq $recovered.domainDigest -and $beforeRestart.historyDigest -eq $recovered.historyDigest)
    $null=Test-Http 'REST funciona após recuperação do banco' GET "/api/requests/$rid2" 200 $ct
    $null=Gql 'GraphQL funciona após recuperação do banco' 'query($id:ID!){review(id:$id){id rating}}' @{id="$vid2"} $pt

    if (-not $SkipNegativeJwt) {
        foreach($case in @(@{name='missing-secret';override='JWT_SECRET=';reason='JWT_SECRET deve conter'},@{name='short-secret';override='JWT_SECRET=c2hvcnQ=';reason='JWT_SECRET deve conter'},@{name='unsafe-ttl';override='JWT_TTL=2h';reason='JWT_TTL deve estar entre'})) {
            $output=Docker -Arguments ($compose+@('run','--rm','--no-deps','-T','--name',"$ProjectName-jwt-$($case.name)",'-e',$case.override,'api')) -AllowFailure
            $code=$script:dockerCode
            $output | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work "$($case.name).log")
            Check "Imagem rejeita JWT $($case.name)" ($code -ne 0 -and $output.Contains($case.reason))
            $negative+=@{case=$case.name;exitCode=$code;rejected=$true}
        }
    }

    $null=Gql 'GraphQL desativa segundo serviço' 'mutation($id:ID!){deactivateService(id:$id){id active}}' @{id="$sid2"} $pt
    $null=Test-Http 'REST desativa primeiro serviço' DELETE "/api/services/$sid" 204 $pt
    $null=Test-Http 'Serviço desativado não aparece no catálogo' GET "/api/services/$sid" 404 $ct
    $null=Test-Http 'Histórico de solicitação preservado' GET "/api/requests/$rid" 200 $ct
    $null=Test-Http 'Histórico de avaliação preservado' GET "/api/reviews/$vid" 200 $ct
    foreach($account in @(@{id=$provider.id;token=$pt;name='PROVIDER'},@{id=$client.id;token=$ct;name='CLIENT'},@{id=$stranger.id;token=$st;name='terceiro'},@{id=$admin.id;token=$at;name='ADMIN'})) {
        $null=Test-Http "Exclusão lógica $($account.name)" DELETE "/api/users/$($account.id)" 204 $account.token
        $null=Test-Http "JWT inativo $($account.name) rejeitado" GET /api/services 401 $account.token
    }
    $final=Snapshot
    $inactive=Sql "SELECT count(*) FROM users WHERE id IN ($($fixtures -join ',')) AND NOT is_active;"
    Check 'Quatro contas sintéticas preservadas e inativas' ([int]$inactive -eq 4)
    Check 'Históricos e avaliações continuam registrados' ($final.requests -ge 2 -and $final.reviews -ge 2)
    $logs=Docker @('logs',$apiId)
    $logs | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'api.log')
    $dbLogs=Docker @('logs',$dbId)
    $dbLogs | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'postgres.log')
    $history=Docker @('history','--no-trunc',$image)
    $imageInfo=(Docker @('image','inspect',$image) | ConvertFrom-Json)[0]
    $runtimeEnv=$imageInfo.Config.Env -join "`n"
    foreach($secret in $secrets) { if($secret.Length -ge 16 -and ($logs.Contains($secret) -or $dbLogs.Contains($secret) -or $history.Contains($secret) -or $runtimeEnv.Contains($secret))) { throw 'Segredo encontrado na auditoria; conteúdo não será exibido.' } }
    Check 'Imagem e logs sem valores de senhas ou JWTs' $true
    Check 'Imagem não define credenciais de execução' (@($imageInfo.Config.Env | Where-Object { $_ -match '^(JWT_SECRET|DB_PASSWORD|POSTGRES_PASSWORD)=' }).Count -eq 0)
    Check 'Logs confirmam Flyway atualizado e Hibernate validate' ($logs -match 'Schema "public" is up to date. No migration necessary.' -and $logs -match 'Initialized JPA EntityManagerFactory')
    Check 'Health final UP' ((Test-Http 'Saúde final' GET /actuator/health 200).status -eq 'UP')
    $success=$true
} catch { $failure=$_.Exception.Message; throw }
finally {
    $http.Dispose();$handler.Dispose()
    [ordered]@{delivery='auditoria-parcial';project=$ProjectName;postgresMajor=$PostgresVersion;image=$image;profile='prod';result=$(if($success){'passed'}else{'failed'});failure=$failure;capturedAt=(Get-Date).ToString('o');baseUrl=$baseUrl;apiContainer=$apiId;databaseContainer=$dbId;volume=$volume;originalDatabaseAccessed=$false;manualResourceDeletion=$false;testContainersAndVolumeRetained=$true;total=$checks.Count;passed=@($checks | Where-Object passed).Count;failed=@($checks | Where-Object {-not $_.passed}).Count;fixtureUsers=$fixtures;serviceIds=$serviceIds;requestIds=$requestIds;reviewIds=$reviewIds;lifecycle=$lifecycle;negativeJwtConfigurations=$negative;finalSnapshot=$final;javaRuntime=$javaVersion;checks=$checks} | ConvertTo-Json -Depth 9 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work "compose-postgres$PostgresVersion.json")
}
Write-Output "$($checks.Count) verificações aprovadas em $baseUrl; projeto $ProjectName preservado para inspeção."
