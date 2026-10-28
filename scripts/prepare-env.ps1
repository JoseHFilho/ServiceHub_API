#Requires -Version 7.2
param(
    [string]$Path = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env'),
    [ValidateSet('15','16')][string]$PostgresVersion = '15',
    [ValidateRange(0,65535)][int]$ApiPort = 8080,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{2,62}$')][string]$ProjectName = 'servicehub-stack',
    [string]$ImageName = 'servicehub-api:java25'
)
$ErrorActionPreference='Stop'
$target=[IO.Path]::GetFullPath($Path)
if (Test-Path -LiteralPath $target) { throw 'O arquivo de ambiente já existe; não será sobrescrito.' }
if ($ImageName -notmatch '^[a-z0-9][a-z0-9./:_-]+$') { throw 'Nome de imagem inválido.' }
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$adminPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$migrationPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
$jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
$contents=@"
COMPOSE_PROJECT_NAME=$ProjectName
SERVICEHUB_IMAGE=$ImageName
POSTGRES_MAJOR=$PostgresVersion
DB_NAME=servicehub
DB_ADMIN_USERNAME=servicehub_admin
DB_ADMIN_PASSWORD=$adminPassword
DB_MIGRATION_USERNAME=servicehub_migrator
DB_MIGRATION_PASSWORD=$migrationPassword
DB_USERNAME=servicehub_app
DB_PASSWORD=$password
SPRING_PROFILES_ACTIVE=prod
API_BIND_ADDRESS=127.0.0.1
API_PORT=$ApiPort
SERVER_PORT=8080
POSTGRES_PORT=55433
JWT_SECRET=$jwt
JWT_TTL=15m
JWT_ISSUER=servicehub-api
"@
[IO.File]::WriteAllText($target,$contents,[Text.UTF8Encoding]::new($false))
Write-Output "Arquivo de ambiente criado em $target. Segredos não foram exibidos."
