# Journeymap Export

Export [JourneyMap](https://www.curseforge.com/minecraft/mc-mods/journeymap) waypoints for backup and recreate them with ease.

This tool reads your `WaypointData.dat` file (NBT format) from `.minecraft/journeymap/data/mp/<server>/waypoints/` and produces:

- `waypoints.json` — full structured export of all waypoint data
- `waypoints.csv` — tabular form (coords, dimension, group, etc.)
- `create_waypoints.txt` — ready-to-use `/waypoint create …` commands, grouped by **group name** and sorted by **waypoint name**

System groups (`journeymap_temp`, `journeymap_death`, `journeymap_all`, `journeymap_default`) are skipped automatically.

---

## Features

- Works standalone, no Minecraft or JourneyMap running
- Supports multiple formats: raw NBT, gzip, zip, zlib
- Groups your waypoints by group name
- Generates `/waypoint create` commands to reimport easily
- Includes a PowerShell watcher script that automatically re-dumps on updates
- Auto-discovers all server worlds under `.minecraft/journeymap/data/mp/`
- Creates per-server export folders automatically

---

## Requirements

- **Java 17+**
- **PowerShell 5+** (or PowerShell Core 7+ on Linux/Mac if you adapt paths)

---

## Usage

### 1. Clone and build

```powershell
git clone https://github.com/survivorsunited/journeymap-export.git
cd journeymap-export
```

### 2. One-off export

Compile and run against a specific `WaypointData.dat`:

```powershell
# Compile
javac -d out org\survivorsunited\utils\journeymap\WaypointDataDump.java

# Run (replace SERVERFOLDER with your server folder name)
java -cp out org.survivorsunited.utils.journeymap.WaypointDataDump `
  "$env:USERPROFILE\AppData\Roaming\.minecraft\journeymap\data\mp\SERVERFOLDER\waypoints\WaypointData.dat" `
  --out export
```

Results will be in `.\export\`.

---

### 3. Continuous export (watch mode, no input needed)

Run the included **PowerShell script** `run.ps1`.  
It will:

- Compile the dumper
- Auto-discover all server folders under `.minecraft/journeymap/data/mp/`
- Create `export/<ServerFolder>/` for each server
- Watch every `WaypointData.dat`
- Re-export automatically whenever the file changes

```powershell
# Just run it, no parameters needed
.
un.ps1
```

Default behavior:
- Polls every 3 seconds
- Waits 750 ms after a change before re-reading
- Picks up new servers that appear while running

---

## Example output

### create_waypoints.txt

```text
# Group: Camp

/waypoint create "[Camp] Nether Fortress Alpha" minecraft:overworld 0 64 0 -12743750
/waypoint create "[Camp] Farm - Music Disk" minecraft:overworld 0 64 0 -3524062
/waypoint create "[Camp] Farm - District" minecraft:overworld 0 64 0 -9983358
/waypoint create "[Camp] Farm - Basic" minecraft:overworld 0 64 0 -668065
```

---

## Notes

- Groups are taken from JourneyMap’s saved data.  
- System groups (`journeymap_temp`, `journeymap_death`, etc.) are ignored.  
- Dimensions are exported as stored (e.g. `minecraft:overworld`, `minecraft:the_nether`).  
- Colors are exported as either named strings or integer RGB values.  
- `run.ps1` works with **multiple servers** at once.  

---

## Contributing

Issues and PRs welcome! Please open an issue if you run into a new format of `WaypointData.dat` or want improvements.

---
