package sibarum.dxgi;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Calling COM from Java: an interface pointer is a pointer to a pointer to a table of function pointers, and a
 * method is a slot in that table whose first argument is the interface pointer itself.
 *
 * <p>Slot numbers live in the binding classes as named constants, each copied from the Windows SDK's C
 * {@code *Vtbl} struct for its interface — the C declaration lists every inherited method in order, so the index
 * there is the index here. A wrong slot calls a different method with the wrong arguments, which usually crashes
 * the process rather than failing, so they are never typed from memory.
 *
 * <p>A downcall handle is made once per function address and cached: the same method of the same class is one
 * address however many objects share it.
 */
public final class Com {

    /** {@code S_OK}. Success HRESULTs are any non-negative value; {@link #check} accepts all of them. */
    public static final int S_OK = 0;

    private static final int SLOT_QUERY_INTERFACE = 0;
    private static final int SLOT_RELEASE = 2;

    private static final FunctionDescriptor QUERY_INTERFACE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor RELEASE = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private static final ConcurrentHashMap<Long, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    private Com() {
    }

    /** The downcall for slot {@code slot} of {@code self}'s vtable, typed as {@code descriptor}. */
    public static MethodHandle method(MemorySegment self, int slot, FunctionDescriptor descriptor) {
        if (self.equals(MemorySegment.NULL)) {
            throw new NativeException("COM call on a null interface pointer (slot " + slot + ")");
        }
        long pointer = ADDRESS.byteSize();
        MemorySegment vtable = self.reinterpret(pointer).get(ADDRESS, 0);
        MemorySegment function = vtable.reinterpret((slot + 1L) * pointer).get(ADDRESS, slot * pointer);
        return HANDLES.computeIfAbsent(function.address(), a -> Ffi.downcall(function, descriptor));
    }

    /** Raise unless {@code hr} is a success code (non-negative). */
    public static void check(int hr, String call) {
        if (hr < 0) {
            throw new NativeException(call + " failed: HRESULT 0x" + Integer.toHexString(hr));
        }
    }

    /** {@code IUnknown::Release}. A null pointer is ignored, so teardown can release whatever was made. */
    public static void release(MemorySegment self) {
        if (self == null || self.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            int ignored = (int) method(self, SLOT_RELEASE, RELEASE).invokeExact(self);
        } catch (Throwable t) {
            throw NativeException.rethrow("IUnknown::Release", t);
        }
    }

    /** {@code IUnknown::QueryInterface} for {@code iid}; the caller releases what it returns. */
    public static MemorySegment queryInterface(MemorySegment self, MemorySegment iid) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) method(self, SLOT_QUERY_INTERFACE, QUERY_INTERFACE).invokeExact(self, iid, out);
            check(hr, "IUnknown::QueryInterface");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("IUnknown::QueryInterface", t);
        }
    }
}
