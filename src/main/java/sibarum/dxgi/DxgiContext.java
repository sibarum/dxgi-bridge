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

    /**
     * {@code -Ddxgi.share=image|buffer}: what Vulkan hands D3D12 a frame through, overriding {@link #sharesBuffer}'s
     * choice by driver. For measuring one path on a driver that would take the other.
     */
    public static final String SHARE = System.getProperty("dxgi.share", "");

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
    private final boolean sharesBuffer;
    private long value;
    private boolean closed;

    private DxgiContext(VkInterop interop, MemorySegment factory, MemorySegment adapter, MemorySegment device,
                        MemorySegment queue, MemorySegment fence, MemorySegment event, long timeline,
                        boolean sharesBuffer) {
        this.sharesBuffer = sharesBuffer;
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
     * timeline semaphores. Fails if the driver reports no adapter LUID for the device. Shares a buffer rather than
     * an image when the driver needs it ({@link #needsBuffer}) or {@link #SHARE} says so.
     */
    public static DxgiContext create(VulkanInstance instance, VulkanDevice vulkan) {
        boolean buffer = SHARE.equals("buffer") || (!SHARE.equals("image") && needsBuffer(instance, vulkan));
        return create(instance, vulkan, buffer);
    }

    /**
     * Whether {@code vulkan}'s driver cannot draw into an image shared with D3D12, so a frame must reach D3D12
     * through a buffer: true on Mesa's dzn (Vulkan over D3D12). dzn makes the exported image a simultaneous-access
     * texture, then translates a render pass's layout transitions on it into D3D12 barrier layouts such a texture may
     * not have ({@code RENDER_TARGET}, or {@code DIRECT_QUEUE_COMMON} when drawn in {@code GENERAL}); D3D12 refuses
     * them (debug layer #1334, {@code INCOMPATIBLE_BARRIER_LAYOUT}), the command list will not close, and the first
     * frame's {@code vkEndCommandBuffer} fails with -1. A buffer has no layout. Seen 2026-10-17 with the
     * Compatibility Pack's dzn 1.2608.3.0, on WARP and on an RTX 5070 Ti.
     */
    public static boolean needsBuffer(VulkanInstance instance, VulkanDevice vulkan) {
        return VkInterop.driverId(instance, vulkan.physicalDevice()) == VkInterop.DRIVER_ID_MESA_DOZEN;
    }

    /**
     * As {@link #create(VulkanInstance, VulkanDevice)}, with the choice made: {@code sharesBuffer} hands each frame to
     * D3D12 through a shared buffer that Vulkan copies the drawn image into, instead of drawing into a shared image.
     */
    public static DxgiContext create(VulkanInstance instance, VulkanDevice vulkan, boolean sharesBuffer) {
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
            return new DxgiContext(interop, factory, adapter, device, queue, fence, event, timeline, sharesBuffer);
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

    /**
     * Whether a frame reaches D3D12 through a shared buffer (Vulkan draws into a private image and copies it there)
     * rather than by drawing straight into a shared image. See {@link DxgiSwapchain#recordCopyOut}.
     */
    public boolean sharesBuffer() {
        return sharesBuffer;
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
