package sibarum.dxgi;

import dev.supirvast.ffi.NativeException;
import dev.supirvast.vulkan.Vk;

import java.lang.foreign.MemorySegment;

/**
 * A flip-model DXGI swapchain on one window, and the shared image Vulkan draws that window into.
 *
 * <p>A frame: {@link #beginFrame} (the swapchain can take another), make sure the image is big enough
 * ({@link #ensureCapacity}), draw into {@link #image()} in Vulkan and {@link #recordCopyOut} after the drawing, in the
 * same submission — waiting on {@link #lastCopyValue()} before writing, signalling a value from
 * {@link DxgiContext#nextValue()} when done — then {@link #present} with that value.
 *
 * <p>Where the frame crosses to D3D12 is {@link DxgiContext#sharesBuffer()}'s choice. Normally {@link #image()} is
 * itself shared: Vulkan draws into the memory D3D12 copies from, and {@link #recordCopyOut} records nothing. On a
 * driver that cannot draw into a shared image (dzn: {@link DxgiContext#needsBuffer}) the image is Vulkan's own, a
 * shared <em>buffer</em> sits beside it, {@link #recordCopyOut} copies the drawn corner into the buffer, and
 * {@link #present} copies the buffer's placed footprint into the back buffer. That is one more copy of the window's
 * pixels per frame, on the GPU.
 *
 * <p>The image and the back buffers are both allocated larger than the window and only reallocated when the window
 * outgrows them, so a {@link #resize} normally changes nothing at all: no buffer on either GPU and no Vulkan object.
 * The buffers are presented unscaled ({@code DXGI_SCALING_NONE}), so the compositor shows their top-left 1:1 and the
 * window's edge clips the rest, and {@link #present} fills exactly that corner.
 *
 * <p>{@code ResizeBuffers} on every size change is what made a drag hang: on a machine whose panel is driven by
 * another GPU than the one drawing, each call reallocates the cross-adapter surfaces on both, and the driver every so
 * often held one for a round 1, 2 or 10 s, or hung the GPU until it was reset (measured 2026-10-13, MainFrame on an
 * RTX 5070 Ti laptop whose panel is on the Intel GPU: 300 resizes stalled 1-7 s at a time and raised a GPU engine
 * timeout; without the reallocation, 300 took 9 s and none waited over 70 ms). A Vulkan swapchain's rebuild did the
 * same, for the same reason. {@code SetSourceSize} looks like the tool for this and is not: on that machine the
 * compositor ignored it under {@code SCALING_STRETCH} and scaled the source up to the buffer under
 * {@code SCALING_NONE}.
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
    /** The window's size: the part of the back buffers on the screen. */
    private int width;
    private int height;
    /** The back buffers' allocated size, at least {@link #width}×{@link #height}. */
    private int bufferWidth;
    private int bufferHeight;
    private SharedImage image;
    /** The buffer the frame crosses to D3D12 through, when {@link DxgiContext#sharesBuffer()}; null otherwise. */
    private SharedBuffer shared;
    private long lastCopy;
    private boolean closed;

    private DxgiSwapchain(DxgiContext context, MemorySegment swapchain, MemorySegment waitable, int width, int height,
                          int bufferWidth, int bufferHeight) {
        this.context = context;
        this.swapchain = swapchain;
        this.waitable = waitable;
        this.width = width;
        this.height = height;
        this.bufferWidth = bufferWidth;
        this.bufferHeight = bufferHeight;
    }

    /**
     * A swapchain on {@code hwnd}, {@code width}×{@code height}. Between a resize and the next present the compositor
     * shows the last frame 1:1 at the top-left; it is never stretched, since the buffers are larger than the window.
     */
    public static DxgiSwapchain create(DxgiContext context, MemorySegment hwnd, int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int bufferW = capacity(w);
        int bufferH = capacity(h);
        MemorySegment swapchain = Dxgi.createSwapChainForHwnd(context.factory(), context.queue(), hwnd, bufferW, bufferH,
                Dxgi.FORMAT_B8G8R8A8_UNORM, BUFFERS, Dxgi.SCALING_NONE, FLAGS);
        DxgiSwapchain made = null;
        try {
            Dxgi.makeWindowAssociation(context.factory(), hwnd, Dxgi.MWA_NO_WINDOW_CHANGES | Dxgi.MWA_NO_ALT_ENTER);
            // One frame queued at most: the CPU never runs more than a frame ahead of the glass, the same rule the
            // Vulkan path keeps with one frame in flight.
            Dxgi.setMaximumFrameLatency(swapchain, 1);
            MemorySegment waitable = Dxgi.frameLatencyWaitable(swapchain);
            made = new DxgiSwapchain(context, swapchain, waitable, w, h, bufferW, bufferH);
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

    /** The window's size as last presented at, or resized to: the part of the back buffers on the screen. */
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
        if (context.sharesBuffer()) {
            image = context.interop().createImage(capW, capH, Vk.FORMAT_B8G8R8A8_UNORM, IMAGE_USAGE);
            shared = importBuffer(capW, capH);
            return true;
        }
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

    /** A shared buffer holding a {@code w}×{@code h} BGRA image, rows aligned for a D3D12 placed footprint. */
    private SharedBuffer importBuffer(int w, int h) {
        int align = D3d12.TEXTURE_DATA_PITCH_ALIGNMENT;
        int rowPitch = (w * 4 + align - 1) / align * align;
        MemorySegment resource = D3d12.createSharedBuffer(context.device(), (long) rowPitch * h);
        try {
            MemorySegment handle = D3d12.createSharedHandle(context.device(), resource);
            try {
                return context.interop().importBuffer(resource, handle, rowPitch, w, h,
                        Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            } finally {
                Win32.close(handle);
            }
        } catch (RuntimeException e) {
            Com.release(resource);
            throw e;
        }
    }

    /**
     * Record, after the drawing into {@link #image()} and in the same command buffer, whatever hands its top-left
     * {@code w}×{@code h} to D3D12: nothing when the image is itself shared, a copy into the shared buffer when not.
     * The image is in {@code layout} (which must allow transfer reads), last written at {@code srcStage} with
     * {@code srcAccess}, and stays in that layout.
     */
    public void recordCopyOut(MemorySegment cmd, int layout, int srcStage, int srcAccess, int w, int h) {
        if (shared == null) {
            return;
        }
        int copyW = Math.min(w, shared.width());
        int copyH = Math.min(h, shared.height());
        if (copyW > 0 && copyH > 0) {
            context.interop().recordCopyToBuffer(cmd, image, layout, srcStage, srcAccess, shared, copyW, copyH);
        }
    }

    private static int capacity(int size) {
        int stepped = (Math.max(1, size) + CAPACITY_STEP + CAPACITY_STEP - 1) / CAPACITY_STEP * CAPACITY_STEP;
        return Math.min(stepped, MAX_DIMENSION);
    }

    /**
     * Present {@code w}×{@code h} from now on — the whole cost of a window resize on this path. Within the back
     * buffers' capacity that is a new pair of numbers and nothing else; only a window that outgrows them idles the
     * D3D12 queue (no back buffer may be in use) and reallocates them with headroom, which a drag does rarely. Does
     * nothing if the size is unchanged.
     */
    public void resize(int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        if (w == width && h == height) {
            return;
        }
        if (w > bufferWidth || h > bufferHeight) {
            int bufferW = capacity(Math.max(w, bufferWidth));
            int bufferH = capacity(Math.max(h, bufferHeight));
            context.idle();
            Dxgi.resizeBuffers(swapchain, bufferW, bufferH, FLAGS);
            bufferWidth = bufferW;
            bufferHeight = bufferH;
        }
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
            if (copyW > 0 && copyH > 0 && shared != null) {
                D3d12.copyFootprintCorner(list, backBuffer, shared.d3dResource(), Dxgi.FORMAT_B8G8R8A8_UNORM,
                        copyW, copyH, shared.rowPitch());
            } else if (copyW > 0 && copyH > 0) {
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
        if (shared != null) {
            context.interop().destroy(shared);
            Com.release(shared.d3dResource());
            shared = null;
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
