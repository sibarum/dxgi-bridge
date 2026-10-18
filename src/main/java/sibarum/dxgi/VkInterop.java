package sibarum.dxgi;

import dev.supirvast.ffi.NativeException;
import dev.supirvast.vulkan.Ffm;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkLoader;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;

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
 * The Vulkan half of sharing with D3D12: a D3D12 shared resource imported as a {@code VkImage} or {@code VkBuffer}
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

    /** {@code VK_DRIVER_ID_MESA_DOZEN}: Mesa's Vulkan over D3D12, "dzn". */
    public static final int DRIVER_ID_MESA_DOZEN = 23;

    public static final int STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO = 1000207003;
    private static final int STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO = 1000207002;
    private static final int STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO = 1000072000;
    private static final int STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO = 1000072001;
    private static final int STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO = 1000127001;
    private static final int STRUCTURE_TYPE_IMPORT_MEMORY_WIN32_HANDLE_INFO_KHR = 1000073000;
    private static final int STRUCTURE_TYPE_MEMORY_WIN32_HANDLE_PROPERTIES_KHR = 1000073002;
    private static final int STRUCTURE_TYPE_IMPORT_SEMAPHORE_WIN32_HANDLE_INFO_KHR = 1000078000;
    private static final int STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 = 1000059001;
    private static final int STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES = 1000196000;

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
    /** {@code VkExternalMemoryImageCreateInfo}, and {@code VkExternalMemoryBufferCreateInfo}, which is the same shape. */
    private static final GroupLayout EXTERNAL_MEMORY_CREATE_INFO = MemoryLayout.structLayout(
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
    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"),
            JAVA_INT.withName("aspectMask"), JAVA_INT.withName("baseMipLevel"), JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("baseArrayLayer"), JAVA_INT.withName("layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageMemoryBarrier");
    /** {@code VkPhysicalDeviceDriverProperties}: the ID, then two 256-byte names and a 4-byte conformance version. */
    private static final GroupLayout DRIVER_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("driverID"), MemoryLayout.sequenceLayout(256, java.lang.foreign.ValueLayout.JAVA_BYTE)
                    .withName("driverName"),
            MemoryLayout.sequenceLayout(256, java.lang.foreign.ValueLayout.JAVA_BYTE).withName("driverInfo"),
            JAVA_INT.withName("conformanceVersion"), MemoryLayout.paddingLayout(4)
    ).withName("VkPhysicalDeviceDriverProperties");
    /** {@code VkPhysicalDeviceProperties2}: more than large enough for {@code VkPhysicalDeviceProperties} (824 bytes). */
    private static final long PROPERTIES_2_BYTES = 1024;

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final MethodHandle createImage;
    private final MethodHandle getImageMemoryRequirements;
    private final MethodHandle createBuffer;
    private final MethodHandle getBufferMemoryRequirements;
    private final MethodHandle getMemoryWin32HandleProperties;
    private final MethodHandle allocateMemory;
    private final MethodHandle bindImageMemory;
    private final MethodHandle bindBufferMemory;
    private final MethodHandle createImageView;
    private final MethodHandle destroyImageView;
    private final MethodHandle destroyImage;
    private final MethodHandle destroyBuffer;
    private final MethodHandle freeMemory;
    private final MethodHandle createSemaphore;
    private final MethodHandle importSemaphoreWin32Handle;
    private final MethodHandle destroySemaphore;
    private final MethodHandle cmdPipelineBarrier;
    private final MethodHandle cmdCopyImageToBuffer;

    public VkInterop(VulkanDevice device) {
        this.device = device;
        this.dev = device.handle();
        FunctionDescriptor create = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        FunctionDescriptor destroy = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
        FunctionDescriptor requirements = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
        FunctionDescriptor bind = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);
        this.createImage = device.command("vkCreateImage", create);
        this.getImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", requirements);
        this.createBuffer = device.command("vkCreateBuffer", create);
        this.getBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", requirements);
        this.getMemoryWin32HandleProperties = device.command("vkGetMemoryWin32HandlePropertiesKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        this.allocateMemory = device.command("vkAllocateMemory", create);
        this.bindImageMemory = device.command("vkBindImageMemory", bind);
        this.bindBufferMemory = device.command("vkBindBufferMemory", bind);
        this.createImageView = device.command("vkCreateImageView", create);
        this.destroyImageView = device.command("vkDestroyImageView", destroy);
        this.destroyImage = device.command("vkDestroyImage", destroy);
        this.destroyBuffer = device.command("vkDestroyBuffer", destroy);
        this.freeMemory = device.command("vkFreeMemory", destroy);
        this.createSemaphore = device.command("vkCreateSemaphore", create);
        this.importSemaphoreWin32Handle = device.command("vkImportSemaphoreWin32HandleKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.destroySemaphore = device.command("vkDestroySemaphore", destroy);
        this.cmdPipelineBarrier = device.command("vkCmdPipelineBarrier", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT,
                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.cmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
    }

    /**
     * The {@code VkDriverId} of {@code physicalDevice} ({@code VkPhysicalDeviceDriverProperties.driverID}), or 0 when
     * the driver does not report one.
     */
    public static int driverId(VulkanInstance instance, MemorySegment physicalDevice) {
        MethodHandle getProperties2 = VkLoader.instanceCommand(instance.handle(), "vkGetPhysicalDeviceProperties2",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment driver = temp.allocate(DRIVER_PROPERTIES);
            Ffm.si(driver, DRIVER_PROPERTIES, "sType", STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES);
            MemorySegment properties2 = temp.allocate(PROPERTIES_2_BYTES, 8);
            properties2.set(JAVA_INT, 0, STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
            properties2.set(ADDRESS, 8, driver);
            Ffm.invokeVoid(getProperties2, physicalDevice, properties2);
            return Ffm.gi(driver, DRIVER_PROPERTIES, "driverID");
        }
    }

    /**
     * Import the D3D12 resource behind {@code handle} as a {@code width}×{@code height} 2D image of
     * {@code vkFormat}, bound to dedicated memory, with a colour view. It is created {@code UNDEFINED}.
     */
    public SharedImage importImage(MemorySegment d3dResource, MemorySegment handle, int width, int height,
                                   int vkFormat, int usage) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment external = temp.allocate(EXTERNAL_MEMORY_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_CREATE_INFO, "sType", STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_CREATE_INFO, "handleTypes", EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);
            long image = newImage(temp, external, width, height, vkFormat, usage);
            long memory = 0L;
            long view;
            try {
                MemorySegment requirements = temp.allocate(MEMORY_REQUIREMENTS);
                Ffm.invokeVoid(getImageMemoryRequirements, dev, image, requirements);
                MemorySegment dedicated = temp.allocate(MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.si(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "sType", STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.sl(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "image", image);
                memory = importMemory(temp, handle, requirements, dedicated);
                Ffm.check(Ffm.invoke(bindImageMemory, dev, image, memory, 0L), "vkBindImageMemory");
                view = newView(temp, image, vkFormat);
            } catch (RuntimeException e) {
                destroy(image, memory, 0L);
                throw e;
            }
            return new SharedImage(d3dResource, image, memory, view, width, height, vkFormat);
        }
    }

    /**
     * A {@code width}×{@code height} 2D image of {@code vkFormat} that only Vulkan sees, on device-local memory, with a
     * colour view: what is drawn into when the frame reaches D3D12 through a {@link SharedBuffer} instead. Its
     * {@link SharedImage#d3dResource()} is {@code NULL}. It is created {@code UNDEFINED}.
     */
    public SharedImage createImage(int width, int height, int vkFormat, int usage) {
        try (Arena temp = Arena.ofConfined()) {
            long image = newImage(temp, MemorySegment.NULL, width, height, vkFormat, usage);
            long memory = 0L;
            long view;
            try {
                MemorySegment requirements = temp.allocate(MEMORY_REQUIREMENTS);
                Ffm.invokeVoid(getImageMemoryRequirements, dev, image, requirements);
                int bits = Ffm.gi(requirements, MEMORY_REQUIREMENTS, "memoryTypeBits");
                MemorySegment allocate = temp.allocate(MEMORY_ALLOCATE_INFO);
                Ffm.si(allocate, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                Ffm.sl(allocate, MEMORY_ALLOCATE_INFO, "allocationSize", Ffm.gl(requirements, MEMORY_REQUIREMENTS, "size"));
                Ffm.si(allocate, MEMORY_ALLOCATE_INFO, "memoryTypeIndex",
                        device.findMemoryType(bits, Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                MemorySegment pMemory = temp.allocate(JAVA_LONG);
                Ffm.check(Ffm.invoke(allocateMemory, dev, allocate, MemorySegment.NULL, pMemory), "vkAllocateMemory");
                memory = pMemory.get(JAVA_LONG, 0);
                Ffm.check(Ffm.invoke(bindImageMemory, dev, image, memory, 0L), "vkBindImageMemory");
                view = newView(temp, image, vkFormat);
            } catch (RuntimeException e) {
                destroy(image, memory, 0L);
                throw e;
            }
            return new SharedImage(MemorySegment.NULL, image, memory, view, width, height, vkFormat);
        }
    }

    /**
     * Import the D3D12 buffer behind {@code handle} as a {@code VkBuffer} of {@code rowPitch * height} bytes, bound
     * to dedicated memory, holding images up to {@code width}×{@code height}.
     */
    public SharedBuffer importBuffer(MemorySegment d3dResource, MemorySegment handle, int rowPitch, int width,
                                     int height, int usage) {
        long size = (long) rowPitch * height;
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment external = temp.allocate(EXTERNAL_MEMORY_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_CREATE_INFO, "sType", STRUCTURE_TYPE_EXTERNAL_MEMORY_BUFFER_CREATE_INFO);
            Ffm.si(external, EXTERNAL_MEMORY_CREATE_INFO, "handleTypes", EXTERNAL_MEMORY_HANDLE_TYPE_D3D12_RESOURCE_BIT);
            MemorySegment info = temp.allocate(VkStructs.BUFFER_CREATE_INFO);
            Ffm.si(info, VkStructs.BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            Ffm.sa(info, VkStructs.BUFFER_CREATE_INFO, "pNext", external);
            Ffm.sl(info, VkStructs.BUFFER_CREATE_INFO, "size", size);
            Ffm.si(info, VkStructs.BUFFER_CREATE_INFO, "usage", usage);
            Ffm.si(info, VkStructs.BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = temp.allocate(JAVA_LONG);
            Ffm.check(Ffm.invoke(createBuffer, dev, info, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);
            long memory = 0L;
            try {
                MemorySegment requirements = temp.allocate(MEMORY_REQUIREMENTS);
                Ffm.invokeVoid(getBufferMemoryRequirements, dev, buffer, requirements);
                MemorySegment dedicated = temp.allocate(MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.si(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "sType", STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO);
                Ffm.sl(dedicated, MEMORY_DEDICATED_ALLOCATE_INFO, "buffer", buffer);
                memory = importMemory(temp, handle, requirements, dedicated);
                Ffm.check(Ffm.invoke(bindBufferMemory, dev, buffer, memory, 0L), "vkBindBufferMemory");
            } catch (RuntimeException e) {
                destroy(buffer, memory);
                throw e;
            }
            return new SharedBuffer(d3dResource, buffer, memory, size, rowPitch, width, height);
        }
    }

    /**
     * Record a copy of the top-left {@code width}×{@code height} of {@code image} into {@code buffer} at its row
     * pitch, after a barrier that makes the image's last writes (at {@code srcStage} with {@code srcAccess}) visible
     * to it. The image stays in {@code layout}, which must allow transfer reads.
     */
    public void recordCopyToBuffer(MemorySegment cmd, SharedImage image, int layout, int srcStage, int srcAccess,
                                   SharedBuffer buffer, int width, int height) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment b = temp.allocate(IMAGE_MEMORY_BARRIER);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "srcAccessMask", srcAccess);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "dstAccessMask", Vk.ACCESS_TRANSFER_READ_BIT);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "oldLayout", layout);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "newLayout", layout);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
            Ffm.sl(b, IMAGE_MEMORY_BARRIER, "image", image.image());
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "levelCount", 1);
            Ffm.si(b, IMAGE_MEMORY_BARRIER, "layerCount", 1);
            Ffm.invokeVoid(cmdPipelineBarrier, cmd, srcStage, Vk.PIPELINE_STAGE_TRANSFER_BIT, 0,
                    0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, b);

            MemorySegment region = temp.allocate(VkStructs.BUFFER_IMAGE_COPY);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "bufferRowLength", buffer.rowPitch() / 4);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "is_layerCount", 1);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_width", width);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_height", height);
            Ffm.si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_depth", 1);
            Ffm.invokeVoid(cmdCopyImageToBuffer, cmd, image.image(), layout, buffer.buffer(), 1, region);
        }
    }

    private long newImage(Arena temp, MemorySegment pNext, int width, int height, int vkFormat, int usage) {
        MemorySegment info = temp.allocate(VkStructs.IMAGE_CREATE_INFO);
        Ffm.si(info, VkStructs.IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
        Ffm.sa(info, VkStructs.IMAGE_CREATE_INFO, "pNext", pNext);
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
        return pImage.get(JAVA_LONG, 0);
    }

    /** Allocate memory over the D3D12 resource behind {@code handle}, dedicated to what {@code dedicated} names. */
    private long importMemory(Arena temp, MemorySegment handle, MemorySegment requirements, MemorySegment dedicated) {
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
        return pMemory.get(JAVA_LONG, 0);
    }

    private long newView(Arena temp, long image, int vkFormat) {
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
        return pView.get(JAVA_LONG, 0);
    }

    /** Destroy the Vulkan side of {@code image}; a D3D12 resource behind it is the caller's to release. */
    public void destroy(SharedImage image) {
        destroy(image.image(), image.memory(), image.view());
    }

    /** Destroy the Vulkan side of {@code buffer}; the D3D12 resource is the caller's to release. */
    public void destroy(SharedBuffer buffer) {
        destroy(buffer.buffer(), buffer.memory());
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

    private void destroy(long buffer, long memory) {
        if (buffer != 0L) {
            Ffm.invokeVoid(destroyBuffer, dev, buffer, MemorySegment.NULL);
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
