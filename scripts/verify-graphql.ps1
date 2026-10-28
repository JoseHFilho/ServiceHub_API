#Requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^servicehub-v5-test-[a-f0-9]{32}$')][string]$ContainerName,
      [string]$ReportPath, [ValidateRange(1,99)][int]$EvidenceStage=5)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$docker = (Get-Command docker -ErrorAction Stop).Source
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$details = (& $docker inspect $ContainerName | ConvertFrom-Json)[0]
if ($LASTEXITCODE -ne 0 -or $details.Config.Image -notin @('postgres:15','postgres:16')) { throw 'Container PostgreSQL de teste inválido.' }
$mounts = @($details.Mounts)
if ($mounts.Count -ne 1 -or $mounts[0].Type -ne 'tmpfs' -or $mounts[0].Destination -ne '/var/lib/postgresql/data') { throw 'Exige banco isolado em tmpfs, sem volumes.' }
if ($details.Config.Env -notcontains 'POSTGRES_USER=schema_test' -or $details.Config.Env -notcontains 'POSTGRES_DB=schema_test') { throw 'Exige credenciais e banco reservados aos testes.' }
$binding = @($details.NetworkSettings.Ports.'5432/tcp')
if ($binding.Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1') { throw 'Exige porta de banco publicada apenas em loopback.' }
$password = @($details.Config.Env | Where-Object { $_.StartsWith('POSTGRES_PASSWORD=') })[0].Substring('POSTGRES_PASSWORD='.Length)
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start(); $apiPort = $listener.LocalEndpoint.Port; $listener.Stop()
$baseUrl = "http://127.0.0.1:$apiPort"
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseCookies = $false; $handler.AllowAutoRedirect = $false
$http = [Net.Http.HttpClient]::new($handler); $http.Timeout = [TimeSpan]::FromSeconds(15)
$checks = [Collections.Generic.List[object]]::new()
$api = $null
$reportPath = if ($ReportPath) { [IO.Path]::GetFullPath($ReportPath) } else { Join-Path $root 'docs/evidencias/etapa5-http.json' }

function Test-Http([string]$Name, [string]$Method, [string]$Path, [int]$Expected, [string]$Token = '', $Body = $null) {
    $message = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "$baseUrl$Path")
    try {
        if ($Token) { $message.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$Token) }
        if ($null -ne $Body) { $message.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 8),[Text.Encoding]::UTF8,'application/json') }
        $response = $http.SendAsync($message).GetAwaiter().GetResult()
        try {
            $code = [int]$response.StatusCode
            $checks.Add([pscustomobject]@{name=$Name;expected=$Expected;actual=$code;passed=($code -eq $Expected)})
            if ($code -ne $Expected) { throw "$Name : esperado $Expected, recebido $code." }
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($text) { return ($text | ConvertFrom-Json -Depth 60) }
        } finally { $response.Dispose() }
    } finally { $message.Dispose() }
}
function Test-Value([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{name=$Name;passed=$Passed})
    if (-not $Passed) { throw "$Name : verificação semântica falhou." }
}
function New-Fixture([string]$Name) {
    $email = "$Name-$([Guid]::NewGuid().ToString('N'))@rest-runtime.test"
    $password = [Guid]::NewGuid().ToString('N')
    $user = Test-Http "Cadastro $Name" POST /api/users 201 '' @{fullName="Teste $Name";email=$email;password=$password}
    if ([long]$user.id -le 0) { throw 'Identificador de fixture inválido.' }
    return @{id=[long]$user.id;email=$email;password=$password}
}
function Login-Fixture($Fixture, [string]$Name) {
    $login = Test-Http "Login $Name" POST /api/auth/login 200 '' @{email=$Fixture.email;password=$Fixture.password}
    return [string]$login.accessToken
}
function Test-GraphQl([string]$Name,[string]$Query,[hashtable]$Variables,[string]$Token,[string]$ErrorCode='') {
    $response = Test-Http $Name POST /graphql 200 $Token @{query=$Query;variables=$Variables}
    if ($ErrorCode) {
        $found = @($response.errors | Where-Object { $_.extensions.code -eq $ErrorCode }).Count -gt 0
        Test-Value "$Name : erro GraphQL $ErrorCode" $found
    } else { Test-Value "$Name : sem erros GraphQL" (-not $response.errors) }
    return $response.data
}

try {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $java; $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    foreach ($arg in @('-jar',(Join-Path $root 'target/servicehub-api-0.0.1-SNAPSHOT.jar'),'--server.address=127.0.0.1','--spring.profiles.active=prod')) { $start.ArgumentList.Add($arg) }
    $start.Environment['DB_URL'] = "jdbc:postgresql://127.0.0.1:$($binding[0].HostPort)/schema_test"
    $start.Environment['DB_USERNAME'] = 'schema_test'; $start.Environment['DB_PASSWORD'] = $password
    $start.Environment['SPRING_DATASOURCE_URL'] = $start.Environment['DB_URL']
    $start.Environment['SPRING_DATASOURCE_USERNAME'] = 'schema_test'; $start.Environment['SPRING_DATASOURCE_PASSWORD'] = $password
    $start.Environment['SPRING_FLYWAY_URL'] = $start.Environment['DB_URL']
    $start.Environment['SPRING_FLYWAY_USER'] = 'schema_test'; $start.Environment['SPRING_FLYWAY_PASSWORD'] = $password
    $start.Environment['JWT_SECRET'] = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
    $start.Environment['JWT_TTL'] = '15m'; $start.Environment['JWT_ISSUER'] = 'servicehub-api'
    $start.Environment['SERVER_PORT'] = [string]$apiPort
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $api = [Diagnostics.Process]::new(); $api.StartInfo = $start
    $null = $api.Start()
    $stdout = $api.StandardOutput.ReadToEndAsync(); $stderr = $api.StandardError.ReadToEndAsync()
    $healthy = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($api.HasExited) { throw 'JAR encerrou durante a inicialização.' }
        try {
            $health = Invoke-RestMethod "$baseUrl/actuator/health" -TimeoutSec 2
            if ($health.status -eq 'UP') { $healthy = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $healthy) { throw 'API não ficou saudável.' }
    $provider = New-Fixture 'provider'; $client = New-Fixture 'client'; $stranger = New-Fixture 'stranger'; $admin = New-Fixture 'admin'
    $sql = "UPDATE users SET role='PROVIDER' WHERE id=$($provider.id); UPDATE users SET role='ADMIN' WHERE id=$($admin.id);"
    $null = & $docker exec $ContainerName psql -U schema_test -d schema_test -X -v ON_ERROR_STOP=1 -c $sql
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao preparar papéis somente nos fixtures isolados.' }
    $providerToken = Login-Fixture $provider 'provider'; $clientToken = Login-Fixture $client 'client'
    $strangerToken = Login-Fixture $stranger 'stranger'; $adminToken = Login-Fixture $admin 'admin'
    $unauthenticated = Test-Http 'GraphQL sem JWT' POST /graphql 401 '' @{query='{services{content{id}}}'}
    Test-Value 'Autenticação usa envelope GraphQL' ($unauthenticated.errors[0].extensions.code -eq 'UNAUTHORIZED')
    $create = 'mutation{createService(input:{title:"Limpeza GraphQL",price:"150.00",category:LIMPEZA}){id providerId price active}}'
    $null = Test-GraphQl 'CLIENT não cadastra serviço' $create @{} $clientToken FORBIDDEN
    $created = Test-GraphQl 'PROVIDER cadastra serviço' $create @{} $providerToken
    $serviceId = [string]$created.createService.id
    Test-Value 'Prestador identificado pelo JWT' ($created.createService.providerId -eq [string]$provider.id -and $created.createService.active)
    $rest = Test-Http 'Serviço GraphQL visível em REST' GET "/api/services/$serviceId" 200 $clientToken
    Test-Value 'Preço exato entre os transportes' ($rest.price -eq 150 -and $created.createService.price -eq '150.00')
    $catalog = Test-GraphQl 'Catálogo com filtro, página e prestador em lote' 'query($id:ID!){services(providerId:$id,category:LIMPEZA,page:{size:5}){content{id provider{id fullName avatarUrl}} totalElements}}' @{id=[string]$provider.id} $clientToken
    Test-Value 'Catálogo traz prestador público' ($catalog.services.content[0].provider.id -eq [string]$provider.id)
    $null = Test-GraphQl 'Consulta prestador público' 'query($id:ID!){provider(id:$id){id fullName avatarUrl}}' @{id=[string]$provider.id} $clientToken
    $book = Test-GraphQl 'CLIENT contrata por GraphQL' 'mutation($id:ID!){createRequest(input:{serviceId:$id,notes:"Portaria"}){id clientId totalPrice status}}' @{id=$serviceId} $clientToken
    $requestId = [string]$book.createRequest.id
    Test-Value 'Cliente, estado inicial e snapshot vêm do servidor' ($book.createRequest.clientId -eq [string]$client.id -and $book.createRequest.status -eq 'PENDING' -and $book.createRequest.totalPrice -eq '150.00')
    $null = Test-GraphQl 'Atualiza preço pelo GraphQL' 'mutation($id:ID!){updateService(id:$id,input:{price:"250.00"}){id price}}' @{id=$serviceId} $providerToken
    $snapshot = Test-Http 'Snapshot GraphQL preservado no REST' GET "/api/requests/$requestId" 200 $clientToken
    Test-Value 'Catálogo não muda preço contratado' ($snapshot.totalPrice -eq 150)
    $null = Test-GraphQl 'Pedido aberto bloqueia desativação' 'mutation($id:ID!){deactivateService(id:$id){id}}' @{id=$serviceId} $providerToken CONFLICT
    $null = Test-GraphQl 'Terceiro não lê pedido' 'query($id:ID!){request(id:$id){id notes}}' @{id=$requestId} $strangerToken FORBIDDEN
    $scope = Test-GraphQl 'Filtro não amplia visibilidade' 'query($id:ID!){requests(serviceId:$id){content{id} totalElements}}' @{id=$serviceId} $strangerToken
    Test-Value 'Lista de terceiro permanece vazia' ($scope.requests.totalElements -eq 0)
    $null = Test-GraphQl 'Pedido pendente não recebe avaliação' 'mutation($id:ID!){createReview(input:{requestId:$id,rating:5}){id}}' @{id=$requestId} $clientToken CONFLICT
    $change = 'mutation($id:ID!,$status:RequestStatus!){changeRequestStatus(id:$id,input:{status:$status}){id status totalPrice}}'
    $null = Test-GraphQl 'CLIENT não avança pedido' $change @{id=$requestId;status='ACCEPTED'} $clientToken FORBIDDEN
    $null = Test-GraphQl 'Máquina de estados impede salto' $change @{id=$requestId;status='COMPLETED'} $providerToken CONFLICT
    foreach ($state in @('ACCEPTED','IN_PROGRESS','COMPLETED')) { $null = Test-GraphQl "Avança para $state" $change @{id=$requestId;status=$state} $providerToken }
    $null = Test-GraphQl 'Estado final não reabre' $change @{id=$requestId;status='PENDING'} $adminToken CONFLICT
    $review = Test-GraphQl 'Avalia pedido concluído' 'mutation($id:ID!){createReview(input:{requestId:$id,rating:5,comment:"Ótimo"}){id reviewerId}}' @{id=$requestId} $clientToken
    $reviewId = [string]$review.createReview.id
    Test-Value 'Avaliador é o cliente real' ($review.createReview.reviewerId -eq [string]$client.id)
    $null = Test-GraphQl 'Avaliação duplicada bloqueada' 'mutation($id:ID!){createReview(input:{requestId:$id,rating:4}){id}}' @{id=$requestId} $clientToken CONFLICT
    $null = Test-GraphQl 'Prestador consulta avaliações autorizadas' 'query($id:ID!){reviews(serviceId:$id){content{id rating} totalElements}}' @{id=$serviceId} $providerToken
    $null = Test-GraphQl 'Campo sensível ausente no schema' '{providers{content{id passwordHash}}}' @{} $clientToken BAD_REQUEST
    $null = Test-GraphQl 'Introspecção fechada em prod' '{__schema{queryType{name}}}' @{} $adminToken BAD_REQUEST
    $null = Test-Http 'GraphiQL indisponível em prod' GET /graphiql 403 $adminToken
    $null = Test-Http 'Schema printer indisponível em prod' GET /graphql/schema 403 $adminToken
    $aliases = '{' + ((0..10 | ForEach-Object { 'a' + $_ + ':services{content{id}}' }) -join ' ') + '}'
    $null = Test-GraphQl 'Limite de aliases' $aliases @{} $clientToken QUERY_LIMIT_EXCEEDED
    $page = 'services(page:{size:100}){content{id title description price category providerId providerName active createdAt updatedAt provider{id fullName avatarUrl}}}'
    $null = Test-GraphQl 'Limite de complexidade' "{a:$page b:$page c:$page}" @{} $clientToken QUERY_LIMIT_EXCEEDED
    $null = Test-GraphQl 'Mutations em lote recusadas antes de gravar' 'mutation{a:createService(input:{title:"A",price:"1.00",category:LIMPEZA}){id} b:createService(input:{title:"B",price:"1.00",category:LIMPEZA}){id}}' @{} $providerToken QUERY_LIMIT_EXCEEDED
    $null = Test-Http 'Variáveis acima do limite de corpo' POST /graphql 413 $clientToken @{query='{services{content{id}}}';variables=@{padding=('x'*65537)}}
    $deleted = Test-GraphQl 'Desativação lógica pelo GraphQL' 'mutation($id:ID!){deactivateService(id:$id){id active}}' @{id=$serviceId} $providerToken
    Test-Value 'Resultado da desativação' (-not $deleted.deactivateService.active)
    $null = Test-Http 'Serviço desativado oculto no REST' GET "/api/services/$serviceId" 404 $clientToken
    $history = Test-GraphQl 'Histórico preservado' 'query($request:ID!,$review:ID!){request(id:$request){id status totalPrice} review(id:$review){id rating}}' @{request=$requestId;review=$reviewId} $providerToken
    Test-Value 'Pedido e avaliação preservados' ($history.request.status -eq 'COMPLETED' -and $history.request.totalPrice -eq '150.00' -and $history.review.rating -eq 5)
    $null = Test-Http 'Desativa CLIENT temporário' DELETE "/api/users/$($client.id)" 204 $clientToken
    $inactive = Test-Http 'JWT de CLIENT inativo recusado no GraphQL' POST /graphql 401 $clientToken @{query='{requests{content{id}}}'}
    Test-Value 'Bloqueio de inativo usa envelope GraphQL' ($inactive.errors[0].extensions.code -eq 'UNAUTHORIZED')
    $null = Test-Http 'Desativa PROVIDER temporário' DELETE "/api/users/$($provider.id)" 204 $providerToken
    $null = Test-Http 'Desativa terceiro temporário' DELETE "/api/users/$($stranger.id)" 204 $strangerToken
    $null = Test-Http 'Desativa ADMIN temporário' DELETE "/api/users/$($admin.id)" 204 $adminToken
    [ordered]@{stage=$EvidenceStage;profile='prod';packagedJar=$true;container=$ContainerName;image=$details.Config.Image;
        originalDatabaseAccessed=$false;total=$checks.Count;passed=@($checks | Where-Object passed).Count;failed=@($checks | Where-Object { -not $_.passed }).Count;
        fixturesLogicallyDeactivated=$true;fixtureUsers=@($provider.id,$client.id,$stranger.id,$admin.id);serviceId=$serviceId;requestId=$requestId;reviewId=$reviewId;checks=$checks
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Output "$($checks.Count) verificações HTTP GraphQL/REST aprovadas; fixtures preservados por exclusão lógica."
} finally {
    if ($api) {
        if (-not $api.HasExited) { $api.Kill(); $api.WaitForExit(10000) | Out-Null }
        if ($stdout) { $stdout.GetAwaiter().GetResult() | Set-Content (Join-Path $root '.tmp/etapa5-api-stdout.log') }
        if ($stderr) { $stderr.GetAwaiter().GetResult() | Set-Content (Join-Path $root '.tmp/etapa5-api-stderr.log') }
        $api.Dispose()
    }
    $http.Dispose(); $handler.Dispose()
}
