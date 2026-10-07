package sibarum.dxgi;

import dev.supirvast.ffi.NativeException;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * The D3D12 side of one Vulkan device: a D3D12 device on the same GPU (found by adapter LUID), a direct queue that
 * copies and presents, and one shared fence that Vulkan sees as a timeline semaphore.
 *
 * <p>Every value either API waits on or signals comes from {@link #nextValue()}, so the two count on one line and a
 * wait for a value means "everything submitted before it, on either side". One context serves every window of an
 * application. Not thread-safe: it lives on the thread that draws, like the Vulkan queue it shares a GPU with.
 */
public final class DxgiContext implements AutoCloseable {

    /** What the {@link VulkanDevice} must be made with: these extensions, and the timeline semaphore feature. */
    public static final List<String> REQUIRED_DEVICE_EXTENSIONS = VkInterop.REQUIRED_DEVICE_EXTENSIONS;

    /** {@code -Ddxgi.debug=true}: the D3D12 debug layer and a debug DXGI factory. */
    public static final boolean DEBUG = Boolean.getBoolean("dxgi.debug");

    /** How long a CPU wait on the fence may take before the device is presumed gone. */
    private static final int WAIT_MILLIS = 5_000;

    private final VkInterop interop;
    private final MemorySegment factory;
    private final MemorySegment adapter;
    private final MemorySegment device;
    private final MemorySegment queue;
    private final MemorySegment fence;
    private final MemorySegment event;
    private final long timeline;
    private long value;
    private boolean closed;

    private DxgiContext(VkInterop interop, MemorySegment factory, MemorySegment adapter, MemorySegment device,
                        MemorySegment queue, MemorySegment fence, MemorySegment event, long timeline) {
        this.interop = interop;
        this.factory = factory;
        this.adapter = adapter;
        this.device = device;
        this.queue = queue;
        this.fence = fence;
        this.event = event;
        this.timeline = timeline;
    }

    /**
     * The D3D12 side of {@code vulkan}, which must have been made with {@link #REQUIRED_DEVICE_EXTENSIONS} and
     * timeline semaphores. Fails if the driver reports no adapter LUID for the device.
     */
    public static DxgiContext create(VulkanInstance instance, VulkanDevice vulkan) {
        VkInterop.requireExtensions(vulkan);
        long luid = instance.adapterLuid(vulkan.physicalDevice()).orElseThrow(() ->
                new NativeException("the Vulkan device reports no adapter LUID, so DXGI cannot find the same GPU"));
        if (DEBUG && !D3d12.enableDebugLayer()) {
            System.err.println("dxgi-bridge: -Ddxgi.debug asked for the D3D12 debug layer, which is not installed");
        }
        MemorySegment factory = MemorySegment.NULL;
        MemorySegment adapter = MemorySegment.NULL;
        MemorySegment device = MemorySegment.NULL;
        MemorySegment queue = MemorySegment.NULL;
        MemorySegment fence = MemorySegment.NULL;
        MemorySegment event = MemorySegment.NULL;
        VkInterop interop = new VkInterop(vulkan);
        try {
            factory = Dxgi.createFactory(DEBUG);
            adapter = Dxgi.adapterByLuid(factory, luid);
            device = D3d12.createDevice(adapter);
            queue = D3d12.createCommandQueue(device);
            fence = D3d12.createFence(device, 0L, D3d12.FENCE_FLAG_SHARED);
            event = Win32.createEvent();
            MemorySegment handle = D3d12.createSharedHandle(device, fence);
            long timeline;
            try {
                timeline = interop.importTimelineSemaphore(handle);
            } finally {
                Win32.close(handle);
            }
            return new DxgiContext(interop, factory, adapter, device, queue, fence, event, timeline);
        } catch (RuntimeException e) {
            Win32.close(event);
            Com.release(fence);
            Com.release(queue);
            Com.release(device);
            Com.release(adapter);
            Com.release(factory);
            throw e;
        }
    }

    /** The next value on the shared fence; nothing else hands one out. */
    public long nextValue() {
        return ++value;
    }

    /** The shared fence as a Vulkan timeline semaphore — wait on it and signal it with values from {@link #nextValue()}. */
    public long timelineSemaphore() {
        return timeline;
    }

    /** Block until the shared fence reaches {@code target}, whichever API signals it. */
    public void waitForValue(long target) {
        if (D3d12.completedValue(fence) >= target) {
            return;
        }
        D3d12.setEventOnCompletion(fence, target, event);
        int result = Win32.waitFor(event, WAIT_MILLIS);
        if (result != Win32.WAIT_OBJECT_0 && D3d12.completedValue(fence) < target) {
            int reason = D3d12.deviceRemovedReason(device);
            throw new NativeException("the shared fence did not reach " + target + " in " + WAIT_MILLIS
                    + " ms (device removed reason 0x" + Integer.toHexString(reason) + ")");
        }
    }

    /** Signal a fresh value on the D3D12 queue and wait for it: the queue has finished everything it was given. */
    public void idle() {
        long v = nextValue();
        D3d12.signal(queue, fence, v);
        waitForValue(v);
    }

    VkInterop interop() {
        return interop;
    }

    MemorySegment factory() {
        return factory;
    }

    MemorySegment device() {
        return device;
    }

    MemorySegment queue() {
        return queue;
    }

    MemorySegment fence() {
        return fence;
    }

    /** Release everything; the caller has already closed the swapchains made from this, and idled Vulkan. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            idle();
        } finally {
            interop.destroySemaphore(timeline);
            Win32.close(event);
            Com.release(fence);
            Com.release(queue);
            Com.release(device);
            Com.release(adapter);
            Com.release(factory);
        }
    }
}
