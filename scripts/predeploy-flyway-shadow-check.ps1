param(
    [string]$ShadowContainerName = "travelcrm-flyway-shadow-$([Guid]::NewGuid().ToString('N').Substring(0, 8))",
    [int]$ShadowPort = 55432,
    [string]$ShadowDatabase = "travelcrm_shadow",
    [string]$ShadowUsername = "postgres",
    [string]$ShadowPassword = "postgres",
    [string]$PostgresImage = "postgres:16-alpine",
    [string]$FlywayVersion = "11.7.2",
    [switch]$CloneTargetSchema,
    [string]$TargetJdbcUrl = $env:DB_URL,
    [string]$TargetUsername = $env:DB_USERNAME,
    [string]$TargetPassword = $env:DB_PASSWORD,
    [switch]$BaselineExistingSchema,
    [string]$BaselineVersion = "1",
    [switch]$UseProposedBaseline,
    [switch]$KeepContainer
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$MigrationDir = Join-Path $RepoRoot "src/main/resources/db/migration"
$ProposedBaseline = Join-Path $RepoRoot "src/main/resources/db/proposed/V1_PROPOSED__baseline_schema.sql"
$ValidatorSource = Join-Path $RepoRoot "scripts/schema/ValidateHibernateSchema.java"
$TargetDir = Join-Path $RepoRoot "target"
$SchemaValidationDir = Join-Path $TargetDir "schema-validation"
$FlywayTargetDir = Join-Path $TargetDir "flyway"
$ClasspathFile = Join-Path $TargetDir "classpath.txt"
$ShadowJdbcUrl = "jdbc:postgresql://localhost:$ShadowPort/$ShadowDatabase"
$ContainerStarted = $false

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $displayArgs = for ($i = 0; $i -lt $Arguments.Count; $i++) {
        $arg = $Arguments[$i]
        if ($i -gt 0 -and $Arguments[$i - 1] -in @("-cp", "-classpath", "--class-path")) {
            "<classpath>"
        } elseif ($arg -like "PGPASSWORD=*") {
            "PGPASSWORD=***"
        } elseif ($arg -like "-Dflyway.password=*") {
            "-Dflyway.password=***"
        } else {
            $arg
        }
    }
    Write-Host "+ $FilePath $($displayArgs -join ' ')"
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE"
    }
}

function ConvertFrom-JdbcPostgresUrl {
    param([Parameter(Mandatory = $true)][string]$JdbcUrl)

    if (-not $JdbcUrl.StartsWith("jdbc:postgresql://")) {
        throw "Only jdbc:postgresql:// URLs are supported by this shadow check."
    }

    $uri = [Uri]$JdbcUrl.Substring(5)
    $hostName = $uri.Host
    if ($hostName -in @("localhost", "127.0.0.1", "::1")) {
        $hostName = "host.docker.internal"
    }

    $databaseName = $uri.AbsolutePath.TrimStart([char[]]"/")
    if ([string]::IsNullOrWhiteSpace($databaseName)) {
        throw "TargetJdbcUrl must include a database name."
    }

    $sslMode = $null
    if ($uri.Query -match "(?:^\?|&)sslmode=([^&]+)") {
        $sslMode = [Uri]::UnescapeDataString($Matches[1])
    }

    [pscustomobject]@{
        Host = $hostName
        Port = $(if ($uri.Port -gt 0) { $uri.Port } else { 5432 })
        Database = $databaseName
        SslMode = $sslMode
    }
}

function New-TargetDockerExecPrefix {
    param([Parameter(Mandatory = $true)]$Source)

    $args = @("exec")
    if (-not [string]::IsNullOrEmpty($TargetPassword)) {
        $args += @("-e", "PGPASSWORD=$TargetPassword")
    }
    if (-not [string]::IsNullOrEmpty($Source.SslMode)) {
        $args += @("-e", "PGSSLMODE=$($Source.SslMode)")
    }
    $args += $ShadowContainerName
    $args
}

function Import-TargetSchema {
    if ([string]::IsNullOrWhiteSpace($TargetJdbcUrl) -or [string]::IsNullOrWhiteSpace($TargetUsername)) {
        throw "CloneTargetSchema requires TargetJdbcUrl and TargetUsername, or DB_URL/DB_USERNAME env vars."
    }

    $source = ConvertFrom-JdbcPostgresUrl $TargetJdbcUrl
    $execPrefix = New-TargetDockerExecPrefix $source
    $schemaDump = "/tmp/target-schema.sql"
    $historyDump = "/tmp/flyway-history.sql"

    Write-Host "Importing schema-only clone from $($source.Host):$($source.Port)/$($source.Database)."
    Invoke-External "docker" ($execPrefix + @(
        "pg_dump",
        "--schema-only",
        "--no-owner",
        "--no-privileges",
        "-h", $source.Host,
        "-p", [string]$source.Port,
        "-U", $TargetUsername,
        "-d", $source.Database,
        "-f", $schemaDump
    ))
    Invoke-External "docker" @(
        "exec", $ShadowContainerName,
        "psql",
        "-v", "ON_ERROR_STOP=1",
        "-U", $ShadowUsername,
        "-d", $ShadowDatabase,
        "-f", $schemaDump
    )

    $historyCheck = & docker @($execPrefix + @(
        "psql",
        "-tAc", "select to_regclass('public.flyway_schema_history') is not null",
        "-h", $source.Host,
        "-p", [string]$source.Port,
        "-U", $TargetUsername,
        "-d", $source.Database
    ))
    if ($LASTEXITCODE -ne 0) {
        throw "Could not check flyway_schema_history on target database."
    }

    $hasHistory = (($historyCheck -join "").Trim() -eq "t")
    if ($hasHistory) {
        Write-Host "Copying flyway_schema_history rows so checksum validation runs against target history."
        Invoke-External "docker" ($execPrefix + @(
            "pg_dump",
            "--data-only",
            "--inserts",
            "--table", "public.flyway_schema_history",
            "-h", $source.Host,
            "-p", [string]$source.Port,
            "-U", $TargetUsername,
            "-d", $source.Database,
            "-f", $historyDump
        ))
        Invoke-External "docker" @(
            "exec", $ShadowContainerName,
            "psql",
            "-v", "ON_ERROR_STOP=1",
            "-U", $ShadowUsername,
            "-d", $ShadowDatabase,
            "-f", $historyDump
        )
    }

    $hasHistory
}

function Invoke-Flyway {
    param(
        [Parameter(Mandatory = $true)][string]$Goal,
        [string[]]$ExtraArgs = @()
    )

    $args = @(
        "-B",
        "org.flywaydb:flyway-maven-plugin:${FlywayVersion}:$Goal",
        "-Dflyway.url=$ShadowJdbcUrl",
        "-Dflyway.user=$ShadowUsername",
        "-Dflyway.password=$ShadowPassword",
        "-Dflyway.locations=filesystem:src/main/resources/db/migration",
        "-Dflyway.validateOnMigrate=true",
        "-Dflyway.outOfOrder=false",
        "-Dflyway.cleanDisabled=true",
        "-Dflyway.validateMigrationNaming=true"
    ) + $ExtraArgs

    Invoke-External $script:Mvnw $args
}

function Wait-ForPostgres {
    for ($i = 0; $i -lt 60; $i++) {
        & docker exec $ShadowContainerName pg_isready -U $ShadowUsername -d $ShadowDatabase *> $null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 1
    }
    throw "Postgres container did not become ready in 60 seconds."
}

if ($UseProposedBaseline -and $CloneTargetSchema) {
    throw "UseProposedBaseline is only for an empty fresh shadow database, not a cloned target schema."
}
if ($BaselineExistingSchema -and -not $CloneTargetSchema) {
    throw "BaselineExistingSchema is only valid with CloneTargetSchema."
}

$isWindowsHost = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows)
$script:Mvnw = Join-Path $RepoRoot ($(if ($isWindowsHost) { "mvnw.cmd" } else { "mvnw" }))

Push-Location $RepoRoot
try {
    New-Item -ItemType Directory -Force -Path $SchemaValidationDir | Out-Null
    New-Item -ItemType Directory -Force -Path $FlywayTargetDir | Out-Null

    Invoke-External "docker" @(
        "run",
        "--rm",
        "--name", $ShadowContainerName,
        "-e", "POSTGRES_DB=$ShadowDatabase",
        "-e", "POSTGRES_USER=$ShadowUsername",
        "-e", "POSTGRES_PASSWORD=$ShadowPassword",
        "-p", "${ShadowPort}:5432",
        "-d", $PostgresImage
    )
    $ContainerStarted = $true
    Wait-ForPostgres

    $clonedFlywayHistory = $false
    if ($CloneTargetSchema) {
        $clonedFlywayHistory = Import-TargetSchema
    }

    Invoke-External $script:Mvnw @("-B", "-DskipTests", "compile")
    Invoke-External $script:Mvnw @("-q", "dependency:build-classpath", "-Dmdep.outputFile=target/classpath.txt")

    $activeMigrations = @()
    if (Test-Path $MigrationDir) {
        $activeMigrations = @(Get-ChildItem -LiteralPath $MigrationDir -File |
            Where-Object { $_.Name -like "V*__*.sql" -or $_.Name -like "R__*.sql" })
    }

    if ($activeMigrations.Count -gt 0) {
        if ($clonedFlywayHistory) {
            Invoke-Flyway "validate"
        }
        if ($BaselineExistingSchema) {
            if ($clonedFlywayHistory) {
                throw "BaselineExistingSchema was requested, but the cloned target already has flyway_schema_history rows."
            }
            Invoke-Flyway "baseline" @(
                "-Dflyway.baselineVersion=$BaselineVersion",
                "-Dflyway.baselineDescription=Existing schema before Flyway"
            )
        }
        Invoke-Flyway "migrate"
        Invoke-Flyway "validate"
    } elseif ($UseProposedBaseline) {
        if (-not (Test-Path $ProposedBaseline)) {
            throw "Proposed baseline not found at $ProposedBaseline"
        }
        Invoke-External "docker" @(
            "cp",
            $ProposedBaseline,
            "${ShadowContainerName}:/tmp/V1_PROPOSED__baseline_schema.sql"
        )
        Invoke-External "docker" @(
            "exec", $ShadowContainerName,
            "psql",
            "-v", "ON_ERROR_STOP=1",
            "-U", $ShadowUsername,
            "-d", $ShadowDatabase,
            "-f", "/tmp/V1_PROPOSED__baseline_schema.sql"
        )
    } elseif (-not $CloneTargetSchema) {
        throw "No active Flyway migrations found. Use -UseProposedBaseline for proposal validation or -CloneTargetSchema for existing-schema validation."
    } else {
        Write-Warning "No active Flyway migrations found; validating the cloned schema as-is."
    }

    $pathSeparator = [System.IO.Path]::PathSeparator
    $dependencyClasspath = (Get-Content $ClasspathFile -Raw).Trim()
    $compileClasspath = "target/classes$pathSeparator$dependencyClasspath"
    $runtimeClasspath = "$SchemaValidationDir$pathSeparator$compileClasspath"

    Invoke-External "javac" @(
        "-proc:none",
        "-encoding", "UTF-8",
        "-cp", $compileClasspath,
        "-d", $SchemaValidationDir,
        $ValidatorSource
    )
    Invoke-External "java" @(
        "-cp", $runtimeClasspath,
        "ValidateHibernateSchema",
        "target/classes",
        $ShadowJdbcUrl,
        $ShadowUsername,
        $ShadowPassword
    )

    $shadowDump = Join-Path $FlywayTargetDir "shadow-schema.sql"
    $dumpOutput = & docker exec $ShadowContainerName pg_dump --schema-only --no-owner --no-privileges `
        -U $ShadowUsername -d $ShadowDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump of the shadow schema failed."
    }
    $dumpOutput | Set-Content -Path $shadowDump -Encoding UTF8

    Write-Host "Shadow Flyway/JPA schema check passed. Shadow schema dump: $shadowDump"
} finally {
    Pop-Location
    if ($ContainerStarted -and -not $KeepContainer) {
        & docker stop $ShadowContainerName *> $null
    } elseif ($ContainerStarted) {
        Write-Host "Keeping shadow container $ShadowContainerName running on localhost:$ShadowPort."
    }
}
