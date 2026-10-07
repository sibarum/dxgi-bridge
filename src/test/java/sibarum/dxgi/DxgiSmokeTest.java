package sibarum.dxgi;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.vulkan.Ffm;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDebugMessenger;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole path on real hardware, in a hidden window: a Vulkan device with the interop extensions, a context and a
 * swapchain, six frames each cleared by Vulkan and copied and presented by D3D12, a resize between them, and a CPU
 * wait that only returns if both queues honoured the shared fence. Run with {@code -Dvexelray.vulkan.validation}
 * to also assert the validation layer stayed silent, and {@code -Ddxgi.debug=true} for the D3D12 debug layer.
 */
class DxgiSmokeTest {

    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"),
            JAVA_INT.withName("aspectMask"), JAVA_INT.withName("baseMipLevel"), JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("baseArrayLayer"), JAVA_INT.withName("layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageMemoryBarrier");

    @Test
    void clearsPresentsAndResizesThroughDxgi() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "DXGI is Windows only");
        try (VulkanInstance instance = new VulkanInstance("dxgi-bridge smoke", List.of())) {
            Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
            assumeTrue(selection.isPresent(), "no Vulkan graphics device");
            assumeTrue(instance.adapterLuid(selection.get().physicalDevice()).isPresent(),
                    "the driver reports no adapter LUID");
            long errorsBefore = VulkanDebugMessenger.errorCount();

            VulkanDevice.Request request = new VulkanDevice.Request(false, null, 1)
                    .withExtensions(DxgiContext.REQUIRED_DEVICE_EXTENSIONS)
                    .withTimelineSemaphore();
            MemorySegment hwnd = TestWindow.create(640, 480);
            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection.get(), request);
                 DxgiContext context = DxgiContext.create(instance, device)) {
                Clearer clearer = new Clearer(device);
                try (DxgiSwapchain swapchain = DxgiSwapchain.create(context, hwnd, 640, 480, true)) {
                    int w = 640;
                    int h = 480;
                    for (int frame = 0; frame < 6; frame++) {
                        if (frame == 3) {
                            w = 900;
                            h = 700;
                            swapchain.resize(w, h);
                            swapchain.ensureCapacity(w, h);
                        }
                        swapchain.beginFrame(1000);
                        long rendered = context.nextValue();
                        clearer.clear(swapchain.image().image(), context.timelineSemaphore(),
                                swapchain.lastCopyValue(), rendered, frame / 6f);
                        swapchain.present(rendered, w, h);
                    }
                    assertEquals(900, swapchain.width());
                    assertTrue(swapchain.image().width() >= 900 && swapchain.image().height() >= 700);
                    // Returns only if Vulkan signalled what D3D12 waited for, and D3D12 then signalled its copy.
                    context.waitForValue(swapchain.lastCopyValue());
                    device.waitIdle();
                }
                clearer.close();
            } finally {
                TestWindow.destroy(hwnd);
            }
            if (VulkanInstance.validationLayerActive()) {
                assertEquals(errorsBefore, VulkanDebugMessenger.errorCount(), "Vulkan validation errors");
            }
        }
    }

    /** Clears the shared image to a colour in Vulkan, waiting on and signalling the shared timeline. */
    private static final class Clearer implements AutoCloseable {
        private final VulkanDevice device;
        private final MethodHandle createPool;
        private final MethodHandle destroyPool;
        private final MethodHandle allocate;
        private final MethodHandle begin;
        private final MethodHandle end;
        private final MethodHandle barrier;
        private final MethodHandle clear;
        private final MethodHandle submit;
        private final MethodHandle queueWaitIdle;
        private final long pool;
        private final MemorySegment cmd;

        Clearer(VulkanDevice device) {
            this.device = device;
            FunctionDescriptor create = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
            createPool = device.command("vkCreateCommandPool", create);
            destroyPool = device.command("vkDestroyCommandPool", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
            allocate = device.command("vkAllocateCommandBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            begin = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            end = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            barrier = device.command("vkCmdPipelineBarrier", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT,
                    JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            clear = device.command("vkCmdClearColorImage", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT,
                    ADDRESS, JAVA_INT, ADDRESS));
            submit = device.command("vkQueueSubmit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
            queueWaitIdle = device.command("vkQueueWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment info = temp.allocate(VkStructs.COMMAND_POOL_CREATE_INFO);
                Ffm.si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
                Ffm.si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
                Ffm.si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
                MemorySegment pPool = temp.allocate(JAVA_LONG);
                Ffm.check(Ffm.invoke(createPool, device.handle(), info, MemorySegment.NULL, pPool), "vkCreateCommandPool");
                pool = pPool.get(JAVA_LONG, 0);
                MemorySegment alloc = temp.allocate(VkStructs.COMMAND_BUFFER_ALLOCATE_INFO);
                Ffm.si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                Ffm.sl(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
                Ffm.si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
                Ffm.si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
                MemorySegment pCmd = Ffi.GLOBAL.allocate(ADDRESS);
                Ffm.check(Ffm.invoke(allocate, device.handle(), alloc, pCmd), "vkAllocateCommandBuffers");
                cmd = pCmd.get(ADDRESS, 0);
            }
        }

        void clear(long image, long timeline, long waitValue, long signalValue, float shade) {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment beginInfo = temp.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
                Ffm.si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                Ffm.si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                Ffm.check(Ffm.invoke(begin, cmd, beginInfo), "vkBeginCommandBuffer");

                MemorySegment b = temp.allocate(IMAGE_MEMORY_BARRIER);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "dstAccessMask", Vk.ACCESS_TRANSFER_WRITE_BIT);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "oldLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "newLayout", Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
                Ffm.sl(b, IMAGE_MEMORY_BARRIER, "image", image);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "levelCount", 1);
                Ffm.si(b, IMAGE_MEMORY_BARRIER, "layerCount", 1);
                Ffm.invokeVoid(barrier, cmd, Vk.PIPELINE_STAGE_TOP_OF_PIPE_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT, 0,
                        0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, b);

                MemorySegment color = temp.allocate(JAVA_FLOAT, 4);
                color.setAtIndex(JAVA_FLOAT, 0, shade);
                color.setAtIndex(JAVA_FLOAT, 1, 0.4f);
                color.setAtIndex(JAVA_FLOAT, 2, 1f - shade);
                color.setAtIndex(JAVA_FLOAT, 3, 1f);
                MemorySegment range = temp.allocate(JAVA_INT, 5);
                range.setAtIndex(JAVA_INT, 0, Vk.IMAGE_ASPECT_COLOR_BIT);
                range.setAtIndex(JAVA_INT, 2, 1);
                range.setAtIndex(JAVA_INT, 4, 1);
                Ffm.invokeVoid(clear, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1, range);
                Ffm.check(Ffm.invoke(end, cmd), "vkEndCommandBuffer");

                MemorySegment waitValues = temp.allocate(JAVA_LONG);
                waitValues.set(JAVA_LONG, 0, waitValue);
                MemorySegment signalValues = temp.allocate(JAVA_LONG);
                signalValues.set(JAVA_LONG, 0, signalValue);
                MemorySegment timelineInfo = temp.allocate(VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO);
                Ffm.si(timelineInfo, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "sType",
                        VkInterop.STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO);
                Ffm.si(timelineInfo, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "waitSemaphoreValueCount", 1);
                Ffm.sa(timelineInfo, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "pWaitSemaphoreValues", waitValues);
                Ffm.si(timelineInfo, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "signalSemaphoreValueCount", 1);
                Ffm.sa(timelineInfo, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "pSignalSemaphoreValues", signalValues);

                MemorySegment semaphores = temp.allocate(JAVA_LONG);
                semaphores.set(JAVA_LONG, 0, timeline);
                MemorySegment stages = temp.allocate(JAVA_INT);
                stages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_TRANSFER_BIT);
                MemorySegment cmds = temp.allocate(ADDRESS);
                cmds.set(ADDRESS, 0, cmd);
                MemorySegment info = temp.allocate(VkStructs.SUBMIT_INFO);
                Ffm.si(info, VkStructs.SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
                Ffm.sa(info, VkStructs.SUBMIT_INFO, "pNext", timelineInfo);
                Ffm.si(info, VkStructs.SUBMIT_INFO, "waitSemaphoreCount", 1);
                Ffm.sa(info, VkStructs.SUBMIT_INFO, "pWaitSemaphores", semaphores);
                Ffm.sa(info, VkStructs.SUBMIT_INFO, "pWaitDstStageMask", stages);
                Ffm.si(info, VkStructs.SUBMIT_INFO, "commandBufferCount", 1);
                Ffm.sa(info, VkStructs.SUBMIT_INFO, "pCommandBuffers", cmds);
                Ffm.si(info, VkStructs.SUBMIT_INFO, "signalSemaphoreCount", 1);
                Ffm.sa(info, VkStructs.SUBMIT_INFO, "pSignalSemaphores", semaphores);
                Ffm.check(Ffm.invoke(submit, device.queue(), 1, info, 0L), "vkQueueSubmit");
                // The test reuses one command buffer, so the CPU waits for it; a presenter keeps a fence instead.
                Ffm.check(Ffm.invoke(queueWaitIdle, device.queue()), "vkQueueWaitIdle");
            }
        }

        @Override
        public void close() {
            Ffm.invokeVoid(destroyPool, device.handle(), pool, MemorySegment.NULL);
        }
    }

    /** A hidden top-level window of the predefined {@code STATIC} class: an HWND to present to and nothing else. */
    private static final class TestWindow {
        private static final SymbolLookup USER32 = Ffi.library("user32");
        private static final MethodHandle CreateWindowExW = Ffi.downcall(USER32, "CreateWindowExW",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle DestroyWindow = Ffi.downcall(USER32, "DestroyWindow",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;

        static MemorySegment create(int width, int height) {
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment className = wide(temp, "STATIC");
                MemorySegment title = wide(temp, "dxgi-bridge smoke");
                MemorySegment hwnd = (MemorySegment) CreateWindowExW.invokeExact(0, className, title,
                        WS_OVERLAPPEDWINDOW, 100, 100, width, height, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL);
                assumeTrue(!hwnd.equals(MemorySegment.NULL), "CreateWindowExW failed");
                return hwnd;
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        }

        static void destroy(MemorySegment hwnd) {
            try {
                int ignored = (int) DestroyWindow.invokeExact(hwnd);
            } catch (Throwable t) {
                throw new AssertionError(t);
            }
        }

        private static MemorySegment wide(Arena arena, String s) {
            MemorySegment out = arena.allocate((s.length() + 1) * 2L, 2);
            for (int i = 0; i < s.length(); i++) {
                out.set(java.lang.foreign.ValueLayout.JAVA_CHAR, i * 2L, s.charAt(i));
            }
            return out;
        }
    }
}
