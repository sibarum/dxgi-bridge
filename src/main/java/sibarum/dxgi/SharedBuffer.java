package sibarum.dxgi;

import java.lang.foreign.MemorySegment;

/**
 * One buffer both APIs see: the D3D12 resource that owns the memory, and the Vulkan buffer and memory imported over
 * it. It holds an image of {@code width}×{@code height} texels, row-major, rows {@code rowPitch} bytes apart.
 *
 * @param d3dResource the {@code ID3D12Resource}, a buffer
 * @param buffer      the {@code VkBuffer}
 * @param memory      the imported {@code VkDeviceMemory}
 * @param size        its size in bytes: {@code rowPitch * height}
 * @param rowPitch    bytes from one row to the next, a multiple of {@link D3d12#TEXTURE_DATA_PITCH_ALIGNMENT}
 * @param width       the widest image it holds, in texels — the capacity, which a window may be smaller than
 * @param height      the tallest
 */
public record SharedBuffer(MemorySegment d3dResource, long buffer, long memory, long size, int rowPitch, int width,
                           int height) {
}
