package sibarum.dxgi;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What sharing a buffer costs over sharing an image, on whatever driver this is: the same frames through both paths,
 * timed on the CPU. "vulkan" is a clear and, on the buffer path, the copy into the buffer, submitted and waited for;
 * "d3d12" is the copy into the back buffer and the present, waited for. Medians, after a warm-up. Opt-in, since it
 * proves nothing and takes a while: {@code mvn test -Dtest=DxgiShareCostBench -Ddxgi.bench=true}.
 */
class DxgiShareCostBench {

    private static final int WARMUP = 30;
    private static final int FRAMES = 300;

    @Test
    void imageAgainstBuffer() {
        assumeTrue(Boolean.getBoolean("dxgi.bench"), "-Ddxgi.bench=true to run");
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "DXGI is Windows only");
        try (VulkanInstance instance = new VulkanInstance("dxgi-bridge bench", List.of())) {
            Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
            assumeTrue(selection.isPresent(), "no Vulkan graphics device");
            VulkanDevice.Request request = new VulkanDevice.Request(false, null, 1)
                    .withExtensions(DxgiContext.REQUIRED_DEVICE_EXTENSIONS)
                    .withTimelineSemaphore();
            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection.get(), request)) {
                System.out.println("[dxgi bench] " + selection.get().deviceName() + ", driver id "
                        + VkInterop.driverId(instance, device.physicalDevice()));
                int[][] sizes = {{1009, 778}, {1920, 1080}, {3840, 2160}};
                for (int[] size : sizes) {
                    for (boolean buffer : new boolean[]{false, true}) {
                        if (!buffer && DxgiContext.needsBuffer(instance, device)) {
                            continue;   // the image path does not work here
                        }
                        measure(instance, device, buffer, size[0], size[1]);
                    }
                }
            }
        }
    }

    private static void measure(VulkanInstance instance, VulkanDevice device, boolean buffer, int w, int h) {
        long[] vulkan = new long[FRAMES];
        long[] d3d12 = new long[FRAMES];
        MemorySegment hwnd = DxgiSmokeTest.TestWindow.create(w, h);
        try (DxgiContext context = DxgiContext.create(instance, device, buffer);
             DxgiSmokeTest.Clearer clearer = new DxgiSmokeTest.Clearer(device);
             DxgiSwapchain swapchain = DxgiSwapchain.create(context, hwnd, w, h)) {
            for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
                swapchain.beginFrame(100);
                long rendered = context.nextValue();
                long t0 = System.nanoTime();
                clearer.clear(swapchain, w, h, context.timelineSemaphore(), rendered, (frame % 7) / 7f);
                long t1 = System.nanoTime();
                swapchain.present(rendered, w, h);
                context.waitForValue(swapchain.lastCopyValue());
                long t2 = System.nanoTime();
                if (frame >= WARMUP) {
                    vulkan[frame - WARMUP] = t1 - t0;
                    d3d12[frame - WARMUP] = t2 - t1;
                }
            }
            device.waitIdle();
        } finally {
            DxgiSmokeTest.TestWindow.destroy(hwnd);
        }
        System.out.printf("[dxgi bench] %4dx%-4d %-6s vulkan %6.1f us  d3d12 %6.1f us  (medians of %d)%n",
                w, h, buffer ? "buffer" : "image", median(vulkan) / 1e3, median(d3d12) / 1e3, FRAMES);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
