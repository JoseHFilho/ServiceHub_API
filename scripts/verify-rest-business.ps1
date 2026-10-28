#Requires -Version 7.0
param([Parameter(Mandatory)][ValidatePattern('^servicehub-v5-test-[a-f0-9]{32}$')][string]$ContainerName,
      [string]$ReportPath, [ValidateRange(1,99)][int]$EvidenceStage=4)
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
$reportPath = if ($ReportPath) { [IO.Path]::GetFullPath($ReportPath) } else { Join-Path $root 'docs/evidencias/etapa4-http.json' }

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
    $null = Test-Http 'Catálogo sem JWT' GET /api/services 401
    $payload = @{title='Limpeza HTTP';category='LIMPEZA';price=150;providerId=$stranger.id;active=$false}
    $null = Test-Http 'CLIENT não cria serviço' POST /api/services 403 $clientToken $payload
    $service = Test-Http 'PROVIDER cria serviço' POST /api/services 201 $providerToken $payload
    Test-Value 'Prestador e ativação vêm do servidor' ($service.providerId -eq $provider.id -and $service.active)
    $serviceId = [long]$service.id
    $null = Test-Http 'Catálogo paginado' GET "/api/services?providerId=$($provider.id)&category=LIMPEZA&size=1" 200 $clientToken
    $request = Test-Http 'CLIENT contrata serviço' POST /api/requests 201 $clientToken @{serviceId=$serviceId;clientId=$stranger.id;totalPrice=1;status='COMPLETED'}
    $requestId = [long]$request.id
    Test-Value 'Identidade, preço e estado de contratação protegidos' ($request.clientId -eq $client.id -and $request.totalPrice -eq 150 -and $request.status -eq 'PENDING')
    $null = Test-Http 'Atualiza preço de catálogo' PATCH "/api/services/$serviceId" 200 $providerToken @{price=250}
    $stored = Test-Http 'Consulta preço histórico' GET "/api/requests/$requestId" 200 $clientToken
    Test-Value 'Preço histórico preservado' ($stored.totalPrice -eq 150)
    $null = Test-Http 'Solicitação aberta bloqueia desativação' DELETE "/api/services/$serviceId" 409 $providerToken
    $null = Test-Http 'CLIENT não avança estado' PATCH "/api/requests/$requestId/status" 403 $clientToken @{status='ACCEPTED'}
    $null = Test-Http 'Terceiro não consulta solicitação' GET "/api/requests/$requestId" 403 $strangerToken
    foreach ($state in @('ACCEPTED','IN_PROGRESS','COMPLETED')) {
        $null = Test-Http "Transição para $state" PATCH "/api/requests/$requestId/status" 200 $providerToken @{status=$state}
    }
    $null = Test-Http 'Estado final não reabre' PATCH "/api/requests/$requestId/status" 409 $adminToken @{status='PENDING'}
    $null = Test-Http 'Nota fracionária inválida' POST /api/reviews 400 $clientToken @{requestId=$requestId;rating=1.5}
    $review = Test-Http 'Avalia solicitação concluída' POST /api/reviews 201 $clientToken @{requestId=$requestId;rating=5;comment='Ótimo';reviewerId=$stranger.id}
    $reviewId = [long]$review.id
    Test-Value 'Avaliador vem da solicitação' ($review.reviewerId -eq $client.id)
    $null = Test-Http 'Avaliação duplicada bloqueada' POST /api/reviews 409 $clientToken @{requestId=$requestId;rating=4}
    $null = Test-Http 'Prestador consulta avaliação' GET "/api/reviews/$reviewId" 200 $providerToken
    $null = Test-Http 'Terceiro não consulta avaliação' GET "/api/reviews/$reviewId" 403 $strangerToken
    $null = Test-Http 'Desativação lógica do serviço' DELETE "/api/services/$serviceId" 204 $providerToken
    $null = Test-Http 'Serviço desativado indisponível' GET "/api/services/$serviceId" 404 $clientToken
    $null = Test-Http 'Histórico da solicitação preservado' GET "/api/requests/$requestId" 200 $clientToken
    $null = Test-Http 'Histórico da avaliação preservado' GET "/api/reviews/$reviewId" 200 $clientToken
    $null = Test-Http 'Lista solicitações com filtro' GET "/api/requests?serviceId=$serviceId" 200 $clientToken
    $null = Test-Http 'Lista avaliações com filtro' GET "/api/reviews?requestId=$requestId" 200 $providerToken
    $doc = Test-Http 'OpenAPI autenticado em prod' GET /v3/api-docs 200 $adminToken
    $schemes = @($doc.paths.'/api/reviews'.post.security | ForEach-Object { $_.PSObject.Properties.Name })
    $jsonContent = $doc.paths.'/api/reviews'.post.requestBody.content.'application/json'
    Test-Value 'OpenAPI contém exemplo JSON e Bearer' (($schemes -contains 'bearerAuth') -and
        ($null -ne $jsonContent.example -or $null -ne $jsonContent.examples))
    $null = Test-Http 'Desativa CLIENT após conclusão' DELETE "/api/users/$($client.id)" 204 $clientToken
    $null = Test-Http 'JWT de CLIENT inativo rejeitado' GET /api/requests 401 $clientToken
    $null = Test-Http 'Desativa PROVIDER após conclusão' DELETE "/api/users/$($provider.id)" 204 $providerToken
    $null = Test-Http 'Desativa terceiro temporário' DELETE "/api/users/$($stranger.id)" 204 $strangerToken
    $null = Test-Http 'Desativa ADMIN temporário' DELETE "/api/users/$($admin.id)" 204 $adminToken
    $null = Test-Http 'JWT de ADMIN inativo rejeitado' GET /api/reviews 401 $adminToken
    [ordered]@{stage=$EvidenceStage;profile='prod';packagedJar=$true;container=$ContainerName;image=$details.Config.Image;
        originalDatabaseAccessed=$false;total=$checks.Count;passed=@($checks | Where-Object passed).Count;failed=@($checks | Where-Object { -not $_.passed }).Count;
        fixturesLogicallyDeactivated=$true;serviceId=$serviceId;requestId=$requestId;reviewId=$reviewId;checks=$checks
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding utf8
    Write-Output "$($checks.Count) verificações HTTP aprovadas; fixtures preservados por exclusão lógica."
} finally {
    if ($api) {
        if (-not $api.HasExited) { $api.Kill(); $api.WaitForExit(10000) | Out-Null }
        if ($stdout) { $stdout.GetAwaiter().GetResult() | Set-Content (Join-Path $root '.tmp/etapa4-api-stdout.log') }
        if ($stderr) { $stderr.GetAwaiter().GetResult() | Set-Content (Join-Path $root '.tmp/etapa4-api-stderr.log') }
        $api.Dispose()
    }
    $http.Dispose(); $handler.Dispose()
}
