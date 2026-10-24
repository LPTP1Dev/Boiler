param([Parameter(Mandatory = $true)][string]$ClientRoot)
$ErrorActionPreference = 'Stop'
$boilerRoot = Split-Path -Parent $PSScriptRoot
$clientDestination = (Resolve-Path -LiteralPath $ClientRoot).Path
if (-not (Test-Path -LiteralPath (Join-Path $clientDestination 'src/main/resources/fabric.mod.json'))) {
    throw 'ClientRoot must point to the BoilerClient project.'
}
$metadata = Get-Content -LiteralPath (Join-Path $clientDestination 'src/main/resources/fabric.mod.json') -Raw | ConvertFrom-Json
if ($metadata.id -ne 'boilerclient') { throw 'The target is not BoilerClient.' }
$protocolDestination = Join-Path $clientDestination 'src/main/java/net/somewhatcity/boiler/stream'
New-Item -ItemType Directory -Path $protocolDestination -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $boilerRoot 'api/src/main/java/net/somewhatcity/boiler/stream/StreamProtocol.java') -Destination (Join-Path $protocolDestination 'StreamProtocol.java')
Copy-Item -LiteralPath (Join-Path $boilerRoot 'plugin/src/main/java/net/somewhatcity/boiler/stream/H264Codec.java') -Destination (Join-Path $protocolDestination 'H264Codec.java')
