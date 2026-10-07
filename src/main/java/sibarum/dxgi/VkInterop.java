package sibarum.dxgi;

import dev.supirvast.ffi.NativeException;
import dev.supirvast.vulkan.Ffm;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Vulkan half of sharing with D3D12: a D3D12 shared resource imported as a {@code VkImage}
 * ({@code VK_KHR_external_memory_win32}), and a D3D12 shared fence imported as a timeline {@code VkSemaphore}
 * ({@code VK_KHR_external_semaphore_win32}). One per {@link VulkanDevice}; it binds the commands it uses once.
 *
 * <p>Importing a Win32 handle does not take ownership of it: the caller still closes the handle, and may do so as
 * soon as the import returns.
 */
public final class VkInterop {

    /** The device extensions a {@link VulkanDevice} must be made with for anything here. */
    public static final List<String> REQUIRED_DEVICE_EXTENSIONS =
            List.of("VK_KHR_external_memory_win32", "VK_KHR_external_semaphore_win32");

    public static final int STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO = 1000207003;
    private static final int STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO = 1000207002;
    private static final int STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO = 1000072001;
    private static final int STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO = 1000127001;
    private static final int STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR = 1000073000;
    private static final int STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR = 1000073002;
    private static final int STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR = 1000078000;

    private static final int EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT = 0x40;
    private static final int EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT = 0x8;
    private static final int SEMAPHORE_TYPE_TIMELINE = 1;

    /**
     * {@code VkTimelineSemaphoreSubmitInfo}, for chaining onto a {@code VkSubmitInfo} that waits on or signals the
     * imported semaphore: a value per wait and per signal semaphore, in the same order (binary semaphores' values
     * are ignored).
     */
    public static final GroupLayout TIMELINE_SEMAPHORE_SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreValueCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pWaitSemaphoreValues"),
            JAVA_INT.withName("signalSemaphoreValueCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphoreValues")).withName("VkTimelineSemaphoreSubmitInfo");

    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"), JAVA_INT.withName("memoryTypeBits"),
            MemoryLayout.paddingLayout(4)).withName("VkMemoryRequirements");
    private static final GroupLayout EXTERNAL_MEMORY_IMAGE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("handleTypes"), MemoryLayout.paddingLayout(4)).withName("VkExternalMemoryImageCreateInfo");
    private static final GroupLayout MEMORY_DEDICATED_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("image"), JAVA_LONG.withName("buffer")).withName("VkMemoryDedicatedAllocateInfo");
    private static final GroupLayout IMPORT_MEMORY_WIN32_HANDLE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("handleType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("handle"), ADDRESS.withName("name")).withName("VkImportMemoryWin32HandleInfoKHR");
    private static final GroupLayout MEMORY_WIN32_HANDLE_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)).withName("VkMemoryWin32HandlePropertiesKHR");
    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"),
            MemoryLayout.paddingLayout(4)).withName("VkMemoryAllocateInfo");
    private static final GroupLayout SEMAPHORE_TYPE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("semaphoreType"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("initialValue")).withName("VkSemaphoreTypeCreateInfo");
    private static final GroupLayout IMPORT_SEMAPHORE_WIN32_HANDLE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("semaphore"), JAVA_INT.withName("flags"), JAVA_INT.withName("handleType"),
            ADDRESS.withName("handle"), ADDRESS.withName("name")).withName("VkImportSemaphoreWin32HandleInfoKHR");

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final MethodHandle createImage;
    private final MethodHandle getImageMemoryRequirements;
    private final MethodHandle getMemoryWin32HandleProperties;
    private final MethodHandle allocateMemory;
    private final MethodHandle bindImageMemory;
    private final MethodHandle createImageView;
    private final MethodHandle destroyImageView;
    private final MethodHandle destroyImage;
    private final MethodHandle freeMemory;
    private final MethodHandle createSemaphore;
    private final MethodHandle importSemaphoreWin32Handle;
    private final MethodHandle destroySemaphore;

    public VkInterop(VulkanDevice device) {
        this.device = device;
        this.dev = device.handle();
        FunctionDescriptor create = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        FunctionDescriptor destroy = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
        this.createImage = device.command("vkCreateImage", create);
        this.getImageMemoryRequirements = device.command("vkGetImageMemoryRequirements",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        this.getMemoryWin32HandleProperties = device.command("vkGetMemoryWin32HandlePropertiesKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        this.allocateMemory = device.command("vkAllocateMemory", create);
        this.bindImageMemory = device.command("vkBindImageMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        this.createImageView = device.command("vkCreateImageView", create);
        this.destroyImageView = device.command("vkDestroyImageView", destroy);
        this.destroyImage = device.command("vkDestroyImage", destroy);
        this.freeMemory = device.command("vkFreeMemory", destroy);
        this.createSemaphore = device.command("vkCreateSemaphore", create);
        this.importSemaphoreWin32Handle = device.command("vkImportSemaphoreWin32HandleKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.destroySemaphore = device.command("vkDestroySemaphore", destroy);
    }

    /**
     * Import the D3D12 resource behind {@code handle} as a {@code width}×{@code height} 2D image of
     * {@code vkFormat}, bound to dedicated memory, with a colour view. It is created {@code UNDEFINED}.
     */
    public SharedImage importImage(MemorySegment d3dResource, MemorySegment handle, int width, int height,
                                   int vkFormat, int usage) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment external = temp.allocate(EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_IMAGE_CREATE_INFO, "sType", STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_IMAGE_CREATE_INFO, "handleTypes", EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);

            MemorySegment info = temp.allocate(VkStructs.IMAGE_CREATE_INFO);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            Ffm.sa(info, VkStructs.IMAGE_CREATE_INFO, "pNext", external);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "format", vkFormat);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "extent_width", width);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "extent_height", height);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "extent_depth", 1);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "mipLevels", 1);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "arrayLayers", 1);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "usage", usage);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            MemorySegment pImage = temp.allocate(JAVA_LONG);
            Ffm.check(Ffm.invoke(createImage, dev, info, MemorySegment.NULL, pImage), "vkCreateImage");
            long image = pImage.get(JAVA_LONG, 0);

            long memory = 0L;
            long view = 0L;
            try {
                MemorySegment requirements = temp.allocate(MEMORY_REQUIREMENTS);
                Ffm.invokeVoid(getImageMemoryRequirements, dev, image, requirements);
                MemorySegment handleProperties = temp.allocate(MEMORY_WIN32_HANDLE_PROPERTIES);
                Ffm.si(handleProperties, MEMORY_WIN32_HANDLE_PROPERTIES, "sType",
                        STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR);
                Ffm.check(Ffm.invoke(getMemoryWin32HandleProperties, dev, EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT,
                        handle, handleProperties), "vkGetMemoryWin32HandlePropertiesKHR");
                int bits = Ffm.gi(requirements, MEMORY_REQUIREMENTS, "memoryTypeBits");
                int handleBits = Ffm.gi(handleProperties, MEMORY_WIN32_HANDLE_PROPERTIES, "memoryTypeBits");
                // Some drivers report no bits for a D3D12 resource handle, meaning "whatever the image needs".
                if (handleBits != 0) {
                    bits &= handleBits;
                }
                int type = device.tryFindMemoryType(bits, Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                if (type < 0) {
                    type = device.findMemoryType(bits, 0);
                }

                MemorySegment dedicated = temp.allocate(MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.si(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "sType", STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.sl(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "image", image);
                MemorySegment imports = temp.allocate(IMPORT_MEMORY_WIN32_HANDLE_INFO);
                Ffm.si(imports, IMPORT_MEMORY_WIN32_HANDLE_INFO, "sType", STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR);
                Ffm.sa(imports, IMPORT_MEMORY_WIN32_HANDLE_INFO, "pNext", dedicated);
                Ffm.si(imports, IMPORT_MEMORY_WIN32_HANDLE_INFO, "handleType", EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);
                Ffm.sa(imports, IMPORT_MEMORY_WIN32_HANDLE_INFO, "handle", handle);
                MemorySegment allocate = temp.allocate(MEMORY_ALLOCATE_INFO);
                Ffm.si(allocate, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                Ffm.sa(allocate, MEMORY_ALLOCATE_INFO, "pNext", imports);
                Ffm.sl(allocate, MEMORY_ALLOCATE_INFO, "allocationSize", Ffm.gl(requirements, MEMORY_REQUIREMENTS, "size"));
                Ffm.si(allocate, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", type);
                MemorySegment pMemory = temp.allocate(JAVA_LONG);
                Ffm.check(Ffm.invoke(allocateMemory, dev, allocate, MemorySegment.NULL, pMemory), "vkAllocateMemory(import)");
                memory = pMemory.get(JAVA_LONG, 0);
                Ffm.check(Ffm.invoke(bindImageMemory, dev, image, memory, 0L), "vkBindImageMemory");

                MemorySegment viewInfo = temp.allocate(VkStructs.IMAGE_VIEW_CREATE_INFO);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                Ffm.sl(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "image", image);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "format", vkFormat);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
                Ffm.si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
                MemorySegment pView = temp.allocate(JAVA_LONG);
                Ffm.check(Ffm.invoke(createImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
                view = pView.get(JAVA_LONG, 0);
            } catch (RuntimeException e) {
                destroy(image, memory, 0L);
                throw e;
            }
            return new SharedImage(d3dResource, image, memory, view, width, height, vkFormat);
        }
    }

    /** Destroy the Vulkan side of {@code image}; the D3D12 resource is the caller's to release. */
    public void destroy(SharedImage image) {
        destroy(image.image(), image.memory(), image.view());
    }

    private void destroy(long image, long memory, long view) {
        if (view != 0L) {
            Ffm.invokeVoid(destroyImageView, dev, view, MemorySegment.NULL);
        }
        if (image != 0L) {
            Ffm.invokeVoid(destroyImage, dev, image, MemorySegment.NULL);
        }
        if (memory != 0L) {
            Ffm.invokeVoid(freeMemory, dev, memory, MemorySegment.NULL);
        }
    }

    /** A timeline semaphore whose payload is the D3D12 fence behind {@code handle}. */
    public long importTimelineSemaphore(MemorySegment handle) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment type = temp.allocate(SEMAPHORE_TYPE_CREATE_INFO);
            Ffm.si(type, SEMAPHORE_TYPE_CREATE_INFO, "sType", STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO);
            Ffm.si(type, SEMAPHORE_TYPE_CREATE_INFO, "semaphoreType", SEMAPHORE_TYPE_TIMELINE);
            MemorySegment info = temp.allocate(VkStructs.CREATE_INFO);
            Ffm.si(info, VkStructs.CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            Ffm.sa(info, VkStructs.CREATE_INFO, "pNext", type);
            MemorySegment pSemaphore = temp.allocate(JAVA_LONG);
            Ffm.check(Ffm.invoke(createSemaphore, dev, info, MemorySegment.NULL, pSemaphore), "vkCreateSemaphore(timeline)");
            long semaphore = pSemaphore.get(JAVA_LONG, 0);

            MemorySegment imports = temp.allocate(IMPORT_SEMAPHORE_WIN32_HANDLE_INFO);
            Ffm.si(imports, IMPORT_SEMAPHORE_WIN32_HANDLE_INFO, "sType", STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR);
            Ffm.sl(imports, IMPORT_SEMAPHORE_WIN32_HANDLE_INFO, "semaphore", semaphore);
            Ffm.si(imports, IMPORT_SEMAPHORE_WIN32_HANDLE_INFO, "handleType", EXTERNAL_SEMAPHORE_HANDLE_TYPE_D3D12_FENCE_BIT);
            Ffm.sa(imports, IMPORT_SEMAPHORE_WIN32_HANDLE_INFO, "handle", handle);
            try {
                Ffm.check(Ffm.invoke(importSemaphoreWin32Handle, dev, imports), "vkImportSemaphoreWin32HandleKHR");
            } catch (RuntimeException e) {
                destroySemaphore(semaphore);
                throw e;
            }
            return semaphore;
        }
    }

    public void destroySemaphore(long semaphore) {
        if (semaphore != 0L) {
            Ffm.invokeVoid(destroySemaphore, dev, semaphore, MemorySegment.NULL);
        }
    }

    /** Fail clearly, before any D3D12 work, when the device was made without what this needs. */
    static void requireExtensions(VulkanDevice device) {
        try {
            device.command("vkImportSemaphoreWin32HandleKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            device.command("vkGetMemoryWin32HandlePropertiesKHR",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        } catch (NativeException e) {
            throw new NativeException("the Vulkan device was made without " + REQUIRED_DEVICE_EXTENSIONS
                    + " (VulkanDevice.Request.withExtensions) or the driver lacks them", e);
        }
    }
}
