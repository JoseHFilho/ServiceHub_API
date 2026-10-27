#Requires -Version 7.0
param(
    [ValidateSet('15', '16')]
    [string]$PostgresVersion = '15',
    [switch]$KeepTestContainer = $true
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dockerPath = (Get-Command docker -ErrorAction Stop).Source
$containerName = 'servicehub-v5-test-' + [Guid]::NewGuid().ToString('N')
$previousPassword = $env:POSTGRES_PASSWORD
$previousTestPassword = $env:MIGRATION_TEST_PASSWORD
$previousSpringPassword = $env:SPRING_DATASOURCE_PASSWORD
$containerAttempted = $false

function Invoke-TestDocker([string[]]$DockerArguments, [int]$TimeoutSeconds = 30) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $dockerPath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $DockerArguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        $null = $process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            throw "Docker não respondeu em $TimeoutSeconds segundos. Verifique o Docker Desktop."
        }
        $output = $stdout.GetAwaiter().GetResult().Trim()
        $errorText = $stderr.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) { throw "Falha do Docker: $errorText" }
        return $output
    } finally { $process.Dispose() }
}

try {
    $null = Invoke-TestDocker @('version', '--format', '{{.Server.Version}}')
    $env:POSTGRES_PASSWORD = [Guid]::NewGuid().ToString('N')
    $env:MIGRATION_TEST_PASSWORD = $env:POSTGRES_PASSWORD
    $env:SPRING_DATASOURCE_PASSWORD = $env:POSTGRES_PASSWORD
    $containerAttempted = $true
    $runArguments = @('run', '--detach', '--name', $containerName,
        '--env', 'POSTGRES_PASSWORD', '--env', 'POSTGRES_USER=schema_test', '--env', 'POSTGRES_DB=schema_test',
        '--publish', '127.0.0.1::5432', '--mount', 'type=tmpfs,destination=/var/lib/postgresql/data')
    if (-not $KeepTestContainer) { $runArguments += '--rm' }
    $runArguments += "postgres:$PostgresVersion"
    $null = Invoke-TestDocker $runArguments 60
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $null = Invoke-TestDocker @('exec', $containerName, 'pg_isready', '-U', 'schema_test', '-d', 'schema_test') 5
            $ready = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw 'PostgreSQL de teste não ficou disponível.' }
    $port = [int](Invoke-TestDocker @('inspect', '--format', '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}', $containerName))
    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'mvnw.cmd') `
            "-Dmigration.test.url=jdbc:postgresql://127.0.0.1:$port/schema_test" `
            '-Dmigration.test.username=schema_test' `
            "-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:$port/schema_test" `
            '-Dspring.datasource.username=schema_test' `
            '-Dspring.datasource.driver-class-name=org.postgresql.Driver' `
            '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect' test
        if ($LASTEXITCODE -ne 0) { throw 'A validação da suíte em PostgreSQL falhou.' }
    } finally { Pop-Location }
} finally {
    if ($containerAttempted -and $KeepTestContainer) {
        Write-Output "Container de teste preservado: $containerName"
    } elseif ($containerAttempted) {
        try { $null = Invoke-TestDocker @('rm', '--force', $containerName) 15 }
        catch { Write-Warning "Verifique o container temporário $containerName. $($_.Exception.Message)" }
    }
    $env:POSTGRES_PASSWORD = $previousPassword
    $env:MIGRATION_TEST_PASSWORD = $previousTestPassword
    $env:SPRING_DATASOURCE_PASSWORD = $previousSpringPassword
}
