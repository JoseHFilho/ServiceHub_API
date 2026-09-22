param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ReportPath
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(20)
$results = [System.Collections.Generic.List[object]]::new()
$createdIds = [System.Collections.Generic.List[long]]::new()

function Check-Request([string]$Name, [string]$Method, [string]$Path, [int]$Expected, $Body = $null, [string]$ContentType = 'application/json') {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$BaseUrl$Path")
    if ($null -ne $Body) {
        $payload = if ($Body -is [string]) { $Body } else { ConvertTo-Json -InputObject $Body -Compress }
        $request.Content = [System.Net.Http.StringContent]::new($payload, [Text.Encoding]::UTF8, $ContentType)
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try {
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        if ($status -ne $Expected) { throw "$Name`: esperado HTTP $Expected, recebido $status. $text" }
        $results.Add([pscustomobject]@{test=$Name;method=$Method;path=$Path;status=$status;passed=$true})
        return [pscustomobject]@{Status=$status;Text=$text;ContentType=[string]$response.Content.Headers.ContentType}
    } finally {
        $response.Dispose()
        $request.Dispose()
    }
}

try {
    $health = Check-Request 'Saúde com banco' GET '/actuator/health' 200
    if (($health.Text | ConvertFrom-Json).status -ne 'UP') { throw 'Banco ou API indisponível.' }
    $hello = Check-Request 'Mensagem em JSON' GET '/api/hello' 200
    if ($hello.ContentType -notlike 'application/json*') { throw 'A rota hello deve retornar JSON.' }
    if (($hello.Text | ConvertFrom-Json).status -ne 'OK') { throw 'Mensagem inicial inválida.' }
    $null = Check-Request 'Status da aplicação' GET '/api/status' 200
    $swagger = Check-Request 'Swagger UI com redirecionamento' GET '/swagger-ui.html' 200
    if ($swagger.Text -notmatch 'Swagger UI') { throw 'Swagger UI não carregou.' }
    $openapi = Check-Request 'Documento OpenAPI' GET '/v3/api-docs' 200
    $doc = $openapi.Text | ConvertFrom-Json
    foreach ($route in @('/api/users', '/api/users/{id}', '/api/hello', '/api/status')) {
        if ($null -eq $doc.paths.$route) { throw "Rota ausente no OpenAPI: $route" }
        foreach ($operation in $doc.paths.$route.PSObject.Properties) {
            if ($operation.Name -in @('get','post','put','patch','delete')) {
                if (-not $operation.Value.summary -or -not $operation.Value.description) { throw "Documentação incompleta: $route" }
            }
        }
    }

    $token = [Guid]::NewGuid().ToString('N')
    $email = "validacao.$token@servicehub.test"
    $body = @{fullName='Validação ServiceHub';email=$email;password='senha-de-teste123'}
    $create = Check-Request 'Criar usuário' POST '/api/users' 201 $body
    $user = $create.Text | ConvertFrom-Json
    $id = [long]$user.id
    $createdIds.Add($id)
    if ($create.Text -match 'password|senha-de-teste123') { throw 'A resposta expôs senha ou hash.' }
    $null = Check-Request 'E-mail duplicado' POST '/api/users' 409 $body
    $null = Check-Request 'Consultar usuário persistido' GET "/api/users/$id" 200
    $list = Check-Request 'Listar usuários' GET '/api/users' 200
    if (@($list.Text | ConvertFrom-Json).id -notcontains $id) { throw 'O novo usuário não aparece na listagem.' }
    $body.fullName = 'Nome substituído'
    $body.password = 'nova-senha123'
    $put = Check-Request 'Substituição completa' PUT "/api/users/$id" 200 $body
    if (($put.Text | ConvertFrom-Json).fullName -ne 'Nome substituído') { throw 'PUT não atualizou o nome.' }
    $patch = Check-Request 'Atualização parcial' PATCH "/api/users/$id" 200 @{fullName='Nome parcial'}
    $patched = $patch.Text | ConvertFrom-Json
    if ($patched.fullName -ne 'Nome parcial' -or $patched.email -ne $email) { throw 'PATCH não preservou os campos omitidos.' }
    $null = Check-Request 'PUT incompleto' PUT "/api/users/$id" 400 @{fullName='Incompleto'}
    $null = Check-Request 'PATCH vazio' PATCH "/api/users/$id" 400 '{}'
    $null = Check-Request 'PATCH com nome em branco' PATCH "/api/users/$id" 400 @{fullName=' '}
    $null = Check-Request 'Campos obrigatórios ausentes' POST '/api/users' 400 '{}'
    $null = Check-Request 'E-mail inválido' POST '/api/users' 400 @{fullName='Teste';email='invalido';password='teste123'}
    $null = Check-Request 'JSON malformado' POST '/api/users' 400 '{invalid'
    $null = Check-Request 'Identificador inválido' GET '/api/users/abc' 400
    $null = Check-Request 'Tipo de conteúdo inválido' POST '/api/users' 415 'text' 'text/plain'
    $deleted = Check-Request 'Excluir usuário' DELETE "/api/users/$id" 204
    if ($deleted.Text) { throw 'DELETE 204 retornou corpo.' }
    $createdIds.Remove($id) | Out-Null
    $null = Check-Request 'Consultar ID removido' GET "/api/users/$id" 404
    $null = Check-Request 'Excluir ID inexistente' DELETE "/api/users/$id" 404
    $null = Check-Request 'PUT em ID inexistente' PUT "/api/users/$id" 404 $body
    $null = Check-Request 'PATCH em ID inexistente' PATCH "/api/users/$id" 404 @{fullName='Teste'}

    $report = [pscustomobject]@{timestamp=(Get-Date).ToString('o');baseUrl=$BaseUrl;total=$results.Count;passed=$results.Count;checks=$results}
    if ($ReportPath) {
        $reportParent = Split-Path -Parent ([IO.Path]::GetFullPath($ReportPath))
        if (-not (Test-Path -LiteralPath $reportParent)) { New-Item -ItemType Directory -Path $reportParent -Force | Out-Null }
        $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    }
    $results | Format-Table -AutoSize
    Write-Output "APROVADO: $($results.Count) verificações HTTP. Dados de teste removidos."
} finally {
    foreach ($remainingId in $createdIds) {
        try { $null = Check-Request 'Limpeza de dado temporário' DELETE "/api/users/$remainingId" 204 }
        catch { Write-Warning "Não foi possível remover o usuário temporário $remainingId" }
    }
    $client.Dispose()
}
