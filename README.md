# Journeymap Export

Export [JourneyMap](https://www.curseforge.com/minecraft/mc-mods/journeymap) waypoints for backup and recreate them with ease.

This tool reads your `WaypointData.dat` file (NBT format) from `.minecraft/journeymap/data/mp/<server>/waypoints/` and produces:

- `waypoints.json` — full structured export of all waypoint data
- `waypoints.csv` — tabular form (coords, dimension, group, etc.)
- `create_waypoints.txt` — ready-to-use `/waypoint create …` commands, grouped by **group name** and sorted by **waypoint name**

System groups (`journeymap_temp`, `journeymap_death`, `journeymap_all`, `journeymap_default`) are skipped automatically.

---

## Features

- **Standalone operation**: No Minecraft or JourneyMap dependencies required
- **Multiple compression formats**: Supports raw NBT, GZIP, ZIP, and ZLIB compression
- **Smart group handling**: 
  - Converts group names to title case (e.g., "my_group" → "My Group")
  - Preserves existing waypoint prefixes (e.g., "[Farm] Wheat Field")
  - Uses "Global" as default group for waypoints without groups
- **Selective coordinate offsets**: 
  - Applies Y-3 and Z-1 offsets only to "Waystones" group waypoints
  - Other waypoint groups maintain original coordinates
- **Multiple export formats**:
  - `waypoints.json` — Complete NBT data as structured JSON
  - `waypoints.csv` — Tabular data for spreadsheet analysis
  - `create_waypoints.txt` — Ready-to-use JourneyMap commands
- **Intelligent command generation**:
  - Groups commands by waypoint group
  - Sorts waypoints alphabetically within each group
  - Includes proper color mapping and dimension handling
- **Automation tools**:
  - PowerShell watcher script for continuous export
  - Auto-discovery of all server worlds
  - Automatic per-server export folder creation

---

## Configuration

The tool includes several configurable constants at the top of `WaypointDataDump.java`:

```java
// Target player for generated commands (empty = command runner)
private static final String DEFAULT_PLAYER = "MrWild0ne";

// Default group for waypoints without a group
private static final String DEFAULT_GROUP_ID = "Global";

// Coordinate offsets (applied only to Waystones group)
private static final int Y_OFFSET = -3;  // Y coordinate adjustment
private static final int Z_OFFSET = -1;  // Z coordinate adjustment
```

### Customization Examples

- **Change target player**: Set `DEFAULT_PLAYER` to your Minecraft username
- **Change default group**: Modify `DEFAULT_GROUP_ID` to your preferred group name
- **Adjust waystone positions**: Modify `Y_OFFSET` and `Z_OFFSET` for fine-tuning waystone placement
- **Disable offsets**: Set both offsets to `0` to disable coordinate adjustments

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
# Group: Farm

waypoint create "[Farm] Wheat Field" minecraft:overworld 100 67 200 white MrWild0ne
waypoint create "[Farm] Animal Pen" minecraft:overworld 150 64 250 green MrWild0ne

# Group: Waystones

waypoint create "[Waystones] Village Stone" minecraft:overworld 200 61 300 blue MrWild0ne
waypoint create "[Waystones] Nether Portal" minecraft:the_nether 50 58 75 red MrWild0ne

# Group: Global

waypoint create "[Global] Spawn Point" minecraft:overworld 0 64 0 yellow MrWild0ne
```

### Key Features Demonstrated

- **Title case group names**: "farm" → "Farm", "waystones" → "Waystones"
- **Selective coordinate offsets**: Waystones have Y-3 and Z-1 applied (200,64,300 → 200,61,299)
- **Preserved prefixes**: Existing "[Farm]" prefixes are maintained
- **Default group**: Waypoints without groups get "Global" prefix
- **Color mapping**: RGB values converted to named colors (white, green, blue, red, yellow)

---

## Notes

- **Group handling**: Groups are taken from JourneyMap's saved data and converted to title case
- **System groups**: `journeymap_temp`, `journeymap_death`, `journeymap_all`, `journeymap_default` are automatically filtered out
- **Coordinate offsets**: Only applied to "Waystones" group waypoints (Y-3, Z-1 by default)
- **Prefix preservation**: Waypoints with existing prefixes (e.g., "[Farm]") keep their original names
- **Default grouping**: Waypoints without groups are assigned to "Global" group
- **Color mapping**: RGB values are converted to nearest named colors for better readability
- **Dimensions**: Exported as stored (e.g. `minecraft:overworld`, `minecraft:the_nether`)
- **Multi-server support**: `run.ps1` automatically handles multiple servers simultaneously  

---

## Contributing

Issues and PRs welcome! Please open an issue if you run into a new format of `WaypointData.dat` or want improvements.

---
