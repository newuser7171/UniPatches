package unipatch.overlaycore;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-process memory scanner for the Universal Overlay.
 *
 * Reads and writes the host app's own address space via /proc/self/maps and
 * /proc/self/mem. No root, no ptrace, no cross-process access: a process can
 * always read and write its own memory, so this only ever touches the app it
 * is patched into.
 *
 * Deliberately NOT a GameGuardian replacement. That attaches to other processes
 * with ptrace and is blocked by SELinux and ptrace_scope on modern Android. This
 * is scoped to the patched app and is only useful where the flag is a live
 * in-memory value rather than a compile-time constant.
 *
 * Scan modes mirror the familiar narrow-as-you-play loop: exact value, increased,
 * decreased, and unchanged since the previous scan. Values are grouped by their
 * current contents so repeated scans intersect rather than accumulate.
 */
public final class MemoryScanner {

    /** How a candidate must have changed between two scans. */
    public enum Mode {
        /** Value equals the search value. */
        EXACT,
        /** Value is greater than it was at the previous scan. */
        INCREASED,
        /** Value is less than it was at the previous scan. */
        DECREASED,
        /** Value did not change since the previous scan. */
        UNCHANGED
    }

    /** One readable/writable private mapping, page-aligned. */
    private static final class Region {
        final long start;
        final long end;

        Region(long start, long end) {
            this.start = start;
            this.end = end;
        }

        long size() { return end - start; }
    }

    /**
     * Region size cap. The Java heap on a large app runs to hundreds of MB and
     * scanning it as 4-byte words takes seconds. Most entitlement flags sit in
     * small allocations, so callers narrow with {@link #setRegionFilter} before
     * committing to a full sweep.
     */
    private static final long DEFAULT_MAX_REGION_BYTES = 512L * 1024 * 1024;

    private static final int MAX_RESULTS = 20_000;

    private long maxRegionBytes = DEFAULT_MAX_REGION_BYTES;
    private String regionFilter = null;
    private long regionFilterBase = 0L;

    private final Map<Long, Integer> previous = new LinkedHashMap<>();
    private final Map<Long, Integer> current = new LinkedHashMap<>();
    private boolean hasPrevious = false;

    /** Restricts scanning to mappings whose path contains {@code needle}. */
    public void setRegionFilter(String needle) {
        this.regionFilter = (needle == null || needle.isEmpty()) ? null : needle;
        this.regionFilterBase = 0L;
    }

    public void setMaxRegionBytes(long bytes) { this.maxRegionBytes = bytes; }

    /** Resets narrowing state so the next scan starts a fresh search. */
    public void reset() {
        previous.clear();
        current.clear();
        hasPrevious = false;
    }

    public int resultCount() { return current.size(); }

    public List<Long> resultAddresses(int limit) {
        List<Long> out = new ArrayList<>();
        for (Long address : current.keySet()) {
            if (out.size() >= limit) break;
            out.add(address);
        }
        return out;
    }

    public int valueAt(long address) {
        Integer v = current.get(address);
        if (v != null) return v;
        byte[] buf = new byte[4];
        if (read(address, buf) == 4) {
            return littleEndianInt(buf);
        }
        return 0;
    }

    /**
     * Runs a scan. The first call ignores {@code mode} and seeds the baseline;
     * later calls apply {@code mode} to narrow the previous result set.
     */
    public int scan(Mode mode, int searchValue) {
        if (!hasPrevious) {
            collectAll(searchValue);
            hasPrevious = true;
            return current.size();
        }

        Map<Long, Integer> next = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : previous.entrySet()) {
            long address = entry.getKey();
            int oldValue = entry.getValue();
            byte[] buf = new byte[4];
            if (read(address, buf) != 4) continue;
            int newValue = littleEndianInt(buf);
            boolean keep;
            switch (mode) {
                case EXACT:     keep = newValue == searchValue; break;
                case INCREASED: keep = newValue > oldValue; break;
                case DECREASED: keep = newValue < oldValue; break;
                case UNCHANGED: keep = newValue == oldValue; break;
                default:        keep = false;
            }
            if (keep && next.size() < MAX_RESULTS) {
                next.put(address, newValue);
            }
        }

        previous.clear();
        previous.putAll(next);
        current.clear();
        current.putAll(next);
        return current.size();
    }

    private void collectAll(int searchValue) {
        previous.clear();
        current.clear();
        for (Region region : parseMaps()) {
            if (region.size() > maxRegionBytes) continue;
            // Word-align within the region so 4-byte reads stay on word boundaries.
            long start = (region.start + 3L) & ~3L;
            long end = region.end & ~3L;
            long address = start;
            while (address + 4 <= end && current.size() < MAX_RESULTS) {
                byte[] buf = new byte[4];
                if (read(address, buf) != 4) {
                    // Unreadable page inside a mapped region: skip past it rather
                    // than stalling a byte at a time across a hole.
                    address += 0x1000;
                    continue;
                }
                int value = littleEndianInt(buf);
                if (value == searchValue) {
                    current.put(address, value);
                    previous.put(address, value);
                }
                address += 4;
            }
        }
    }

    /** Writes an int32 at {@code address}. Returns true when the write landed. */
    public boolean writeInt(long address, int value) {
        byte[] buf = new byte[4];
        buf[0] = (byte) (value & 0xff);
        buf[1] = (byte) ((value >>> 8) & 0xff);
        buf[2] = (byte) ((value >>> 16) & 0xff);
        buf[3] = (byte) ((value >>> 24) & 0xff);
        if (write(address, buf) != 4) return false;
        current.put(address, value);
        return true;
    }

    /** Human-readable name for a result address, used by the overlay panel. */
    public String describe(long address) {
        String path = mappingPath(address);
        if (path == null || path.isEmpty()) return String.format("0x%X [anon]", address);
        return String.format("0x%X %s", address, path);
    }

    private String mappingPath(long address) {
        try (java.io.BufferedReader reader =
                     new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 6) continue;
                String[] range = parts[0].split("-");
                if (range.length != 2) continue;
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                if (address < start || address >= end) continue;
                String path = parts[5];
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        } catch (Exception ignored) {
            // /proc/self/maps is not always readable; description is best-effort.
        }
        return null;
    }

    /** Parses /proc/self/maps into readable, writable, private mappings. */
    private List<Region> parseMaps() {
        List<Region> out = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5) continue;
                String perms = parts[1];
                if (perms.length() < 3) continue;
                // Private, writable, readable. Anonymous and file-backed both allowed.
                if (perms.charAt(0) != 'r' || perms.charAt(1) != 'w' || perms.charAt(2) != 'p') continue;
                String[] range = parts[0].split("-");
                if (range.length != 2) continue;
                long start = Long.parseUnsignedLong(range[0], 16);
                long end = Long.parseUnsignedLong(range[1], 16);
                if (end <= start) continue;
                String name = parts.length > 5 ? parts[5] : "";
                if (regionFilter != null && !name.contains(regionFilter)) continue;
                if (regionFilter != null && !regionFilterBaseIn(start, end)) continue;
                out.add(new Region(start, end));
            }
        } catch (Exception error) {
            android.util.Log.w("UnipatchMemScan", "/proc/self/maps unreadable: " + error);
        }
        return out;
    }

    private boolean regionFilterBaseIn(long start, long end) {
        // Filter by name only; a numeric base filter is applied by callers through
        // setRegionFilter with a name substring, so this is a no-op placeholder kept
        // for the case where a caller supplies both.
        return regionFilterBase == 0L || (start <= regionFilterBase && regionFilterBase < end);
    }

    private int read(long address, byte[] buf) {
        try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "r")) {
            mem.seek(address);
            mem.readFully(buf);
            return buf.length;
        } catch (Exception error) {
            return -1;
        }
    }

    private int write(long address, byte[] buf) {
        try (RandomAccessFile mem = new RandomAccessFile(new File("/proc/self/mem"), "rw")) {
            mem.seek(address);
            mem.write(buf);
            return buf.length;
        } catch (Exception error) {
            return -1;
        }
    }

    private static int littleEndianInt(byte[] b) {
        return (b[0] & 0xff)
                | ((b[1] & 0xff) << 8)
                | ((b[2] & 0xff) << 16)
                | ((b[3] & 0xff) << 24);
    }
}
