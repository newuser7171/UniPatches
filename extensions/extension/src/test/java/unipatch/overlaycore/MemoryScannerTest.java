package unipatch.overlaycore;

import org.junit.Test;
import static org.junit.Assert.*;

public class MemoryScannerTest {
    @Test public void mapsPermissionsUseFourthPrivateFlag() {
        assertTrue(MemoryScanner.isReadableWritablePrivate("rw-p"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("r--p"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("rw-s"));
        assertFalse(MemoryScanner.isReadableWritablePrivate("r-xp"));
    }
}
