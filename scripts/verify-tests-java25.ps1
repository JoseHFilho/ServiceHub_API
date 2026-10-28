#Requires -Version 7.2
param(
    [ValidateSet('15','16')][string[]]$PostgresVersions=@('15','16'),
    [string]$ImageName=('servicehub-tests-java25:'+[Guid]::NewGuid().ToString('N').Substring(0,12)),
    [string]$CacheDirectory=(Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository'),
    [switch]$SkipBuild,
    [switch]$NoHostCache,
    [string]$ReportDirectory
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$runId=[Guid]::NewGuid().ToString('N').Substring(0,12)
$work=Join-Path $root ".tmp/java25-tests-$runId"
$null=New-Item -ItemType Directory -Force -Path $work
if(-not $ReportDirectory){$ReportDirectory=Join-Path $work 'reports'}
$null=New-Item -ItemType Directory -Force -Path $ReportDirectory
if($ImageName -notmatch '^[a-z0-9][a-z0-9./:_-]+$'){throw 'Nome de imagem inválido.'}
if(-not $SkipBuild){
    & docker build --target build --tag $ImageName $root > (Join-Path $work 'image-build.log') 2>&1
    if($LASTEXITCODE -ne 0){throw 'Falha ao preparar a imagem JDK 25; consulte o log privado.'}
}
$cached=(-not $NoHostCache) -and (Test-Path -LiteralPath $CacheDirectory)
foreach($version in $PostgresVersions){
    $results=Join-Path $work "postgres$version"
    $null=New-Item -ItemType Directory -Force -Path $results
    $logPath=Join-Path $work "postgres$version.log"
    $arguments=@('run','--rm','--name',"servicehub-e8-tests-$runId-pg$version",
        '--mount','type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
        '--env','TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal',
        '--tmpfs','/root/.m2/wrapper:exec,mode=1777',
        '--mount',"type=bind,source=$results,target=/results",
        '--mount',"type=bind,source=$(Join-Path $root 'docker/run-tests.sh'),target=/run-tests.sh,readonly")
    if($cached){$arguments+=@('--mount',"type=bind,source=$([IO.Path]::GetFullPath($CacheDirectory)),target=/root/.m2/repository,readonly")}
    $arguments+=@('--entrypoint','sh',$ImageName,'/run-tests.sh')
    if($cached){$arguments+='-o'}
    $arguments+=@('-Ptestcontainers',"-Dpostgres.version=$version")
    Write-Output "Regressão Java 25 com PostgreSQL $version"
    & docker @arguments > $logPath 2>&1
    if($LASTEXITCODE -ne 0){throw "Regressão Java 25/PostgreSQL $version falhou; consulte $logPath"}
    $combined=0
    $counts=@{}
    foreach($runner in @('standard','postgres')){
        $directory=Join-Path $results $(if($runner -eq 'standard'){'surefire-reports'}else{'failsafe-reports'})
        $minimum=if($runner -eq 'standard'){370}else{91}
        $baselinePath=Join-Path $root "docs/evidencias/etapa7-postgres16-$runner.json"
        $suites=@(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' | ForEach-Object {
            [xml]$xml=Get-Content -Raw -LiteralPath $_.FullName
            $suite=$xml.testsuite
            [pscustomobject]@{name=[string]$suite.name;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped;java=[string]($suite.properties.property | Where-Object name -eq 'java.version').value}
        })
        $total=[int]($suites | Measure-Object tests -Sum).Sum
        $bad=[int]($suites | Measure-Object failures -Sum).Sum+[int]($suites | Measure-Object errors -Sum).Sum+[int]($suites | Measure-Object skipped -Sum).Sum
        if($total -lt $minimum -or $bad -or @($suites | Where-Object java -notlike '25.*').Count){throw "Contagem/runtime incorreto $runner : $total (mínimo histórico $minimum)"}
        $baseline=Get-Content -Raw -LiteralPath $baselinePath | ConvertFrom-Json
        foreach($old in $baseline.suites){$match=@($suites | Where-Object name -eq $old.name);if($match.Count -ne 1 -or $match[0].tests -lt $old.tests){throw "Teste anterior ausente: $($old.name)"}}
        [ordered]@{delivery='auditoria-parcial';runner=$runner;postgresMajor=$version;result='BUILD SUCCESS';command="./mvnw -B -ntp $(if($cached){'-o '})-Ptestcontainers -Dpostgres.version=$version clean verify (JDK 25 container)";javaRuntime=@($suites.java | Select-Object -Unique);javaRelease=25;tests=$total;failures=0;errors=0;skipped=0;previousSuiteCountsPreserved=$true;originalDatabaseAccessed=$false;suites=$suites} | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $ReportDirectory "java25-postgres$version-$runner.json")
        $combined+=$total
        $counts[$runner]=$total
    }
    [ordered]@{delivery='auditoria-parcial';postgresMajor=$version;result='BUILD SUCCESS';javaRuntime=@($suites.java | Select-Object -Unique);javaRelease=25;tests=$combined;standardTests=$counts.standard;postgresTests=$counts.postgres;failures=0;errors=0;skipped=0;independentOfApplicationCompose=$true;composeDatasourceUsed=$false;capturedAt=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath (Join-Path $ReportDirectory "java25-postgres$version-final.json")
    Write-Output "PostgreSQL $version : $combined execuções aprovadas em Java 25. Relatórios em $results"
}
