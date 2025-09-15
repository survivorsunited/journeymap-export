# run-from-backup.ps1 — one-off export from local data\WaypointData.dat
$ErrorActionPreference = "Stop"

# --- Config ---
$BackupFile = Join-Path $PSScriptRoot "data\WaypointData.dat"
$ExportName = "backup"
$StabilizeMs = 500

# --- Paths ---
$Here    = $PSScriptRoot
$Src     = Join-Path $Here "org\survivorsunited\utils\journeymap\WaypointDataDump.java"
$OutBin  = Join-Path $Here "out"
$OutDir  = Join-Path $Here ("export\" + $ExportName)
$SnapDir = Join-Path $OutDir "snapshots"

# --- Verify backup file exists ---
if (-not (Test-Path -LiteralPath $BackupFile)) {
  Write-Host "[FATAL] Backup file not found at $BackupFile"
  exit 1
}

# --- Compile exporter ---
if (!(Test-Path $OutBin)) { New-Item -ItemType Directory -Path $OutBin | Out-Null }
Write-Host "[INFO] Compiling WaypointDataDump.java ..."
javac -d $OutBin $Src

# --- Ensure output dirs ---
if (!(Test-Path $OutDir))  { New-Item -ItemType Directory -Path $OutDir  | Out-Null }
if (!(Test-Path $SnapDir)) { New-Item -ItemType Directory -Path $SnapDir | Out-Null }

# --- Small delay (if file was just copied) ---
Start-Sleep -Milliseconds $StabilizeMs

# --- Run export ---
Write-Host "[INFO] Exporting from backup:"
Write-Host "  Input : $BackupFile"
Write-Host "  Output: $OutDir"
& java -cp $OutBin org.survivorsunited.utils.journeymap.WaypointDataDump @("$BackupFile", "--out", "$OutDir")

# --- Create timestamped snapshot zip of outputs ---
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ts   = (Get-Date).ToString("yyyyMMdd-HHmmss")
$json = Join-Path $OutDir "waypoints.json"
$csv  = Join-Path $OutDir "waypoints.csv"
$txt  = Join-Path $OutDir "create_waypoints.txt"
$snap = Join-Path $SnapDir "$ts.zip"

$toZip = @()
if (Test-Path $json) { $toZip += $json }
if (Test-Path $csv)  { $toZip += $csv }
if (Test-Path $txt)  { $toZip += $txt }

if ($toZip.Count -gt 0) {
  Compress-Archive -LiteralPath $toZip -DestinationPath $snap -Force
  Write-Host "[INFO] Snapshot -> $snap"
} else {
  Write-Host "[WARN] No export files found to include in snapshot."
}

Write-Host "[DONE] Backup export complete."
