[CmdletBinding()]
param(
    [System.Management.Automation.PSCredential] $Credential,
    [string] $RabbitHost = 'localhost',
    [int] $RabbitPort = 5672,
    [string] $ManagementUrl = 'http://localhost:15672'
)
$ErrorActionPreference = 'Stop'
$names = @('RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD', 'RABBITMQ_HOST', 'RABBITMQ_PORT',
    'RABBITMQ_MANAGEMENT_URL', 'RABBITMQ_DEFINITIONS_FILE', 'NOTIFY_INTEGRATION_TEST',
    'SMTP_HOST', 'SMTP_PORT', 'SMTP_USERNAME', 'SMTP_PASSWORD', 'NOTIFY_TEST_MAILPIT_URL')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$container = 'barriodigital-notify-it-' + [Guid]::NewGuid().ToString('N')
$started = $false
Push-Location $PSScriptRoot
try {
    if (-not $Credential -and (-not $env:RABBITMQ_USERNAME -or -not $env:RABBITMQ_PASSWORD)) {
        $Credential = Get-Credential -Message 'Administrador local de RabbitMQ (para vhost temporal)'
    }
    if ($Credential) {
        $env:RABBITMQ_USERNAME = $Credential.UserName
        $env:RABBITMQ_PASSWORD = $Credential.GetNetworkCredential().Password
    }
    $env:RABBITMQ_HOST = $RabbitHost
    $env:RABBITMQ_PORT = "$RabbitPort"
    $env:RABBITMQ_MANAGEMENT_URL = $ManagementUrl.TrimEnd('/')
    $definitions = Join-Path $PSScriptRoot '../../infra-barriodigital/rabbitmq/definitions.json'
    $env:RABBITMQ_DEFINITIONS_FILE = (Resolve-Path -LiteralPath $definitions).Path
    $env:NOTIFY_INTEGRATION_TEST = 'true'
    $env:SMTP_HOST = '127.0.0.1'
    $env:SMTP_USERNAME = ''
    $env:SMTP_PASSWORD = ''

    docker run --detach --rm --name $container --publish '127.0.0.1::1025' --publish '127.0.0.1::8025' axllent/mailpit:v1.31.4
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo iniciar Mailpit para la prueba.' }
    $started = $true
    $smtp = docker port $container '1025/tcp'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo determinar el puerto SMTP.' }
    $ui = docker port $container '8025/tcp'
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo determinar el puerto de Mailpit.' }
    $env:SMTP_PORT = ($smtp -split ':')[-1].Trim()
    $env:NOTIFY_TEST_MAILPIT_URL = 'http://127.0.0.1:' + ($ui -split ':')[-1].Trim()
    $ready = $false
    for ($i = 0; $i -lt 30; $i++) {
        try {
            Invoke-RestMethod -Uri ($env:NOTIFY_TEST_MAILPIT_URL + '/api/v1/messages') -TimeoutSec 5 | Out-Null
            $ready = $true
            break
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $ready) { throw 'Mailpit no estuvo listo en 30 segundos.' }
    & .\mvnw.cmd '-Dtest=NotifyRabbitIT' test
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la prueba de integracion real de Notify.' }
    Write-Host 'Integracion real completada. El vhost temporal y Mailpit se eliminan; las colas existentes no se consumieron.'
} finally {
    if ($started) { docker stop $container | Out-Null }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
