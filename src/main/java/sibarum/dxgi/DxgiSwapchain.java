package sibarum.dxgi;

import dev.supirvast.ffi.NativeException;
import dev.supirvast.vulkan.Vk;

import java.lang.foreign.MemorySegment;

/**
 * A flip-model DXGI swapchain on one window, and the shared image Vulkan draws that window into.
 *
 * <p>A frame: {@link #beginFrame} (the swapchain can take another), make sure the image is big enough
 * ({@link #ensureCapacity}), draw into {@link #image()} in Vulkan — waiting on {@link #lastCopyValue()} before
 * writing, signalling a value from {@link DxgiContext#nextValue()} when done — then {@link #present} with that value.
 *
 * <p>The image is allocated larger than the window and only reallocated when the window outgrows it, so a
 * {@link #resize} is a {@code ResizeBuffers} and nothing else: no Vulkan object changes.
 */
public final class DxgiSwapchain implements AutoCloseable {

    private static final int BUFFERS = 2;
    private static final int FLAGS = Dxgi.SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT;
    /** Image capacity is rounded up to this, plus one step of headroom, so a growing drag reallocates rarely. */
    private static final int CAPACITY_STEP = 256;
    private static final int MAX_DIMENSION = 16384;
    /** The usage the shared image is imported with: drawn into, and readable/clearable by transfer. */
    public static final int IMAGE_USAGE = Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT
            | Vk.IMAGE_USAGE_TRANSFER_DST_BIT;

    private final DxgiContext context;
    private final MemorySegment swapchain;
    private final MemorySegment waitable;
    private final MemorySegment[] allocators = new MemorySegment[BUFFERS];
    private final MemorySegment[] lists = new MemorySegment[BUFFERS];
    private final long[] bufferDone = new long[BUFFERS];
    private int width;
    private int height;
    private SharedImage image;
    private long lastCopy;
    private boolean closed;

    private DxgiSwapchain(DxgiContext context, MemorySegment swapchain, MemorySegment waitable, int width, int height) {
        this.context = context;
        this.swapchain = swapchain;
        this.waitable = waitable;
        this.width = width;
        this.height = height;
    }

    /**
     * A swapchain on {@code hwnd}, {@code width}×{@code height}. {@code stretch} picks what the compositor does
     * with a back buffer that does not match the window between a resize and the next present: stretch it, or
     * show it 1:1 at the top-left.
     */
    public static DxgiSwapchain create(DxgiContext context, MemorySegment hwnd, int width, int height, boolean stretch) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        MemorySegment swapchain = Dxgi.createSwapChainForHwnd(context.factory(), context.queue(), hwnd, w, h,
                Dxgi.FORMAT_B8G8R8A8_UNORM, BUFFERS, stretch ? Dxgi.SCALING_STRETCH : Dxgi.SCALING_NONE, FLAGS);
        DxgiSwapchain made = null;
        try {
            Dxgi.makeWindowAssociation(context.factory(), hwnd, Dxgi.MWA_NO_WINDOW_CHANGES | Dxgi.MWA_NO_ALT_ENTER);
            // One frame queued at most: the CPU never runs more than a frame ahead of the glass, the same rule the
            // Vulkan path keeps with one frame in flight.
            Dxgi.setMaximumFrameLatency(swapchain, 1);
            MemorySegment waitable = Dxgi.frameLatencyWaitable(swapchain);
            made = new DxgiSwapchain(context, swapchain, waitable, w, h);
            for (int i = 0; i < BUFFERS; i++) {
                made.allocators[i] = D3d12.createCommandAllocator(context.device());
                made.lists[i] = D3d12.createCommandList(context.device(), made.allocators[i]);
            }
            made.ensureCapacity(w, h);
            return made;
        } catch (RuntimeException e) {
            if (made != null) {
                made.close();
            } else {
                Com.release(swapchain);
            }
            throw e;
        }
    }

    /**
     * Wait until the swapchain can take another frame, up to {@code timeoutMillis}. Returns false on a timeout,
     * which a caller treats as "draw anyway": the waitable is a pacing hint, not a correctness requirement.
     */
    public boolean beginFrame(int timeoutMillis) {
        return Win32.waitFor(waitable, timeoutMillis) == Win32.WAIT_OBJECT_0;
    }

    /** The image to draw into. Replaced by {@link #ensureCapacity}; never cache it across that call. */
    public SharedImage image() {
        return image;
    }

    /** The value Vulkan must wait for before writing {@link #image()} again: the last copy out of it is done. */
    public long lastCopyValue() {
        return lastCopy;
    }

    /** The back buffers' size: what was last presented at, or resized to. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Make sure {@link #image()} can hold {@code w}×{@code h}. Returns true when it was replaced — the caller then
     * remakes anything built on the old view (a framebuffer). Idles the queue first, since the old image may still be
     * being copied from; the caller must also have idled any Vulkan work still writing it.
     */
    public boolean ensureCapacity(int w, int h) {
        if (image != null && image.width() >= w && image.height() >= h) {
            return false;
        }
        int capW = capacity(Math.max(w, image == null ? 0 : image.width()));
        int capH = capacity(Math.max(h, image == null ? 0 : image.height()));
        context.idle();
        releaseImage();
        MemorySegment resource = D3d12.createSharedTexture(context.device(), capW, capH, Dxgi.FORMAT_B8G8R8A8_UNORM);
        try {
            MemorySegment handle = D3d12.createSharedHandle(context.device(), resource);
            try {
                image = context.interop().importImage(resource, handle, capW, capH, Vk.FORMAT_B8G8R8A8_UNORM,
                        IMAGE_USAGE);
            } finally {
                Win32.close(handle);
            }
        } catch (RuntimeException e) {
            Com.release(resource);
            throw e;
        }
        return true;
    }

    private static int capacity(int size) {
        int stepped = (Math.max(1, size) + CAPACITY_STEP + CAPACITY_STEP - 1) / CAPACITY_STEP * CAPACITY_STEP;
        return Math.min(stepped, MAX_DIMENSION);
    }

    /**
     * Resize the back buffers to {@code w}×{@code h} — the whole cost of a window resize on this path. Idles the
     * D3D12 queue (no back buffer may be in use); does nothing if the size is unchanged.
     */
    public void resize(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (w == width && h == height) {
            return;
        }
        context.idle();
        Dxgi.resizeBuffers(swapchain, w, h, FLAGS);
        width = w;
        height = h;
    }

    /**
     * Copy the top-left {@code w}×{@code h} of {@link #image()} into the current back buffer once Vulkan's
     * {@code renderedValue} is reached, and present it with vsync. Returns the {@code Present} HRESULT
     * ({@link Dxgi#STATUS_OCCLUDED} is a success code meaning nothing was shown).
     */
    public int present(long renderedValue, int w, int h) {
        int copyW = Math.min(Math.min(w, width), image.width());
        int copyH = Math.min(Math.min(h, height), image.height());
        int index = Dxgi.currentBackBufferIndex(swapchain);
        if (bufferDone[index] != 0L) {
            context.waitForValue(bufferDone[index]);   // the allocator's last list has run
        }
        MemorySegment allocator = allocators[index];
        MemorySegment list = lists[index];
        D3d12.resetAllocator(allocator);
        D3d12.reset(list, allocator);
        MemorySegment backBuffer = Dxgi.buffer(swapchain, index);
        try {
            D3d12.transition(list, backBuffer, D3d12.RESOURCE_STATE_PRESENT, D3d12.RESOURCE_STATE_COPY_DEST);
            if (copyW > 0 && copyH > 0) {
                D3d12.copyCorner(list, backBuffer, image.d3dResource(), copyW, copyH);
            }
            D3d12.transition(list, backBuffer, D3d12.RESOURCE_STATE_COPY_DEST, D3d12.RESOURCE_STATE_PRESENT);
            D3d12.close(list);
        } finally {
            // The swapchain keeps the buffer alive; D3D12 does not count the list's use of it, so this is safe now.
            Com.release(backBuffer);
        }
        D3d12.waitFor(context.queue(), context.fence(), renderedValue);
        D3d12.executeOne(context.queue(), list);
        long done = context.nextValue();
        D3d12.signal(context.queue(), context.fence(), done);
        bufferDone[index] = done;
        lastCopy = done;
        int hr = Dxgi.present(swapchain, 1, 0);
        if (hr < 0) {
            int reason = D3d12.deviceRemovedReason(context.device());
            throw new NativeException("IDXGISwapChain::Present failed: HRESULT 0x" + Integer.toHexString(hr)
                    + " (device removed reason 0x" + Integer.toHexString(reason) + ")");
        }
        return hr;
    }

    private void releaseImage() {
        if (image != null) {
            context.interop().destroy(image);
            Com.release(image.d3dResource());
            image = null;
        }
    }

    /** Idle the queue and release everything. The caller has idled Vulkan work that writes the image. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            context.idle();
        } finally {
            releaseImage();
            for (int i = 0; i < BUFFERS; i++) {
                Com.release(lists[i]);
                Com.release(allocators[i]);
            }
            Win32.close(waitable);
            Com.release(swapchain);
        }
    }
}
