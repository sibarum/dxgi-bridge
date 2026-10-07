package sibarum.dxgi;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The part of {@code dxgi.dll} a flip-model window swapchain needs: a factory, the adapter with a given LUID, the
 * swapchain and its present, resize and frame-latency waitable.
 *
 * <p>Slot numbers are from the C {@code IDXGIFactory4Vtbl} and {@code IDXGISwapChain3Vtbl} structs in the Windows
 * SDK 10.0.26100.0 {@code shared/dxgi1_4.h}; constants from the {@code shared/dxgi*.h} headers of the same SDK.
 */
public final class Dxgi {

    private static final SymbolLookup LIB = Ffi.library("dxgi");

    public static final MemorySegment IID_FACTORY4 = Guid.of("1bc6ea02-ef36-464f-bf0c-21ca39e5168a");
    public static final MemorySegment IID_ADAPTER = Guid.of("2411e7e1-12ac-4ccf-bd14-9798e8534dc0");
    public static final MemorySegment IID_SWAPCHAIN3 = Guid.of("94d99bdb-f1f8-4ab0-b236-7da0170edab1");

    public static final int FORMAT_UNKNOWN = 0;
    public static final int FORMAT_B8G8R8A8_UNORM = 87;
    public static final int USAGE_RENDER_TARGET_OUTPUT = 0x20;
    public static final int SCALING_STRETCH = 0;
    public static final int SCALING_NONE = 1;
    public static final int SWAP_EFFECT_FLIP_DISCARD = 4;
    public static final int ALPHA_MODE_IGNORE = 3;
    public static final int SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT = 64;
    public static final int MWA_NO_WINDOW_CHANGES = 1;
    public static final int MWA_NO_ALT_ENTER = 2;
    public static final int CREATE_FACTORY_DEBUG = 1;
    /** {@code DXGI_STATUS_OCCLUDED}: a success code — the window is not visible, and the present did nothing. */
    public static final int STATUS_OCCLUDED = 0x087A0001;
    public static final int ERROR_DEVICE_REMOVED = 0x887A0005;
    public static final int ERROR_DEVICE_RESET = 0x887A0007;

    // IDXGIFactory4Vtbl
    private static final int FACTORY_MAKE_WINDOW_ASSOCIATION = 8;
    private static final int FACTORY_CREATE_SWAP_CHAIN_FOR_HWND = 15;
    private static final int FACTORY_ENUM_ADAPTER_BY_LUID = 26;
    // IDXGISwapChain3Vtbl
    private static final int SWAPCHAIN_PRESENT = 8;
    private static final int SWAPCHAIN_GET_BUFFER = 9;
    private static final int SWAPCHAIN_RESIZE_BUFFERS = 13;
    private static final int SWAPCHAIN_SET_MAXIMUM_FRAME_LATENCY = 31;
    private static final int SWAPCHAIN_GET_FRAME_LATENCY_WAITABLE_OBJECT = 33;
    private static final int SWAPCHAIN_GET_CURRENT_BACK_BUFFER_INDEX = 36;

    /** {@code DXGI_SWAP_CHAIN_DESC1}: twelve 32-bit fields. */
    private static final GroupLayout SWAP_CHAIN_DESC1 = MemoryLayout.structLayout(
            JAVA_INT.withName("Width"), JAVA_INT.withName("Height"), JAVA_INT.withName("Format"),
            JAVA_INT.withName("Stereo"), JAVA_INT.withName("SampleCount"), JAVA_INT.withName("SampleQuality"),
            JAVA_INT.withName("BufferUsage"), JAVA_INT.withName("BufferCount"), JAVA_INT.withName("Scaling"),
            JAVA_INT.withName("SwapEffect"), JAVA_INT.withName("AlphaMode"), JAVA_INT.withName("Flags")
    ).withName("DXGI_SWAP_CHAIN_DESC1");

    private static final MethodHandle CreateDXGIFactory2 = Ffi.downcall(LIB, "CreateDXGIFactory2",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /** A {@code LUID} is an 8-byte struct, which the x64 calling convention passes in one integer register. */
    private static final FunctionDescriptor ENUM_ADAPTER_BY_LUID = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CREATE_SWAP_CHAIN_FOR_HWND = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor MAKE_WINDOW_ASSOCIATION = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor PRESENT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor GET_BUFFER = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor RESIZE_BUFFERS = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor SET_MAXIMUM_FRAME_LATENCY = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT);
    private static final FunctionDescriptor GET_FRAME_LATENCY_WAITABLE_OBJECT = FunctionDescriptor.of(ADDRESS, ADDRESS);
    private static final FunctionDescriptor GET_CURRENT_BACK_BUFFER_INDEX = FunctionDescriptor.of(JAVA_INT, ADDRESS);

    private Dxgi() {
    }

    /** {@code CreateDXGIFactory2} for {@code IDXGIFactory4}. */
    public static MemorySegment createFactory(boolean debug) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) CreateDXGIFactory2.invokeExact(debug ? CREATE_FACTORY_DEBUG : 0, IID_FACTORY4, out);
            Com.check(hr, "CreateDXGIFactory2");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateDXGIFactory2", t);
        }
    }

    /** The adapter whose LUID is {@code luid} — the same GPU a Vulkan device reports through its LUID. */
    public static MemorySegment adapterByLuid(MemorySegment factory, long luid) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(factory, FACTORY_ENUM_ADAPTER_BY_LUID, ENUM_ADAPTER_BY_LUID)
                    .invokeExact(factory, luid, IID_ADAPTER, out);
            Com.check(hr, "IDXGIFactory4::EnumAdapterByLuid");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGIFactory4::EnumAdapterByLuid", t);
        }
    }

    /**
     * A flip-model swapchain on {@code hwnd} presented by {@code queue} (for D3D12 the "device" argument is the
     * queue). Returns {@code IDXGISwapChain3}; the {@code IDXGISwapChain1} the call makes is released here.
     */
    public static MemorySegment createSwapChainForHwnd(MemorySegment factory, MemorySegment queue, MemorySegment hwnd,
                                                       int width, int height, int format, int bufferCount,
                                                       int scaling, int flags) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment desc = temp.allocate(SWAP_CHAIN_DESC1);
            set(desc, "Width", width);
            set(desc, "Height", height);
            set(desc, "Format", format);
            set(desc, "SampleCount", 1);
            set(desc, "BufferUsage", USAGE_RENDER_TARGET_OUTPUT);
            set(desc, "BufferCount", bufferCount);
            set(desc, "Scaling", scaling);
            set(desc, "SwapEffect", SWAP_EFFECT_FLIP_DISCARD);
            set(desc, "AlphaMode", ALPHA_MODE_IGNORE);
            set(desc, "Flags", flags);
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(factory, FACTORY_CREATE_SWAP_CHAIN_FOR_HWND, CREATE_SWAP_CHAIN_FOR_HWND)
                    .invokeExact(factory, queue, hwnd, desc, MemorySegment.NULL, MemorySegment.NULL, out);
            Com.check(hr, "IDXGIFactory2::CreateSwapChainForHwnd");
            MemorySegment swapchain1 = out.get(ADDRESS, 0);
            try {
                return Com.queryInterface(swapchain1, IID_SWAPCHAIN3);
            } finally {
                Com.release(swapchain1);
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGIFactory2::CreateSwapChainForHwnd", t);
        }
    }

    /** {@code MakeWindowAssociation}: which window messages DXGI may act on for {@code hwnd} (none, here). */
    public static void makeWindowAssociation(MemorySegment factory, MemorySegment hwnd, int flags) {
        try {
            int hr = (int) Com.method(factory, FACTORY_MAKE_WINDOW_ASSOCIATION, MAKE_WINDOW_ASSOCIATION)
                    .invokeExact(factory, hwnd, flags);
            Com.check(hr, "IDXGIFactory::MakeWindowAssociation");
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGIFactory::MakeWindowAssociation", t);
        }
    }

    /** {@code Present}; returns the HRESULT, since {@link #STATUS_OCCLUDED} and the device errors mean different things. */
    public static int present(MemorySegment swapchain, int syncInterval, int flags) {
        try {
            return (int) Com.method(swapchain, SWAPCHAIN_PRESENT, PRESENT).invokeExact(swapchain, syncInterval, flags);
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain::Present", t);
        }
    }

    /** Back buffer {@code index} as an {@code ID3D12Resource}; the caller releases it. */
    public static MemorySegment buffer(MemorySegment swapchain, int index) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(swapchain, SWAPCHAIN_GET_BUFFER, GET_BUFFER)
                    .invokeExact(swapchain, index, D3d12.IID_RESOURCE, out);
            Com.check(hr, "IDXGISwapChain::GetBuffer");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain::GetBuffer", t);
        }
    }

    /**
     * {@code ResizeBuffers}, keeping the count and format. Every reference to a back buffer must be released and
     * the queue idle on them first; {@code flags} must be the flags the swapchain was created with.
     */
    public static void resizeBuffers(MemorySegment swapchain, int width, int height, int flags) {
        try {
            int hr = (int) Com.method(swapchain, SWAPCHAIN_RESIZE_BUFFERS, RESIZE_BUFFERS)
                    .invokeExact(swapchain, 0, width, height, FORMAT_UNKNOWN, flags);
            Com.check(hr, "IDXGISwapChain::ResizeBuffers");
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain::ResizeBuffers", t);
        }
    }

    public static void setMaximumFrameLatency(MemorySegment swapchain, int frames) {
        try {
            int hr = (int) Com.method(swapchain, SWAPCHAIN_SET_MAXIMUM_FRAME_LATENCY, SET_MAXIMUM_FRAME_LATENCY)
                    .invokeExact(swapchain, frames);
            Com.check(hr, "IDXGISwapChain2::SetMaximumFrameLatency");
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain2::SetMaximumFrameLatency", t);
        }
    }

    /** The waitable that is signalled when the swapchain can take another frame; the caller closes it. */
    public static MemorySegment frameLatencyWaitable(MemorySegment swapchain) {
        try {
            return (MemorySegment) Com.method(swapchain, SWAPCHAIN_GET_FRAME_LATENCY_WAITABLE_OBJECT,
                    GET_FRAME_LATENCY_WAITABLE_OBJECT).invokeExact(swapchain);
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain2::GetFrameLatencyWaitableObject", t);
        }
    }

    public static int currentBackBufferIndex(MemorySegment swapchain) {
        try {
            return (int) Com.method(swapchain, SWAPCHAIN_GET_CURRENT_BACK_BUFFER_INDEX, GET_CURRENT_BACK_BUFFER_INDEX)
                    .invokeExact(swapchain);
        } catch (Throwable t) {
            throw NativeException.rethrow("IDXGISwapChain3::GetCurrentBackBufferIndex", t);
        }
    }

    private static void set(MemorySegment s, String field, int value) {
        s.set(JAVA_INT, SWAP_CHAIN_DESC1.byteOffset(MemoryLayout.PathElement.groupElement(field)), value);
    }
}
