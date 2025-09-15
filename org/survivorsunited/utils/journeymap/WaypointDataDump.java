package org.survivorsunited.utils.journeymap;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.Map;

/**
 * Standalone NBT dumper for JourneyMap WaypointData.dat (ZIP/GZIP/ZLIB/raw NBT).
 * No Minecraft/JourneyMap deps required.
 *
 * Features:
 * - Supports multiple compression formats (ZIP, GZIP, ZLIB, raw NBT)
 * - Exports to JSON, CSV, and waypoint creation commands
 * - Configurable coordinate offsets (applied only to Waystones group)
 * - Smart group name handling with title case conversion
 * - Preserves existing waypoint prefixes
 * - Filters out system groups (journeymap_temp, journeymap_death, etc.)
 *
 * Configuration Constants:
 * - DEFAULT_PLAYER: Target player for commands (default: "MrWild0ne")
 * - DEFAULT_GROUP_ID: Default group for waypoints without group (default: "Global")
 * - Y_OFFSET: Y coordinate offset for Waystones group (default: -3)
 * - Z_OFFSET: Z coordinate offset for Waystones group (default: -1)
 *
 * Usage:
 *   javac -d out org/survivorsunited.utils/journeymap/WaypointDataDump.java
 *   java -cp out org.survivorsunited.utils.journeymap.WaypointDataDump "C:\path\WaypointData.dat" --out export
 *
 * Output Files:
 * - waypoints.json: Complete NBT data as JSON
 * - waypoints.csv: Structured waypoint data in CSV format
 * - create_waypoints.txt: JourneyMap commands grouped by waypoint group
 */
public class WaypointDataDump {

    // Simplified named colors for /waypoint create
    private static final Map<String,Integer> NAMED_COLORS = Map.ofEntries(
        Map.entry("black",       0x000000),
        Map.entry("dark_blue",   0x0000AA),
        Map.entry("dark_green",  0x00AA00),
        Map.entry("dark_aqua",   0x00AAAA),
        Map.entry("dark_red",    0xAA0000),
        Map.entry("dark_purple", 0xAA00AA),
        Map.entry("gold",        0xFFAA00),
        Map.entry("gray",        0xAAAAAA),
        Map.entry("dark_gray",   0x555555),
        Map.entry("blue",        0x5555FF),
        Map.entry("green",       0x55FF55),
        Map.entry("aqua",        0x55FFFF),
        Map.entry("red",         0xFF5555),
        Map.entry("light_purple",0xFF55FF),
        Map.entry("yellow",      0xFFFF55),
        Map.entry("white",       0xFFFFFF)
    );

    // Quick lookup for valid names
    private static final Set<String> ALLOWED_COLOR_NAMES = new HashSet<>(NAMED_COLORS.keySet());

    // Optional: set a default player to append to commands; leave "" to target the runner
    private static final String DEFAULT_PLAYER = "MrWild0ne";
    
    // Default group ID for waypoints without a group
    private static final String DEFAULT_GROUP_ID = "Global";
    
    // Coordinate offsets for waypoint creation commands
    private static final int Z_OFFSET = -1;
    private static final int Y_OFFSET = -3;

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -cp out org.survivorsunited.utils.journeymap.WaypointDataDump <WaypointData.dat|.zip|.gz> [--out export]");
            System.exit(2);
        }
        Path input = Paths.get(args[0]).toAbsolutePath();
        String outDir = "export";
        for (int i = 1; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) outDir = args[++i];
        }
        Path out = Paths.get(outDir).toAbsolutePath();

        log("[INFO] Input: " + input);
        log("[INFO] Output dir: " + out);

        try {
            Files.createDirectories(out);
            byte[] nbt = loadPossiblyCompressed(input);

            // Parse NBT (root = TAG_Compound, often unnamed)
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(nbt));
            NbtElement root = Nbt.readNamed(din);
            Object jsonObj = Nbt.toJsonLike(root);

            // JSON
            Path jsonPath = out.resolve("waypoints.json");
            writeJson(jsonPath, jsonObj);
            log("[INFO] Wrote " + jsonPath);

            // CSV (best-effort)
            Map<String,Object> jsonTop;
            if (jsonObj instanceof Map<?,?> m) {
                @SuppressWarnings("unchecked") Map<String,Object> cast = (Map<String,Object>) m;
                jsonTop = cast;
            } else {
                jsonTop = new LinkedHashMap<>();
                jsonTop.put("root", jsonObj);
            }
            List<Map<String,Object>> rows = WaypointCsvExtractor.extract(jsonTop);
            if (!rows.isEmpty()) {
                Path csvPath = out.resolve("waypoints.csv");
                writeCsv(csvPath, rows);
                log("[INFO] Wrote " + csvPath);
            } else {
                log("[WARN] CSV not generated (structure not recognized). JSON includes full tree.");
            }

            // TXT with /waypoint create commands (grouped by human-readable group name)
            writeCreateCommandsTxt(out, root);

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    // --- POS helpers: prefer pos.{x,y,z}, fallback to top-level x/y/z ---
    private static Integer getCoordInt(NbtElement wp, String key, Integer def) {
        // 1) direct (top-level)
        NbtElement ch = getChild(wp, key);
        if (ch != null && ch.value instanceof Number n) return n.intValue();

        // 2) pos.{key}
        NbtElement pos = getCompoundChild(wp, "pos");
        if (pos != null) {
            NbtElement ch2 = getChild(pos, key);
            if (ch2 != null && ch2.value instanceof Number n2) return n2.intValue();
        }
        return def;
    }

    private static String normalizeColorName(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return ALLOWED_COLOR_NAMES.contains(t) ? t : null;
    }
    
    private static String mapColor(Object colorVal) {
        if (colorVal == null) return "white";
        // If string, try to accept a valid name or parse numeric
        if (colorVal instanceof String str) {
            String named = normalizeColorName(str);
            if (named != null) return named;
    
            try {
                // allow "#RRGGBB"
                if (str.startsWith("#") && str.length() == 7) {
                    int rgb = Integer.parseInt(str.substring(1), 16) & 0xFFFFFF;
                    return nearestNamed(rgb);
                }
                // allow decimal int in string
                int rgb = Integer.parseInt(str);
                return nearestNamed(rgb & 0xFFFFFF);
            } catch (Exception ignored) {
                return "white";
            }
        }
        if (colorVal instanceof Number n) {
            int rgb = n.intValue() & 0xFFFFFF; // strip alpha if present
            return nearestNamed(rgb);
        }
        return "white";
    }
    
    private static String nearestNamed(int rgb) {
        String bestName = "white";
        long bestDist = Long.MAX_VALUE;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (Map.Entry<String,Integer> e : NAMED_COLORS.entrySet()) {
            int c = e.getValue();
            int cr = (c >> 16) & 0xFF, cg = (c >> 8) & 0xFF, cb = c & 0xFF;
            int dr = r - cr, dg = g - cg, db = b - cb;
            long dist = (long)dr*dr + (long)dg*dg + (long)db*db;
            if (dist < bestDist) {
                bestDist = dist;
                bestName = e.getKey();
            }
        }
        return bestName;
    }
    
    /**
     * Converts a string to title case (first letter of each word capitalized).
     * Handles separators like spaces, underscores, and hyphens.
     * 
     * Examples:
     * - "waystones" → "Waystones"
     * - "my_group" → "My Group"
     * - "underground-bases" → "Underground Bases"
     * - "journeymap_default" → "Journeymap Default"
     * 
     * @param str The string to convert to title case
     * @return The title-cased string, or original string if null/empty
     */
    private static String toTitleCase(String str) {
        if (str == null || str.isEmpty()) return str;
        
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        
        for (char c : str.toCharArray()) {
            if (Character.isWhitespace(c) || c == '_' || c == '-') {
                result.append(c);
                capitalizeNext = true;
            } else {
                if (capitalizeNext) {
                    result.append(Character.toUpperCase(c));
                    capitalizeNext = false;
                } else {
                    result.append(Character.toLowerCase(c));
                }
            }
        }
        
        return result.toString();
    }


    // --- load zip/gzip/zlib/raw ---
    private static byte[] loadPossiblyCompressed(Path p) throws IOException {
        byte[] head;
        try (InputStream is = Files.newInputStream(p)) {
            head = is.readNBytes(12);
        }
        log("[DEBUG] First bytes: " + toHex(head));

        // GZIP?
        if (head.length >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            log("[DEBUG] Detected GZIP");
            try (InputStream gz = new GZIPInputStream(Files.newInputStream(p))) {
                return readAll(gz);
            }
        }

        // ZIP? (PK..)
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K') {
            log("[DEBUG] Detected ZIP");
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(p))) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    log("[DEBUG] ZIP entry: " + ze.getName());
                    if (ze.getName().equals("WaypointData.dat") || ze.getName().endsWith(".dat")) {
                        byte[] data = readAll(zis);
                        zis.closeEntry();
                        return data;
                    }
                    zis.closeEntry();
                }
            }
            throw new IOException("ZIP did not contain WaypointData.dat");
        }

        // ZLIB/DEFLATE? (0x78 0x01/0x9C/0xDA etc.)
        if (head.length >= 2 && (head[0] & 0xFF) == 0x78) {
            log("[DEBUG] Detected ZLIB/DEFLATE (0x78 ??)");
            try (InputStream inf = new InflaterInputStream(Files.newInputStream(p))) {
                return readAll(inf);
            } catch (IOException ex) {
                log("[WARN] Inflater failed, falling back to raw. Error: " + ex.getMessage());
            }
        }

        // else raw NBT
        log("[DEBUG] Treating as raw NBT");
        return Files.readAllBytes(p);
    }

    private static String toHex(byte[] b) {
        if (b == null || b.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02X", b[i]));
            if (i < b.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    // --- JSON writer (pretty) ---
    private static void writeJson(Path path, Object obj) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writeJsonRecursive(w, obj, 0);
            w.write("\n");
        }
    }
    @SuppressWarnings("unchecked")
    private static void writeJsonRecursive(Writer w, Object v, int ind) throws IOException {
        if (v == null) { w.write("null"); return; }
        if (v instanceof Number || v instanceof Boolean) { w.write(String.valueOf(v)); return; }
        if (v instanceof String s) { w.write("\"" + esc(s) + "\""); return; }
        if (v instanceof Map<?,?> m) {
            w.write("{\n");
            int i=0, sz=m.size();
            for (Map.Entry<?,?> e : m.entrySet()) {
                indent(w, ind+2);
                w.write("\""+esc(String.valueOf(e.getKey()))+"\": ");
                writeJsonRecursive(w, e.getValue(), ind+2);
                if (++i < sz) w.write(",");
                w.write("\n");
            }
            indent(w, ind); w.write("}");
            return;
        }
        if (v instanceof Collection<?> c) {
            w.write("[\n");
            int i=0, sz=c.size();
            for (Object o : c) {
                indent(w, ind+2);
                writeJsonRecursive(w, o, ind+2);
                if (++i < sz) w.write(",");
                w.write("\n");
            }
            indent(w, ind); w.write("]");
            return;
        }
        // Java primitive arrays: convert to list for readability
        if (v instanceof int[] ia)  { writeJsonRecursive(w, toList(ia), ind); return; }
        if (v instanceof long[] la) { writeJsonRecursive(w, toList(la), ind); return; }
        if (v instanceof byte[] ba) { writeJsonRecursive(w, Base64.getEncoder().encodeToString(ba), ind); return; }
        w.write("\"" + esc(String.valueOf(v)) + "\"");
    }
    private static List<Integer> toList(int[] a) { List<Integer> L=new ArrayList<>(a.length); for(int x:a)L.add(x); return L; }
    private static List<Long>    toList(long[] a){ List<Long> L=new ArrayList<>(a.length); for(long x:a)L.add(x); return L; }
    private static void indent(Writer w, int n) throws IOException { for (int i=0;i<n;i++) w.write(' '); }
    private static String esc(String s) { return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n"); }

    // --- CSV writer ---
    private static void writeCsv(Path path, List<Map<String,Object>> rows) throws IOException {
        List<String> header = new ArrayList<>(rows.get(0).keySet());
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",", header)); w.write("\n");
            for (Map<String,Object> r : rows) {
                List<String> cells = new ArrayList<>(header.size());
                for (String h : header) cells.add(csv(r.get(h)));
                w.write(String.join(",", cells)); w.write("\n");
            }
        }
    }
    private static String csv(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        boolean q = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (q) s = "\"" + s.replace("\"","\"\"") + "\"";
        return s;
    }

    private static void log(String m) { System.out.println(m); }

    // ===================== Minimal NBT reader =====================
    static class Nbt {
        static final int TAG_End=0,TAG_Byte=1,TAG_Short=2,TAG_Int=3,TAG_Long=4,TAG_Float=5,TAG_Double=6,TAG_Byte_Array=7,
                         TAG_String=8,TAG_List=9,TAG_Compound=10,TAG_Int_Array=11,TAG_Long_Array=12;

        static NbtElement readNamed(DataInput in) throws IOException {
            int type = in.readUnsignedByte();
            if (type != TAG_Compound) throw new IOException("Root must be TAG_Compound, got " + type);
            String name = readUTF(in);
            NbtElement value = readCompound(in);
            value.name = name;
            return value;
        }

        static NbtElement read(DataInput in, int type) throws IOException {
            NbtElement el = new NbtElement(); el.type = type;
            switch (type) {
                case TAG_End -> {}
                case TAG_Byte -> el.value = in.readByte();
                case TAG_Short -> el.value = in.readShort();
                case TAG_Int -> el.value = in.readInt();
                case TAG_Long -> el.value = in.readLong();
                case TAG_Float -> el.value = in.readFloat();
                case TAG_Double -> el.value = in.readDouble();
                case TAG_Byte_Array -> {
                    int len = in.readInt();
                    byte[] b = new byte[len];
                    in.readFully(b);
                    el.value = b;
                }
                case TAG_String -> el.value = readUTF(in);
                case TAG_List -> {
                    int elemType = in.readUnsignedByte();
                    int len = in.readInt();
                    List<NbtElement> list = new ArrayList<>(len);
                    for (int i=0;i<len;i++) list.add(read(in, elemType));
                    el.childType = elemType;
                    el.children = list;
                }
                case TAG_Compound -> {
                    NbtElement comp = readCompound(in);
                    el.map = comp.map;
                }
                case TAG_Int_Array -> {
                    int len = in.readInt();
                    int[] arr = new int[len];
                    for (int i=0;i<len;i++) arr[i] = in.readInt();
                    el.value = arr;
                }
                case TAG_Long_Array -> {
                    int len = in.readInt();
                    long[] arr = new long[len];
                    for (int i=0;i<len;i++) arr[i] = in.readLong();
                    el.value = arr;
                }
                default -> throw new IOException("Unknown NBT tag: " + type);
            }
            return el;
        }

        static NbtElement readCompound(DataInput in) throws IOException {
            NbtElement comp = new NbtElement(); comp.type = TAG_Compound; comp.map = new LinkedHashMap<>();
            while (true) {
                int t = in.readUnsignedByte();
                if (t == TAG_End) break;
                String name = readUTF(in);
                NbtElement child = read(in, t);
                child.name = name;
                comp.map.put(name, child);
            }
            return comp;
        }

        static String readUTF(DataInput in) throws IOException {
            int len = in.readUnsignedShort();
            byte[] b = new byte[len];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        static Object toJsonLike(NbtElement el) {
            if (el == null) return null;
            if (el.type == TAG_Compound) {
                Map<String,Object> m = new LinkedHashMap<>();
                if (el.name != null) m.put("_name", el.name);
                if (el.map != null) {
                    for (Map.Entry<String,NbtElement> e : el.map.entrySet()) {
                        m.put(e.getKey(), toJsonLike(e.getValue()));
                    }
                }
                return m;
            }
            if (el.type == TAG_List) {
                List<Object> list = new ArrayList<>();
                if (el.children != null) for (NbtElement c : el.children) list.add(toJsonLike(c));
                Map<String,Object> obj = new LinkedHashMap<>();
                obj.put("_list", list);
                obj.put("_elemType", el.childType);
                return obj;
            }
            if (el.type == TAG_Byte_Array) return Base64.getEncoder().encodeToString((byte[]) el.value);
            if (el.type == TAG_Int_Array)  return toList((int[]) el.value);
            if (el.type == TAG_Long_Array) return toList((long[]) el.value);
            return el.value; // primitives & strings
        }

        private static List<Integer> toList(int[] a) { List<Integer> L=new ArrayList<>(a.length); for(int x:a)L.add(x); return L; }
        private static List<Long>    toList(long[] a){ List<Long> L=new ArrayList<>(a.length); for(long x:a)L.add(x); return L; }
    }

    static class NbtElement {
        int type;
        String name;
        Object value; // primitives, arrays
        Map<String,NbtElement> map; // compound
        List<NbtElement> children;  // list
        Integer childType;
    }

    // ===================== CSV extractor =====================
    static class WaypointCsvExtractor {

        @SuppressWarnings("unchecked")
        static List<Map<String,Object>> extract(Map<String,Object> json) {
            List<Map<String,Object>> rows = new ArrayList<>();

            // 1) Expected JM shape: groups -> group -> waypoints -> <id>
            Object groupsObj = json.get("groups");
            if (groupsObj instanceof Map<?,?> groups) {
                System.out.println("[DEBUG] keys under 'groups': " + ((Map<?,?>)groups).keySet());
                int groupCount = 0, wpCount = 0;
                for (Map.Entry<?,?> ge : ((Map<?,?>)groups).entrySet()) {
                    String groupKey = String.valueOf(ge.getKey());
                    Object groupVal = ge.getValue();
                    if (!(groupVal instanceof Map<?,?> gmapAny)) continue;
                    groupCount++;

                    @SuppressWarnings("unchecked")
                    Map<String,Object> gmap = (Map<String,Object>) gmapAny;
                    System.out.println("[DEBUG] group '" + groupKey + "' keys: " + gmap.keySet());

                    Object wpsObj = firstAny(gmap, "waypoints","wps","points","entries");
                    if (wpsObj instanceof Map<?,?> wps) {
                        for (Map.Entry<?,?> we : ((Map<?,?>)wps).entrySet()) {
                            Object wpVal = we.getValue();
                            if (!(wpVal instanceof Map<?,?> wmAny)) continue;
                            Map<String,Object> wm = (Map<String,Object>) wmAny;

                            Map<String,Object> row = toRow(wm);
                            if (row.get("guid") == null) row.put("guid", String.valueOf(we.getKey()));
                            if (!row.containsKey("groupId") || row.get("groupId") == null) {
                                Object groupId = first(gmap, "id","groupId","name","label", "key");
                                row.put("groupId", groupId != null ? groupId : DEFAULT_GROUP_ID);
                            }
                            rows.add(row);
                            wpCount++;
                        }
                    }
                }
                System.out.println("[DEBUG] groups found: " + groupCount + ", waypoints found: " + wpCount);
                if (!rows.isEmpty()) return rows;
            }

            // 2) Root-level waypoints map
            Object wpsRoot = firstAny(json, "waypoints","wps","points","entries");
            if (wpsRoot instanceof Map<?,?> wps) {
                int wpCount = 0;
                for (Map.Entry<?,?> we : ((Map<?,?>)wps).entrySet()) {
                    Object wpVal = we.getValue();
                    if (wpVal instanceof Map<?,?> wmAny) {
                        Map<String,Object> wm = (Map<String,Object>) wmAny;
                        Map<String,Object> row = toRow(wm);
                        if (row.get("guid") == null) row.put("guid", String.valueOf(we.getKey()));
                        rows.add(row);
                        wpCount++;
                    }
                }
                System.out.println("[DEBUG] root-level waypoints found: " + wpCount);
                if (!rows.isEmpty()) return rows;
            }

            // 3) Deep heuristic scan
            Deque<Object> dq = new ArrayDeque<>(); dq.add(json);
            while (!dq.isEmpty()) {
                Object o = dq.removeFirst();
                if (o instanceof Map<?,?> mmAny) {
                    @SuppressWarnings("unchecked")
                    Map<String,Object> mm = (Map<String,Object>) mmAny;
                    for (Map.Entry<String,Object> e : mm.entrySet()) {
                        Object v = e.getValue();
                        if (v instanceof Map<?,?> || v instanceof List<?>) dq.addLast(v);
                    }
                    Collection<Object> vals = new ArrayList<>(mm.values());
                    if (!vals.isEmpty() && vals.stream().allMatch(x -> x instanceof Map)) {
                        int hits = 0;
                        for (Object x : vals) if (x instanceof Map<?,?> m && looksLikeWaypoint((Map<String,Object>) m)) hits++;
                        if (hits >= Math.max(1, vals.size()/2)) {
                            for (Object x : vals) rows.add(toRow((Map<String,Object>) x));
                            System.out.println("[DEBUG] heuristic map-of-waypoints hit: " + rows.size() + " rows");
                            return rows;
                        }
                    }
                } else if (o instanceof List<?> list) {
                    int hits = 0;
                    for (Object x : list) if (x instanceof Map<?,?> m && looksLikeWaypoint((Map<String,Object>) m)) hits++;
                    if (hits >= Math.max(1, list.size()/2)) {
                        for (Object x : list) rows.add(toRow((Map<String,Object>) x));
                        System.out.println("[DEBUG] heuristic list-of-waypoints hit: " + rows.size() + " rows");
                        return rows;
                    }
                    for (Object x : list) dq.addLast(x);
                }
            }
            return rows;
        }

        static boolean looksLikeWaypoint(Map<String,Object> m) {
            // direct x/y/z
            boolean direct = m.containsKey("x") && m.containsKey("y") && m.containsKey("z");
            if (direct) return true;
            // pos.{x,y,z}
            Object pos = m.get("pos");
            if (pos instanceof Map<?,?> pm) {
                return ((Map<?,?>)pm).containsKey("x") && ((Map<?,?>)pm).containsKey("y") && ((Map<?,?>)pm).containsKey("z");
            }
            return false;
        }

        @SuppressWarnings("unchecked")
        static Map<String,Object> toRow(Map<String,Object> m) {
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("guid", first(m, "guid","id","uuid","key"));
            r.put("name", first(m, "name","label","title"));
            Object groupId = first(m, "groupId","group","grp");
            r.put("groupId", groupId != null ? groupId : DEFAULT_GROUP_ID);
            r.put("primaryDimension", first(m, "primaryDimension","dimension","dim","primaryDim"));

            Object x = m.get("x"), y = m.get("y"), z = m.get("z");
            if (x == null || y == null || z == null) {
                Object pos = m.get("pos");
                if (pos instanceof Map<?,?> pm) {
                    if (x == null) x = ((Map<?,?>)pm).get("x");
                    if (y == null) y = ((Map<?,?>)pm).get("y");
                    if (z == null) z = ((Map<?,?>)pm).get("z");
                }
            }
            r.put("x", x); r.put("y", y); r.put("z", z);

            r.put("enabled", first(m, "enabled","isEnabled"));
            r.put("persistent", first(m, "persistent","isPersistent","save"));
            r.put("color", first(m, "color","colour","rgb"));
            r.put("iconKey", first(m, "iconKey","icon","marker"));
            r.put("note", first(m, "note","description","desc"));
            return r;
        }
        
        static Object first(Map<String,Object> m, String... keys) {
            for (String k : keys) if (m.containsKey(k)) return m.get(k);
            return null;
        }
        static Object firstAny(Map<String,Object> m, String... keys) {
            for (String k : keys) if (m.containsKey(k)) return m.get(k);
            return null;
        }
    }

    // ===================== create_waypoints.txt =====================
    private static final Set<String> SYSTEM_GROUPS = new HashSet<>(Arrays.asList(
            "journeymap_temp", "journeymap_death", "journeymap_all", "journeymap_default"
    ));

    private static void writeCreateCommandsTxt(Path outDir, NbtElement root) throws IOException {
        // Build groupId -> groupName from root.groups
        Map<String,String> groupNames = new LinkedHashMap<>();
        NbtElement groupsComp = getCompoundChild(root, "groups");
        if (groupsComp != null && groupsComp.map != null) {
            for (Map.Entry<String, NbtElement> ge : groupsComp.map.entrySet()) {
                String gid = ge.getKey();
                NbtElement g = ge.getValue();
                String gname = getStringChild(g, "name");
                if (gname == null || gname.isEmpty()) gname = gid;
                groupNames.put(gid, gname);
            }
        }

        // Collect waypoints
        Map<String, List<String>> cmdsByGroupName = new LinkedHashMap<>();
        NbtElement wps = getCompoundChild(root, "waypoints");
        int total = 0;
        if (wps != null && wps.map != null) {
            for (Map.Entry<String, NbtElement> we : wps.map.entrySet()) {
                NbtElement wp = we.getValue();
                String groupId = getStringChild(wp, "groupId");
                if (groupId == null || groupId.isEmpty()) groupId = DEFAULT_GROUP_ID;
                if (SYSTEM_GROUPS.contains(groupId)) continue;

                String gname = groupNames.getOrDefault(groupId, DEFAULT_GROUP_ID);
                String titleCaseGroup = toTitleCase(gname);
                String name  = getStringChild(wp, "name");
                if (name == null || name.isEmpty()) name = "Unnamed";

                // coords (with configurable offsets for Waystones group only)
                int baseX = getCoordInt(wp, "x", 0);
                int baseY = getCoordInt(wp, "y", 64);
                int baseZ = getCoordInt(wp, "z", 0);
                
                // Apply coordinate offsets only for Waystones group
                // This allows for fine-tuning waystone positions without affecting other waypoints
                if ("waystones".equalsIgnoreCase(titleCaseGroup)) {
                    baseY += Y_OFFSET;  // Default: -3
                    baseZ += Z_OFFSET;   // Default: -1
                }
                
                String x = String.valueOf(baseX);
                String y = String.valueOf(baseY);
                String z = String.valueOf(baseZ);

                // dimension
                String dim = getStringChild(wp, "primaryDimension");
                if (dim == null || dim.isEmpty()) dim = "minecraft:overworld";

                // color → map to a valid name
                String colorName;
                NbtElement colorEl = getChild(wp, "color");
                if (colorEl != null) {
                    colorName = mapColor(colorEl.value);
                } else {
                    colorName = "white";
                }
                
                // build command with smart prefix handling
                String prefixedName;
                if (name.startsWith("[") && name.contains("]")) {
                    // Waypoint already has a prefix (e.g., "[Farm] Wheat Field"), use it as-is
                    prefixedName = name.replace("\"", "\\\"");
                } else {
                    // Add group prefix (e.g., "Wheat Field" → "[Farm] Wheat Field")
                    prefixedName = ("[" + titleCaseGroup + "] " + name).replace("\"", "\\\"");
                }
                String player = DEFAULT_PLAYER; // "" = whoever runs the command
                String cmd = String.format(
                    "waypoint create \"%s\" %s %s %s %s %s%s",
                    prefixedName, dim, x, y, z, colorName,
                    (player == null || player.isEmpty()) ? "" : " " + player
                );

                cmdsByGroupName.computeIfAbsent(gname, k -> new ArrayList<>()).add(cmd);
                total++;
            }
        }

        // Sort each group's list alphabetically
        for (List<String> list : cmdsByGroupName.values()) {
            list.sort(Comparator.naturalOrder());
        }

        Path txt = outDir.resolve("create_waypoints.txt");
        try (BufferedWriter w = Files.newBufferedWriter(txt, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, List<String>> e : cmdsByGroupName.entrySet()) {
                String gname = e.getKey();
                w.write("# Group: " + gname); 
                w.newLine(); w.newLine();
                for (String cmd : e.getValue()) {
                    w.write(cmd); 
                    w.newLine();
                }
                w.newLine();
            }
        }
        log("[INFO] Wrote " + txt + " with " + total + " commands across " + cmdsByGroupName.size() + " groups");
    }


    // --- NBT helpers ---
    private static NbtElement getCompoundChild(NbtElement comp, String key) {
        if (comp == null || comp.map == null) return null;
        NbtElement ch = comp.map.get(key);
        return (ch != null && ch.type == Nbt.TAG_Compound) ? ch : null;
    }
    private static NbtElement getChild(NbtElement comp, String key) {
        if (comp == null || comp.map == null) return null;
        return comp.map.get(key);
    }
    private static String getStringChild(NbtElement comp, String key) {
        NbtElement ch = getChild(comp, key);
        return (ch != null && ch.value instanceof String s) ? s : null;
    }
    private static int getIntChild(NbtElement comp, String key, int def) {
        NbtElement ch = getChild(comp, key);
        if (ch != null && ch.value instanceof Number n) return n.intValue();
        return def;
    }
}
