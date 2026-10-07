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
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * The part of {@code d3d12.dll} a copy-and-present queue needs: a device, a direct queue, command allocators and
 * lists, a shared fence, a shared texture, transitions and a region copy.
 *
 * <p>Every slot number is from the C {@code *Vtbl} struct of its interface in the Windows SDK 10.0.26100.0
 * {@code um/d3d12.h} (and {@code um/d3d12sdklayers.h} for {@code ID3D12Debug}); constants are from the same files.
 */
public final class D3d12 {

    private static final SymbolLookup LIB = Ffi.library("d3d12");

    public static final MemorySegment IID_DEVICE = Guid.of("189819f1-1db6-4b57-be54-1821339b85f7");
    public static final MemorySegment IID_COMMAND_QUEUE = Guid.of("0ec870a6-5d7e-4c22-8cfc-5baae07616ed");
    public static final MemorySegment IID_COMMAND_ALLOCATOR = Guid.of("6102dee4-af59-4b09-b999-b44d73f09b24");
    public static final MemorySegment IID_GRAPHICS_COMMAND_LIST = Guid.of("5b160d0f-ac1b-4185-8ba8-b3ae42a5a455");
    public static final MemorySegment IID_FENCE = Guid.of("0a753dcf-c4d8-4b91-adf6-be5a60d95a76");
    public static final MemorySegment IID_RESOURCE = Guid.of("696442be-a72e-4059-bc79-5b5c98040fad");
    private static final MemorySegment IID_DEBUG = Guid.of("344488b7-6846-474b-b989-f027448245e0");

    public static final int FEATURE_LEVEL_11_0 = 0xb000;
    public static final int COMMAND_LIST_TYPE_DIRECT = 0;
    public static final int HEAP_TYPE_DEFAULT = 1;
    public static final int HEAP_FLAG_SHARED = 0x1;
    public static final int RESOURCE_DIMENSION_TEXTURE2D = 3;
    public static final int RESOURCE_FLAG_ALLOW_RENDER_TARGET = 0x1;
    public static final int RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS = 0x20;
    public static final int RESOURCE_STATE_COMMON = 0;
    public static final int RESOURCE_STATE_PRESENT = 0;
    public static final int RESOURCE_STATE_COPY_DEST = 0x400;
    public static final int RESOURCE_STATE_COPY_SOURCE = 0x800;
    public static final int FENCE_FLAG_SHARED = 0x1;
    /** {@code GENERIC_ALL}, the access a shared handle is made with. */
    public static final int GENERIC_ALL = 0x10000000;

    private static final int BARRIER_TYPE_TRANSITION = 0;
    private static final int BARRIER_ALL_SUBRESOURCES = 0xffffffff;
    private static final int COPY_TYPE_SUBRESOURCE_INDEX = 0;

    // ID3D12DeviceVtbl
    private static final int DEVICE_CREATE_COMMAND_QUEUE = 8;
    private static final int DEVICE_CREATE_COMMAND_ALLOCATOR = 9;
    private static final int DEVICE_CREATE_COMMAND_LIST = 12;
    private static final int DEVICE_CREATE_COMMITTED_RESOURCE = 27;
    private static final int DEVICE_CREATE_SHARED_HANDLE = 31;
    private static final int DEVICE_CREATE_FENCE = 36;
    private static final int DEVICE_GET_DEVICE_REMOVED_REASON = 37;
    // ID3D12CommandQueueVtbl
    private static final int QUEUE_EXECUTE_COMMAND_LISTS = 10;
    private static final int QUEUE_SIGNAL = 14;
    private static final int QUEUE_WAIT = 15;
    // ID3D12CommandAllocatorVtbl
    private static final int ALLOCATOR_RESET = 8;
    // ID3D12GraphicsCommandListVtbl
    private static final int LIST_CLOSE = 9;
    private static final int LIST_RESET = 10;
    private static final int LIST_COPY_TEXTURE_REGION = 16;
    private static final int LIST_RESOURCE_BARRIER = 26;
    // ID3D12FenceVtbl
    private static final int FENCE_GET_COMPLETED_VALUE = 8;
    private static final int FENCE_SET_EVENT_ON_COMPLETION = 9;
    // ID3D12DebugVtbl
    private static final int DEBUG_ENABLE_DEBUG_LAYER = 3;

    /** {@code D3D12_COMMAND_QUEUE_DESC}. */
    private static final GroupLayout COMMAND_QUEUE_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("Type"), JAVA_INT.withName("Priority"), JAVA_INT.withName("Flags"),
            JAVA_INT.withName("NodeMask")).withName("D3D12_COMMAND_QUEUE_DESC");

    /** {@code D3D12_HEAP_PROPERTIES}. */
    private static final GroupLayout HEAP_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("Type"), JAVA_INT.withName("CPUPageProperty"), JAVA_INT.withName("MemoryPoolPreference"),
            JAVA_INT.withName("CreationNodeMask"), JAVA_INT.withName("VisibleNodeMask")).withName("D3D12_HEAP_PROPERTIES");

    /** {@code D3D12_RESOURCE_DESC}: 56 bytes, the {@code UINT64}s aligned to 8. */
    private static final GroupLayout RESOURCE_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("Dimension"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("Alignment"), JAVA_LONG.withName("Width"),
            JAVA_INT.withName("Height"), JAVA_SHORT.withName("DepthOrArraySize"), JAVA_SHORT.withName("MipLevels"),
            JAVA_INT.withName("Format"), JAVA_INT.withName("SampleCount"), JAVA_INT.withName("SampleQuality"),
            JAVA_INT.withName("Layout"), JAVA_INT.withName("Flags"), MemoryLayout.paddingLayout(4)
    ).withName("D3D12_RESOURCE_DESC");

    /** {@code D3D12_RESOURCE_BARRIER} holding its transition member: 32 bytes. */
    private static final GroupLayout RESOURCE_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("Type"), JAVA_INT.withName("Flags"),
            ADDRESS.withName("pResource"), JAVA_INT.withName("Subresource"),
            JAVA_INT.withName("StateBefore"), JAVA_INT.withName("StateAfter"), MemoryLayout.paddingLayout(4)
    ).withName("D3D12_RESOURCE_BARRIER");

    /**
     * {@code D3D12_TEXTURE_COPY_LOCATION} with its subresource-index member: 48 bytes, since the union's largest
     * member (a placed footprint, led by a {@code UINT64}) is 32 bytes at offset 16.
     */
    private static final GroupLayout TEXTURE_COPY_LOCATION = MemoryLayout.structLayout(
            ADDRESS.withName("pResource"), JAVA_INT.withName("Type"), MemoryLayout.paddingLayout(4),
            JAVA_INT.withName("SubresourceIndex"), MemoryLayout.paddingLayout(28)
    ).withName("D3D12_TEXTURE_COPY_LOCATION");

    /** {@code D3D12_BOX}. */
    private static final GroupLayout BOX = MemoryLayout.structLayout(
            JAVA_INT.withName("left"), JAVA_INT.withName("top"), JAVA_INT.withName("front"),
            JAVA_INT.withName("right"), JAVA_INT.withName("bottom"), JAVA_INT.withName("back")).withName("D3D12_BOX");

    private static final MethodHandle D3D12CreateDevice = Ffi.downcall(LIB, "D3D12CreateDevice",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle D3D12GetDebugInterface = Ffi.downcall(LIB, "D3D12GetDebugInterface",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private static final FunctionDescriptor HR_THIS = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor VOID_THIS = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor HR_PTR_IID_OUT = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor HR_INT_IID_OUT = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CREATE_COMMAND_LIST = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CREATE_COMMITTED_RESOURCE = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CREATE_SHARED_HANDLE = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor CREATE_FENCE = FunctionDescriptor.of(JAVA_INT,
            ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor EXECUTE_COMMAND_LISTS = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor QUEUE_FENCE_VALUE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor LIST_RESET_DESC = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor RESOURCE_BARRIER_DESC = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor COPY_TEXTURE_REGION = FunctionDescriptor.ofVoid(
            ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor GET_COMPLETED_VALUE = FunctionDescriptor.of(JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor SET_EVENT_ON_COMPLETION = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS);

    private D3d12() {
    }

    // ---- creation ----

    /** {@code D3D12GetDebugInterface} + {@code EnableDebugLayer}; false when the layer is not installed. */
    public static boolean enableDebugLayer() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) D3D12GetDebugInterface.invokeExact(IID_DEBUG, out);
            if (hr < 0) {
                return false;
            }
            MemorySegment debug = out.get(ADDRESS, 0);
            Com.method(debug, DEBUG_ENABLE_DEBUG_LAYER, VOID_THIS).invokeExact(debug);
            Com.release(debug);
            return true;
        } catch (Throwable t) {
            throw NativeException.rethrow("D3D12GetDebugInterface", t);
        }
    }

    /** {@code D3D12CreateDevice} on {@code adapter} at feature level 11_0. */
    public static MemorySegment createDevice(MemorySegment adapter) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) D3D12CreateDevice.invokeExact(adapter, FEATURE_LEVEL_11_0, IID_DEVICE, out);
            Com.check(hr, "D3D12CreateDevice");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("D3D12CreateDevice", t);
        }
    }

    /** A direct command queue at normal priority. */
    public static MemorySegment createCommandQueue(MemorySegment device) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment desc = temp.allocate(COMMAND_QUEUE_DESC);   // zeroed: DIRECT, NORMAL, NONE, node 0
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_COMMAND_QUEUE, HR_PTR_IID_OUT)
                    .invokeExact(device, desc, IID_COMMAND_QUEUE, out);
            Com.check(hr, "ID3D12Device::CreateCommandQueue");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateCommandQueue", t);
        }
    }

    /** A direct command allocator. */
    public static MemorySegment createCommandAllocator(MemorySegment device) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_COMMAND_ALLOCATOR, HR_INT_IID_OUT)
                    .invokeExact(device, COMMAND_LIST_TYPE_DIRECT, IID_COMMAND_ALLOCATOR, out);
            Com.check(hr, "ID3D12Device::CreateCommandAllocator");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateCommandAllocator", t);
        }
    }

    /** A direct graphics command list over {@code allocator}, returned <em>closed</em> so every use starts with a reset. */
    public static MemorySegment createCommandList(MemorySegment device, MemorySegment allocator) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_COMMAND_LIST, CREATE_COMMAND_LIST)
                    .invokeExact(device, 0, COMMAND_LIST_TYPE_DIRECT, allocator, MemorySegment.NULL,
                            IID_GRAPHICS_COMMAND_LIST, out);
            Com.check(hr, "ID3D12Device::CreateCommandList");
            MemorySegment list = out.get(ADDRESS, 0);
            close(list);
            return list;
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateCommandList", t);
        }
    }

    /**
     * A 2D texture on a shared default heap that can be a render target and be used by two queues at once — the
     * image Vulkan draws into and this queue copies out of. Created in the {@code COMMON} state; a simultaneous-
     * access resource is promoted to a copy source implicitly and decays back, so it needs no barriers here.
     */
    public static MemorySegment createSharedTexture(MemorySegment device, int width, int height, int dxgiFormat) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment heap = temp.allocate(HEAP_PROPERTIES);
            heap.set(JAVA_INT, HEAP_PROPERTIES.byteOffset(MemoryLayout.PathElement.groupElement("Type")),
                    HEAP_TYPE_DEFAULT);
            MemorySegment desc = temp.allocate(RESOURCE_DESC);
            set(desc, RESOURCE_DESC, "Dimension", RESOURCE_DIMENSION_TEXTURE2D);
            desc.set(JAVA_LONG, off(RESOURCE_DESC, "Width"), width);
            set(desc, RESOURCE_DESC, "Height", height);
            desc.set(JAVA_SHORT, off(RESOURCE_DESC, "DepthOrArraySize"), (short) 1);
            desc.set(JAVA_SHORT, off(RESOURCE_DESC, "MipLevels"), (short) 1);
            set(desc, RESOURCE_DESC, "Format", dxgiFormat);
            set(desc, RESOURCE_DESC, "SampleCount", 1);
            set(desc, RESOURCE_DESC, "Flags",
                    RESOURCE_FLAG_ALLOW_RENDER_TARGET | RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS);
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_COMMITTED_RESOURCE, CREATE_COMMITTED_RESOURCE)
                    .invokeExact(device, heap, HEAP_FLAG_SHARED, desc, RESOURCE_STATE_COMMON, MemorySegment.NULL,
                            IID_RESOURCE, out);
            Com.check(hr, "ID3D12Device::CreateCommittedResource");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateCommittedResource", t);
        }
    }

    /** A fence starting at {@code initial}. */
    public static MemorySegment createFence(MemorySegment device, long initial, int flags) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_FENCE, CREATE_FENCE)
                    .invokeExact(device, initial, flags, IID_FENCE, out);
            Com.check(hr, "ID3D12Device::CreateFence");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateFence", t);
        }
    }

    /** An NT handle to a shared {@code object} (a fence or a shared-heap resource). The caller closes it. */
    public static MemorySegment createSharedHandle(MemorySegment device, MemorySegment object) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(ADDRESS);
            int hr = (int) Com.method(device, DEVICE_CREATE_SHARED_HANDLE, CREATE_SHARED_HANDLE)
                    .invokeExact(device, object, MemorySegment.NULL, GENERIC_ALL, MemorySegment.NULL, out);
            Com.check(hr, "ID3D12Device::CreateSharedHandle");
            return out.get(ADDRESS, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::CreateSharedHandle", t);
        }
    }

    /** {@code GetDeviceRemovedReason}: {@code S_OK} while the device is healthy. */
    public static int deviceRemovedReason(MemorySegment device) {
        try {
            return (int) Com.method(device, DEVICE_GET_DEVICE_REMOVED_REASON, HR_THIS).invokeExact(device);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Device::GetDeviceRemovedReason", t);
        }
    }

    // ---- the queue ----

    /** Submit one closed command list. */
    public static void executeOne(MemorySegment queue, MemorySegment list) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment lists = temp.allocate(ADDRESS);
            lists.set(ADDRESS, 0, list);
            Com.method(queue, QUEUE_EXECUTE_COMMAND_LISTS, EXECUTE_COMMAND_LISTS).invokeExact(queue, 1, lists);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12CommandQueue::ExecuteCommandLists", t);
        }
    }

    /** The queue sets {@code fence} to {@code value} once everything before it has run. */
    public static void signal(MemorySegment queue, MemorySegment fence, long value) {
        try {
            int hr = (int) Com.method(queue, QUEUE_SIGNAL, QUEUE_FENCE_VALUE).invokeExact(queue, fence, value);
            Com.check(hr, "ID3D12CommandQueue::Signal");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12CommandQueue::Signal", t);
        }
    }

    /** The queue runs nothing after this until {@code fence} reaches {@code value}. */
    public static void waitFor(MemorySegment queue, MemorySegment fence, long value) {
        try {
            int hr = (int) Com.method(queue, QUEUE_WAIT, QUEUE_FENCE_VALUE).invokeExact(queue, fence, value);
            Com.check(hr, "ID3D12CommandQueue::Wait");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12CommandQueue::Wait", t);
        }
    }

    // ---- recording ----

    /** Reset an allocator whose lists the GPU has finished with. */
    public static void resetAllocator(MemorySegment allocator) {
        try {
            int hr = (int) Com.method(allocator, ALLOCATOR_RESET, HR_THIS).invokeExact(allocator);
            Com.check(hr, "ID3D12CommandAllocator::Reset");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12CommandAllocator::Reset", t);
        }
    }

    /** Reopen a closed list for recording into {@code allocator}. */
    public static void reset(MemorySegment list, MemorySegment allocator) {
        try {
            int hr = (int) Com.method(list, LIST_RESET, LIST_RESET_DESC).invokeExact(list, allocator,
                    MemorySegment.NULL);
            Com.check(hr, "ID3D12GraphicsCommandList::Reset");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12GraphicsCommandList::Reset", t);
        }
    }

    /** Close a list so it can be executed. */
    public static void close(MemorySegment list) {
        try {
            int hr = (int) Com.method(list, LIST_CLOSE, HR_THIS).invokeExact(list);
            Com.check(hr, "ID3D12GraphicsCommandList::Close");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12GraphicsCommandList::Close", t);
        }
    }

    /** Record a transition of every subresource of {@code resource}. */
    public static void transition(MemorySegment list, MemorySegment resource, int before, int after) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment barrier = temp.allocate(RESOURCE_BARRIER);
            set(barrier, RESOURCE_BARRIER, "Type", BARRIER_TYPE_TRANSITION);
            barrier.set(ADDRESS, off(RESOURCE_BARRIER, "pResource"), resource);
            set(barrier, RESOURCE_BARRIER, "Subresource", BARRIER_ALL_SUBRESOURCES);
            set(barrier, RESOURCE_BARRIER, "StateBefore", before);
            set(barrier, RESOURCE_BARRIER, "StateAfter", after);
            Com.method(list, LIST_RESOURCE_BARRIER, RESOURCE_BARRIER_DESC).invokeExact(list, 1, barrier);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12GraphicsCommandList::ResourceBarrier", t);
        }
    }

    /** Record a copy of the top-left {@code width}×{@code height} of {@code source} to the same place in {@code dest}. */
    public static void copyCorner(MemorySegment list, MemorySegment dest, MemorySegment source, int width, int height) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment dst = temp.allocate(TEXTURE_COPY_LOCATION);
            dst.set(ADDRESS, 0, dest);
            set(dst, TEXTURE_COPY_LOCATION, "Type", COPY_TYPE_SUBRESOURCE_INDEX);
            MemorySegment src = temp.allocate(TEXTURE_COPY_LOCATION);
            src.set(ADDRESS, 0, source);
            set(src, TEXTURE_COPY_LOCATION, "Type", COPY_TYPE_SUBRESOURCE_INDEX);
            MemorySegment box = temp.allocate(BOX);
            set(box, BOX, "right", width);
            set(box, BOX, "bottom", height);
            set(box, BOX, "back", 1);
            Com.method(list, LIST_COPY_TEXTURE_REGION, COPY_TEXTURE_REGION).invokeExact(list, dst, 0, 0, 0, src, box);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12GraphicsCommandList::CopyTextureRegion", t);
        }
    }

    // ---- the fence ----

    /** The highest value the fence has reached. */
    public static long completedValue(MemorySegment fence) {
        try {
            return (long) Com.method(fence, FENCE_GET_COMPLETED_VALUE, GET_COMPLETED_VALUE).invokeExact(fence);
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Fence::GetCompletedValue", t);
        }
    }

    /** Signal {@code event} when the fence reaches {@code value}. */
    public static void setEventOnCompletion(MemorySegment fence, long value, MemorySegment event) {
        try {
            int hr = (int) Com.method(fence, FENCE_SET_EVENT_ON_COMPLETION, SET_EVENT_ON_COMPLETION)
                    .invokeExact(fence, value, event);
            Com.check(hr, "ID3D12Fence::SetEventOnCompletion");
        } catch (Throwable t) {
            throw NativeException.rethrow("ID3D12Fence::SetEventOnCompletion", t);
        }
    }

    private static long off(GroupLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static void set(MemorySegment s, GroupLayout layout, String field, int value) {
        s.set(JAVA_INT, off(layout, field), value);
    }
}
