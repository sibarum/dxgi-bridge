# dxgi-bridge

Present Vulkan-drawn images through a **DXGI swapchain** on Windows, from Java, over Panama FFM.

Vulkan keeps doing all the drawing. It draws into one image that D3D12 created and shared; a D3D12 queue copies
the visible part of it into a flip-model DXGI swapchain and presents. Windows owns that swapchain, so a window
resize is `IDXGISwapChain::ResizeBuffers` — not a `vkCreateSwapchainKHR`, which on some machines (a hybrid
laptop, notably) stalls the compositor for seconds or resets the GPU when it happens dozens of times a second
during a drag.

The design follows [vk_dxgi](https://github.com/something-mighty/vk_dxgi) (CC0), in its blit mode.

## How it works

- **One GPU.** The D3D12 device is found from the Vulkan device's adapter LUID (`EnumAdapterByLuid`).
- **One shared image** (`D3D12_HEAP_FLAG_SHARED`, render target, simultaneous access), imported into Vulkan with
  `VK_KHR_external_memory_win32` as a `D3D12_RESOURCE` handle. It is allocated larger than the window and only
  reallocated when the window outgrows it, so a resize normally changes nothing on the Vulkan side.
- **One fence.** A D3D12 fence created `D3D12_FENCE_FLAG_SHARED`, imported into Vulkan as a **timeline
  semaphore** (`VK_KHR_external_semaphore_win32`, `D3D12_FENCE` handle). Every value on it is handed out by
  `DxgiContext.nextValue()`, so both APIs count on the same line.
- **A frame:** wait on the swapchain's frame-latency waitable (`DxgiSwapchain.beginFrame`) → Vulkan draws and
  signals value *R*, having waited for the previous copy (`lastCopyValue`) → `DxgiSwapchain.present(R, w, h)`:
  the D3D12 queue waits *R*, copies the `w`×`h` corner into the back buffer, signals, and presents.

## What a Vulkan device needs

```java
Request request = Request.present()                       // or any other
        .withExtensions(DxgiContext.REQUIRED_DEVICE_EXTENSIONS)
        .withTimelineSemaphore();
```

## Layout

| Class | What |
| --- | --- |
| `Com`, `Guid` | Calling COM from Java: a vtable slot to a downcall, `Release`, `QueryInterface`, HRESULTs |
| `D3d12`, `Dxgi`, `Win32` | The bindings, one class per library; vtable slots copied from the Windows SDK's C `*Vtbl` structs |
| `VkInterop` | The Vulkan half: import a D3D12 resource as a `VkImage`, a D3D12 fence as a timeline semaphore |
| `DxgiContext` | One per Vulkan device: the D3D12 device, queue and shared fence |
| `DxgiSwapchain` | One per window: the swapchain, the shared image, copy and present, resize |

`-Ddxgi.debug=true` turns on the D3D12 debug layer (it must be installed: Windows' optional "Graphics Tools").

## Building

JDK 25, Maven. Install supirvast first (`mvn install` in that repo), then `mvn install` here.
The smoke test needs Windows and a GPU whose driver reports a valid LUID, and skips otherwise.
