package sibarum.dxgi;

import java.lang.foreign.MemorySegment;

/**
 * One image both APIs see: the D3D12 resource that owns the memory, and the Vulkan image, memory and colour view
 * imported over it.
 *
 * @param d3dResource the {@code ID3D12Resource}
 * @param image       the {@code VkImage}
 * @param memory      the imported {@code VkDeviceMemory}
 * @param view        a 2D colour {@code VkImageView} of the whole image
 * @param width       its width in texels — the capacity, which a window may be smaller than
 * @param height      its height in texels
 * @param vkFormat    its {@code VkFormat}
 */
public record SharedImage(MemorySegment d3dResource, long image, long memory, long view, int width, int height,
                          int vkFormat) {
}
