package sibarum.dxgi;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** The three {@code kernel32} calls a fence and a frame-latency waitable need: an event, a wait, a close. */
public final class Win32 {

    private static final SymbolLookup LIB = Ffi.library("kernel32");

    /** {@code WAIT_OBJECT_0}: the object was signalled. */
    public static final int WAIT_OBJECT_0 = 0;
    /** {@code WAIT_TIMEOUT}. */
    public static final int WAIT_TIMEOUT = 0x102;

    private static final MethodHandle CreateEventW = Ffi.downcall(LIB, "CreateEventW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle WaitForSingleObjectEx = Ffi.downcall(LIB, "WaitForSingleObjectEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    private static final MethodHandle CloseHandle = Ffi.downcall(LIB, "CloseHandle",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private Win32() {
    }

    /** An unnamed auto-reset event, initially unsignalled — what {@code SetEventOnCompletion} signals. */
    public static MemorySegment createEvent() {
        try {
            MemorySegment event = (MemorySegment) CreateEventW.invokeExact(MemorySegment.NULL, 0, 0,
                    MemorySegment.NULL);
            if (event.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateEventW failed");
            }
            return event;
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateEventW", t);
        }
    }

    /** {@code WaitForSingleObjectEx(handle, millis, alertable=TRUE)}; returns the wait result. */
    public static int waitFor(MemorySegment handle, int millis) {
        try {
            return (int) WaitForSingleObjectEx.invokeExact(handle, millis, 1);
        } catch (Throwable t) {
            throw NativeException.rethrow("WaitForSingleObjectEx", t);
        }
    }

    /** {@code CloseHandle}; a null handle is ignored. */
    public static void close(MemorySegment handle) {
        if (handle == null || handle.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            int ignored = (int) CloseHandle.invokeExact(handle);
        } catch (Throwable t) {
            throw NativeException.rethrow("CloseHandle", t);
        }
    }
}
