# run.ps1 — compile once, auto-discover servers, watch & dump on change
$ErrorActionPreference = "Stop"

# --- Paths & constants ---
$Here       = $PSScriptRoot
$Src        = Join-Path $Here "org\survivorsunited\utils\journeymap\WaypointDataDump.java"
$OutBin     = Join-Path $Here "out"
$UserProfile= $env:USERPROFILE
$Roaming    = Join-Path $UserProfile "AppData\Roaming"
$MpBase     = Join-Path $Roaming ".minecraft\journeymap\data\mp"
$ExportBase = Join-Path $Here "export"
$PollSeconds= 3
$StabilizeMs= 750

# --- Compile dumper ---
if (!(Test-Path $OutBin)) { New-Item -ItemType Directory -Path $OutBin | Out-Null }
Write-Host "[INFO] Compiling WaypointDataDump.java ..."
javac -d $OutBin $Src

# --- Helper: run the dumper safely ---
function Invoke-Dump {
  param([string]$InputFile, [string]$ExportDir)

  if (-not (Test-Path $InputFile)) {
    Write-Host "[WARN] Missing input: $InputFile"
    return
  }
  if (-not (Test-Path $ExportDir)) { New-Item -ItemType Directory -Path $ExportDir | Out-Null }

  # Allow file write to stabilize (mods often write in bursts)
  Start-Sleep -Milliseconds $StabilizeMs

  Write-Host "[INFO] Dumping -> $ExportDir"
  & java -cp $OutBin org.survivorsunited.utils.journeymap.WaypointDataDump @("$InputFile", "--out", "$ExportDir")
}

# --- Discover servers (any folder with waypoints\WaypointData.dat) ---
function Get-Servers {
  if (-not (Test-Path $MpBase)) { return @() }
  Get-ChildItem -LiteralPath $MpBase -Directory -ErrorAction SilentlyContinue |
    ForEach-Object {
      $serverFolder = $_.Name
      $wpDir = Join-Path $_.FullName "waypoints"
      $wpDat = Join-Path $wpDir "WaypointData.dat"
      if (Test-Path $wpDat) {
        [PSCustomObject]@{
          Name       = $serverFolder
          InputFile  = $wpDat
          ExportDir  = (Join-Path $ExportBase $serverFolder)
        }
      }
    }
}

# Initial discovery
$servers = Get-Servers
if (-not $servers -or $servers.Count -eq 0) {
  Write-Host "[WARN] No JourneyMap MP servers found at: $MpBase"
  Write-Host "[WARN] Waiting for servers to appear..."
}

# Track last state per server
$state = @{} # key: InputFile, value: @{WriteTimeUtc=..., Size=...}

# Initial dump for all discovered servers
foreach ($s in $servers) {
  try {
    $fi = Get-Item -LiteralPath $s.InputFile
    $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
    Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir
  } catch {
    Write-Host "[WARN] Skipping initial dump: $($s.InputFile) — $($_.Exception.Message)"
  }
}

Write-Host "[INFO] Watching:"
Write-Host "  Base MP path : $MpBase"
Write-Host "  Export base  : $ExportBase"
Write-Host "  Poll         : $PollSeconds s"
Write-Host "  Stabilize    : $StabilizeMs ms"
Write-Host ""
Write-Host "[INFO] Entering watch loop (Ctrl+C to stop) ..."

# --- Watch loop: poll all servers; also pick up new servers as they appear ---
while ($true) {
  Start-Sleep -Seconds $PollSeconds

  # Refresh server list to catch newly created worlds/servers
  $servers = Get-Servers

  foreach ($s in $servers) {
    try {
      $fi = Get-Item -LiteralPath $s.InputFile -ErrorAction Stop
    } catch {
      # File might be temporarily inaccessible; try next round
      continue
    }

    if (-not $state.ContainsKey($s.InputFile)) {
      # New server discovered — initialize and dump once
      $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
      Write-Host "[INFO] New server detected: $($s.Name)"
      Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir
      continue
    }

    $prev = $state[$s.InputFile]
    if ($fi.LastWriteTimeUtc -ne $prev.WriteTimeUtc -or $fi.Length -ne $prev.Size) {
      Write-Host ("[INFO] Change detected for {0} at {1:u}" -f $s.Name, (Get-Date))
      Write-Host ("       LastWriteTime: {0} -> {1}" -f $prev.WriteTimeUtc, $fi.LastWriteTimeUtc)
      Write-Host ("       Size         : {0} -> {1}" -f $prev.Size, $fi.Length)

      # Update state then dump
      $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
      try {
        Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir
      } catch {
        Write-Host "[ERROR] Dump failed for $($s.Name): $($_.Exception.Message)"
      }
    }
  }
}
