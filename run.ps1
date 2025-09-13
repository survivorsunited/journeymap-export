# run.ps1 — compile once, auto-discover servers, watch & dump on change
# Also: make timestamped snapshots; keep last N; roll oldest into archive.zip.
$ErrorActionPreference = "Stop"

# --- Config ---
$PollSeconds = 3
$StabilizeMs = 750
$MaxSnapshots = 10   # <-- change this to your retention target

# --- Paths ---
$Here        = $PSScriptRoot
$Src         = Join-Path $Here "org\survivorsunited\utils\journeymap\WaypointDataDump.java"
$OutBin      = Join-Path $Here "out"
$UserProfile = $env:USERPROFILE
$Roaming     = Join-Path $UserProfile "AppData\Roaming"
$MpBase      = Join-Path $Roaming ".minecraft\journeymap\data\mp"
$ExportBase  = Join-Path $Here "export"

# Ensure out/
if (!(Test-Path $OutBin)) { New-Item -ItemType Directory -Path $OutBin | Out-Null }

# --- Compile dumper ---
Write-Host "[INFO] Compiling WaypointDataDump.java ..."
javac -d $OutBin $Src

# Load ZipArchive types (required for append-to-archive)
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Servers {
  if (-not (Test-Path $MpBase)) { return @() }
  Get-ChildItem -LiteralPath $MpBase -Directory -ErrorAction SilentlyContinue |
    ForEach-Object {
      $serverFolder = $_.Name
      $wpDir = Join-Path $_.FullName "waypoints"
      $wpDat = Join-Path $wpDir "WaypointData.dat"
      if (Test-Path $wpDat) {
        [PSCustomObject]@{
          Name      = $serverFolder
          InputFile = $wpDat
          ExportDir = (Join-Path $ExportBase $serverFolder)
          SnapDir   = (Join-Path (Join-Path $ExportBase $serverFolder) "snapshots")
        }
      }
    }
}

function Invoke-Dump {
  param([string]$InputFile, [string]$ExportDir, [string]$SnapDir)

  if (-not (Test-Path $InputFile)) {
    Write-Host "[WARN] Missing input: $InputFile"
    return
  }
  if (-not (Test-Path $ExportDir)) { New-Item -ItemType Directory -Path $ExportDir | Out-Null }
  if (-not (Test-Path $SnapDir))   { New-Item -ItemType Directory -Path $SnapDir   | Out-Null }

  # Let the file finish writing
  Start-Sleep -Milliseconds $StabilizeMs

  Write-Host "[INFO] Dumping -> $ExportDir"
  & java -cp $OutBin org.survivorsunited.utils.journeymap.WaypointDataDump @("$InputFile", "--out", "$ExportDir")

  # Build a timestamped snapshot zip of the three outputs
  $ts = (Get-Date).ToString("yyyyMMdd-HHmmss")
  $json = Join-Path $ExportDir "waypoints.json"
  $csv  = Join-Path $ExportDir "waypoints.csv"
  $txt  = Join-Path $ExportDir "create_waypoints.txt"
  $snapZip = Join-Path $SnapDir "$ts.zip"

  $pathsToZip = @()
  if (Test-Path $json) { $pathsToZip += $json }
  if (Test-Path $csv)  { $pathsToZip += $csv }
  if (Test-Path $txt)  { $pathsToZip += $txt }

  if ($pathsToZip.Count -gt 0) {
    # Create snapshot zip containing current outputs
    Compress-Archive -LiteralPath $pathsToZip -DestinationPath $snapZip -Force
    Write-Host "[INFO] Snapshot -> $snapZip"

    # Enforce retention (keep $MaxSnapshots latest zips; move oldest into archive.zip)
    Enforce-Retention -SnapDir $SnapDir -Max $MaxSnapshots
  } else {
    Write-Host "[WARN] No export files to snapshot in $ExportDir"
  }
}

function Enforce-Retention {
  param([string]$SnapDir, [int]$Max)

  $archivePath = Join-Path $SnapDir "archive.zip"
  $zips = Get-ChildItem -LiteralPath $SnapDir -Filter *.zip -File -ErrorAction SilentlyContinue |
          Where-Object { $_.Name -ne "archive.zip" } |
          Sort-Object LastWriteTimeUtc

  while ($zips.Count -gt $Max) {
    $oldest = $zips[0]
    try {
      # Append oldest zip into archive.zip (as a nested file), then delete the original
      $fs = [System.IO.File]::Open($archivePath, [System.IO.FileMode]::OpenOrCreate, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
      $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Update, $false)

      $entryName = $oldest.Name
      # If same name exists, add with timestamped subfolder to avoid collisions
      $entryPath = "snapshots/$($entryName)"
      if ($zip.Entries.Name -contains $entryName) {
        $entryPath = "snapshots/dup-$((Get-Date).ToString('yyyyMMdd-HHmmss'))-$entryName"
      }
      $entry = $zip.CreateEntry($entryPath, [System.IO.Compression.CompressionLevel]::Optimal)

      $inStream  = [System.IO.File]::OpenRead($oldest.FullName)
      $outStream = $entry.Open()
      $inStream.CopyTo($outStream)
      $outStream.Dispose()
      $inStream.Dispose()

      $zip.Dispose()
      $fs.Dispose()

      Remove-Item -LiteralPath $oldest.FullName -Force
      Write-Host "[INFO] Rolled -> $($oldest.Name) into archive.zip"
    } catch {
      Write-Host "[ERROR] Retention/archival failed for $($oldest.FullName): $($_.Exception.Message)"
      # break to avoid loop storm if archive is locked
      break
    }

    # refresh list
    $zips = Get-ChildItem -LiteralPath $SnapDir -Filter *.zip -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -ne "archive.zip" } |
            Sort-Object LastWriteTimeUtc
  }
}

# Discover servers
$servers = Get-Servers
if (-not $servers -or $servers.Count -eq 0) {
  Write-Host "[WARN] No JourneyMap MP servers found at: $MpBase"
  Write-Host "[WARN] Waiting for servers to appear..."
}

# Track last state per server
$state = @{} # key: InputFile -> @{WriteTimeUtc; Size}

# Initial dump for all discovered servers
foreach ($s in $servers) {
  try {
    $fi = Get-Item -LiteralPath $s.InputFile
    $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
    Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir -SnapDir $s.SnapDir
  } catch {
    Write-Host "[WARN] Skipping initial dump: $($s.InputFile) — $($_.Exception.Message)"
  }
}

Write-Host "[INFO] Watching:"
Write-Host "  Base MP path : $MpBase"
Write-Host "  Export base  : $ExportBase"
Write-Host "  Snapshots    : keep last $MaxSnapshots; older -> archive.zip"
Write-Host "  Poll         : $PollSeconds s"
Write-Host "  Stabilize    : $StabilizeMs ms"
Write-Host ""
Write-Host "[INFO] Entering watch loop (Ctrl+C to stop) ..."

# Watch loop (poll + pick up new servers)
while ($true) {
  Start-Sleep -Seconds $PollSeconds

  $servers = Get-Servers
  foreach ($s in $servers) {
    try {
      $fi = Get-Item -LiteralPath $s.InputFile -ErrorAction Stop
    } catch {
      continue
    }

    if (-not $state.ContainsKey($s.InputFile)) {
      $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
      Write-Host "[INFO] New server detected: $($s.Name)"
      Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir -SnapDir $s.SnapDir
      continue
    }

    $prev = $state[$s.InputFile]
    if ($fi.LastWriteTimeUtc -ne $prev.WriteTimeUtc -or $fi.Length -ne $prev.Size) {
      Write-Host ("[INFO] Change detected for {0} at {1:u}" -f $s.Name, (Get-Date))
      Write-Host ("       LastWriteTime: {0} -> {1}" -f $prev.WriteTimeUtc, $fi.LastWriteTimeUtc)
      Write-Host ("       Size         : {0} -> {1}" -f $prev.Size, $fi.Length)

      $state[$s.InputFile] = @{ WriteTimeUtc = $fi.LastWriteTimeUtc; Size = $fi.Length }
      try {
        Invoke-Dump -InputFile $s.InputFile -ExportDir $s.ExportDir -SnapDir $s.SnapDir
      } catch {
        Write-Host "[ERROR] Dump failed for $($s.Name): $($_.Exception.Message)"
      }
    }
  }
}
