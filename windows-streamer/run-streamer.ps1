Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$project = Join-Path $PSScriptRoot "Audio2Phone.Streamer"
dotnet run --project $project -- $args
