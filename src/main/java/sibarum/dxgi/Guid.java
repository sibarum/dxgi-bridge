package sibarum.dxgi;

import dev.supirvast.ffi.Ffi;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * A COM {@code GUID} in native memory, made once from its registry form and kept for the life of the process — an
 * interface ID is passed by pointer ({@code REFIID}) to nearly every call here, so it is allocated in
 * {@link Ffi#GLOBAL} rather than per call.
 */
public final class Guid {

    private Guid() {
    }

    /**
     * {@code "189819f1-1db6-4b57-be54-1821339b85f7"} → the 16-byte struct. {@code Data1} is a little-endian
     * {@code uint32}, {@code Data2} and {@code Data3} little-endian {@code uint16}s, and the last 8 bytes are stored
     * in the order they are written.
     */
    public static MemorySegment of(String text) {
        String hex = text.replace("-", "");
        if (hex.length() != 32) {
            throw new IllegalArgumentException("not a GUID: " + text);
        }
        MemorySegment guid = Ffi.GLOBAL.allocate(16, 4);
        guid.set(JAVA_INT, 0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.set(JAVA_SHORT, 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.set(JAVA_SHORT, 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            guid.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
        }
        return guid;
    }
}
