# Jade Driver Contract

This document is the *complement* of `isa.md`. The ISA describes what the
hardware does; this document describes everything the driver must do **on
top of** the hardware so that an application written against OpenGL,
Vulkan, or OpenCL can run on Jade.

The dividing principle:

> Anything that varies between draw calls, frames, or applications is
> the driver's problem. Anything that varies per pixel is the hardware's
> problem.

When the answer is ambiguous (the three APIs disagree, the workload is
small, or the cost of hardware-ifying it is high relative to perf gain),
it goes to the driver.

---

## 1. Driver Layers

```
+----------------------------------------------------+
|  Application                                       |
+----------------------------------------------------+
|  Client API: OpenGL / Vulkan / OpenCL              |
+----------------------------------------------------+
|  Jade User-Mode Driver (UMD)                       |
|   - API-state tracking                             |
|   - Shader frontend + IR + Jade backend            |
|   - Command-stream builder                         |
|   - Resource & memory management                   |
+----------------------------------------------------+
|  Jade Kernel-Mode Driver (KMD)                     |
|   - Submission queues & DMA                        |
|   - Page tables / GART / IOMMU                     |
|   - Power, clocks, interrupts, vblank              |
|   - Display mode-setting (KMS-style)               |
+----------------------------------------------------+
|  Jade Hardware (SMs + fixed-function + display)    |
+----------------------------------------------------+
```

Both layers are mandatory. The UMD owns *what to draw*; the KMD owns
*when it actually hits the hardware*.

---

## 2. What the Driver Owns

Each of these is something Jade does **not** do in hardware, because it
either doesn't pay for the silicon or the API surface is too divergent.

### 2.1 Object & state-machine bookkeeping

OpenGL is a stateful state machine. Vulkan replaces that with explicit
state objects. OpenCL has yet another model. None of these belong in
hardware:

- GL object name management (`glGenBuffers`, `glGenTextures`, …).
- Vulkan handle allocators (`VkBuffer`, `VkImage`, …).
- OpenCL `cl_mem`, `cl_kernel`, `cl_event` lifetimes.
- Reference counting, lifetime tracking, error/validation reporting.
- Per-thread current-context tracking, locking, multi-thread reentry.

### 2.2 Shader compilation

The shader compiler lives entirely in the driver. Its stages:

| Stage | Responsibility |
|---|---|
| **Frontend** | Parse GLSL / SPIR-V / OpenCL C → internal IR. |
| **Middle** | API-agnostic optimizations: SSA, DCE, loop unrolling, vectorization to vec4. |
| **Lowering** | Map IR to Jade ISA mnemonics. Allocate VGPRs/SGPRs (max 32/16 per wavefront — the driver must spill to lane-private stack when exceeded). |
| **Scheduler** | Software instruction scheduling around fixed-function latencies (TEX, SFU, ATOM). |
| **Backend** | Emit 32-bit instruction words. |
| **Linker** | Resolve cross-stage varying slots (VTX `o*` → FRAG `a*`). |

Hardware does **not** validate the shader, does **not** legalize
unsupported ops, and does **not** profile for register pressure. The
driver is the only safety net.

Specific rewrites the driver must perform:

- **Matrix multiply** in shader source → `MUL4x4`/`MUL3x3`.
- **`dot(a,b)`** → `DP4`/`DP3`/`DP2`.
- **`mix`/`smoothstep`/`refract`/`reflect`** → corresponding `GEOM` ops.
- **Constants** in shader source → upload to constant buffer + `LDC`.
- **`texture()`** with derivative LOD → `TEX.2D` etc.; **`textureLod()`**
  → `TEXL.LOD`; **`textureGather()`** → `TEXL.GATHER`.
- **Per-vertex attributes** → vertex-fetch hardware programmed via
  `a*` slot binding; the driver tells the input assembler which
  attribute lives at which slot, stride, and format.
- **Varyings** → assigned to `o*` in the vertex shader and `a*` in the
  fragment shader through a stage-link table the driver builds at
  pipeline-create time. Hardware just routes by slot index.

### 2.3 Resource binding

Jade has small fixed binding tables (16 textures, 8 samplers, 8 const
buffers, 8 storage buffers, 4 render targets). The driver:

- Maps API descriptor sets / bindless tables / GL texture units onto
  Jade binding slots.
- Patches binding-slot indices into shader code (or uses indirect-mode
  `slot = s_rs1` variants — but those cost a cycle).
- Manages over-commitment: when an application binds more than the HW
  supports, the driver issues a binding-update between draws.

### 2.4 Memory allocation & VA management

- Physical memory pools, VA allocation, page tables (KMD).
- Suballocators inside VkDeviceMemory or `cl_mem`.
- Aligning allocations to **32 B** (the memory bus / cache-line width).
  The driver should also align vertex arrays so a wavefront's
  `LDG.V4` hits exactly two lanes per bus beat — i.e. start each
  attribute array on a 32-B boundary and stride by a multiple of 16 B.
- Honoring the `BUS_WIDTH` CSR: any helper that lays out a struct of
  shader uniforms should pad to a multiple of 32 B so updates remain
  one-beat writes.

### 2.5 Pipeline / state object construction

Vulkan pipelines, GL state caches, CL programs: all distilled by the
driver into:

- A blob of CSR writes (depth test mode, blend equation, viewport, …).
- A binding-table snapshot.
- A pointer to compiled shader code.
- An input-assembler config (primitive type, vertex fetch layout).

A draw call is then: switch CSRs → switch bindings → kick the
wavefront dispatcher.

### 2.6 Command stream / submission

The driver builds a *command stream* in CPU-writable memory consisting
of three things:

1. **CSR writes** — set state.
2. **Dispatch records** — vertex count, instance count, base vertex.
3. **Shader pointers** — VTX/FRAG/CMP shader entry points.

The KMD pushes this stream to a submission queue. Hardware reads,
applies CSR writes, then dispatches wavefronts. There is no "command
buffer recording" in hardware — Vulkan's `vkCmd*` calls are a UMD
construct.

### 2.7 Synchronization between CPU and GPU

- Fences (`vkFence`, `glSync`, `cl_event`) → KMD signals via interrupt
  on a wavefront-complete or DMA-complete IRQ.
- Semaphores → submission-queue dependencies; the KMD walks the graph
  and orders dispatches.
- `vkSemaphore` (binary & timeline) → KMD timeline counter; UMD just
  enqueues waits.
- Pipeline barriers → emitted as `FENCE` + optional `BAR.WG` in the
  command stream. Hardware only knows the primitives.

### 2.8 Resource transitions / layout

Vulkan image layouts (`SHADER_READ_ONLY_OPTIMAL`, etc.) are a *driver*
concept. Hardware texture units read whatever format the driver tells
them via the texture descriptor; layout transitions become:

- Cache flushes / invalidates (`FENCE`).
- Optional `TILE.STORE` / `IMG` copies when changing memory layout.
- An update to the texture descriptor in the binding table.

### 2.9 Vertex / index pulling

The vertex fetch unit reads vertex attribute streams via `LDG.V4` issued
implicitly by the wavefront dispatcher. The driver's job:

- Build the attribute table: per `a*` slot, `(buffer_slot, offset,
  stride, format)`.
- Run the index buffer through index assembly (strip → list expansion,
  primitive restart, etc.) on CPU when not natively supported.
- Patch instance step rates.

### 2.10 Higher-level pipeline stages not in HW

These geometric stages are *not* in Jade v1 hardware; the driver maps
them down to vertex/compute shaders + scratch buffers:

- **Tessellation** (GL/Vulkan) — emulate by emitting tessellated meshes
  via a compute pre-pass.
- **Geometry shader** — emit via a compute pre-pass + storage buffer of
  generated vertices.
- **Transform feedback** — wire vertex shader outputs to `STG.V4` into a
  driver-allocated buffer.

If any of these become important workloads, they are candidates for
moving into hardware via the `0x32`–`0x3F` extension space.

### 2.11 OpenCL-specific responsibilities

OpenCL is mostly compute. The driver:

- Maps `cl_command_queue` to the KMD submission queue.
- Maps `clEnqueueNDRangeKernel` global/local sizes onto Jade's
  work-group / wavefront layout.
- Maps `__local` → `LDS`/`STS`; `__constant` → `LDC`; `__private` →
  `LDL`/`STL`.
- Implements `barrier(CLK_LOCAL_MEM_FENCE)` → `BAR.WG`.
- Implements `async_work_group_copy` → `DMA.CPY` + `DMA.WAIT`.
- Implements `printf` → ring buffer drained by `DBG.PR`.
- Implements `clEnqueueReadBuffer` / `clFinish` → KMD DMA + fence wait.

### 2.12 Display & windowing

The display controller is hardware (`DSP.*`), but the driver still owns:

- **Mode-setting policy** (KMS in Linux terms): which connector, which
  EDID, which preferred mode. The HW just takes one mode at a time via
  `DSP.MODE`.
- **Front/back buffer ownership** across DRI / Wayland / WSI / EGL.
- **Swap-chain management** — Vulkan WSI / EGL: the driver allocates
  framebuffer slots, rotates them on `DSP.PRESENT`, signals the
  `VK_KHR_swapchain` semaphores.
- **Gamma / HDR metadata** policy. Hardware loads the LUT (`DSP.GAMMA`)
  and exposes HDR metadata registers (`DSP.HDR`); the driver decides
  what to put there based on the connected display's EDID and the app's
  color-space request.
- **Cursor compositing** — when the app wants the cursor above content,
  the driver uses `CUR.*`. When it wants software composition (e.g.,
  themed cursors with shadows), it falls back to drawing.
- **Tearing avoidance** — wait on `DSP.VSYNC` or use `DSP.PRESENT`'s
  vsync-aligned variant.

---

## 3. What the Driver Does **Not** Own

These are the things hardware now handles that an OpenGL or Vulkan
driver on commodity GPUs sometimes has to babysit. On Jade, **do not
emit driver code for any of these** — emit the corresponding opcode and
let the fixed-function unit deal with it.

| Concern | Handled by HW opcode | Why it's in HW |
|---|---|---|
| Perspective-correct interpolation | `INTRP.PC` | Per-pixel cost. Software interp is the textbook bandwidth-killer. |
| Per-fragment depth test | `ZTST.EARLY` / `ZTST.LATE` | One comparator + one cache line per pixel; cheap silicon, huge perf. |
| Per-fragment stencil ops | `STCL.*` | Same as depth. |
| Per-fragment blending | `BLND.*` | RMW on framebuffer — the ROP can chain reads/writes; software can't. |
| Color format pack/unpack | `PACK.*` | Branchy bit-twiddling that's a single LUT in HW. |
| sRGB↔linear | `SRGB2LIN` / `LIN2SRGB` | Per-channel LUT, called on every texture read & FB write. |
| Texture filtering (bilinear, trilinear, anisotropic) | `TEX.*` / `TEXL.*` | Multi-tap weighted average; one of the densest fixed-function pieces in a GPU. |
| Texture compression decode (BC1–BC7 / ASTC / ETC2) | inside `TEX.*` | Bit-unpack + endpoint interpolation per fetch. |
| Cube-face selection & seamless cube edges | `TEX.CUBE` | Direction-vector dispatch + per-edge fix-up in HW. |
| Screen-space derivatives (ddx/ddy) | `FRAG.DDX` / `FRAG.DDY` | Requires the 2×2 helper-lane quad; HW exposes it natively. |
| Coverage masks for MSAA | `MSAA.COV` | One mask per fragment from edge equations — falls out of rasterizer. |
| Triangle setup (edge eqs, 1/w plane, depth slopes) | `RAST.SETUP.TRI` | Couple of FP divides; software would block on SFU. |
| Scan-out, vsync, gamma, HDR meta | `DSP.*` | Real-time scan engine, can't be late. |
| Hardware cursor compositing | `CUR.*` | Single sprite blended on scan-out, free latency-wise. |
| Fast color/depth/stencil clears | `CLR.*` | Hardware can write a metadata bit that lazily clears on first access. |
| DMA copies | `DMA.CPY` | Off-pipeline; lets the compute side overlap. |

If the driver ever finds itself walking pixels in software, it has lost
the contract.

---

## 4. Compiling a Shader: Concrete Lowering

This is the canonical lowering the driver backend must implement.

### 4.1 GLSL vertex shader

```glsl
layout(location = 0) in vec4 in_pos;
layout(location = 1) in vec2 in_uv;
layout(set = 0, binding = 0) uniform Mats { mat4 mvp; };
layout(location = 0) out vec2 v_uv;

void main() {
    gl_Position = mvp * in_pos;
    v_uv        = in_uv;
}
```

Becomes:

```
# in_pos -> a0..a3   (driver-assigned)
# in_uv  -> a4..a5
# mvp at constant-buffer slot C0, offset 0
LDC.V4   v0,  C0, #0       # row 0
LDC.V4   v4,  C0, #16
LDC.V4   v8,  C0, #32
LDC.V4   v12, C0, #48
MUL4x4   v16, v0, a0       # mvp * pos
FMOV     o0,  v16           # gl_Position
FMOV     o1,  a4            # v_uv (linker collapses a4..a5 -> o1..o2)
FMOV     o2,  a5
HLT
```

### 4.2 GLSL fragment shader

```glsl
layout(location = 0) in vec2 v_uv;
layout(set = 0, binding = 1) uniform sampler2D tex;
layout(location = 0) out vec4 frag;

void main() {
    frag = texture(tex, v_uv);
}
```

Becomes:

```
# v_uv -> attribute slots 0..1 (varyings from VTX o1..o2)
INTRP.PC v0, slot=0        # interpolated u
INTRP.PC v1, slot=1        # interpolated v
TEX.2D   v4, v0, slot=0    # sample texture binding 0 with sampler 0
FBWR.C   slot=0, v4        # commit to render target 0
HLT
```

Notice: the driver did *not* emit a perspective divide, did *not* emit
interpolation arithmetic, did *not* emit a bilinear filter, did *not*
emit a blend.

---

## 5. API → Jade Mapping Cheat Sheet

### 5.1 OpenGL

| OpenGL concept | Jade mapping |
|---|---|
| GL context, default framebuffer | Driver-side state + `DSP.SETFB` for window FB. |
| Vertex array object | Driver-side attribute table; consumed by HW vertex fetch. |
| `glDrawElements` | Driver builds index stream; emits dispatch record. |
| `glUseProgram` | Driver swaps shader pointers and varying-link table. |
| `glBindTexture` | Driver writes texture-binding slot CSR. |
| `glBlendFunc` / `glBlendEquation` | Driver writes `BLEND_CFG*` CSR; HW `BLND.*` consumes it. |
| `glDepthFunc` | `Z_CMP` CSR. |
| `glViewport` | `VIEWPORT` CSR + `VIEW.XFORM`. |
| `glClear(GL_COLOR_BUFFER_BIT)` | `CLR.C` (HW fast-clear). |
| `glReadPixels` | Driver issues DMA from FB to host buffer. |
| `glFinish` | KMD waits for fence. |
| `glTexImage2D` | Driver allocates texture, programs descriptor, optionally `DMA.CPY` to upload. |
| `glXSwapBuffers` / `eglSwapBuffers` | Driver schedules `DSP.PRESENT` aligned with `DSP.VSYNC`. |
| Display lists, immediate mode | Driver-only; deprecated. |

### 5.2 Vulkan

| Vulkan concept | Jade mapping |
|---|---|
| `VkInstance` / `VkPhysicalDevice` / `VkDevice` | Driver objects; no HW state. |
| `VkCommandBuffer` (recording) | Driver builds command stream in a UMD buffer. |
| `vkCmdDraw` | Append CSR writes + a dispatch record. |
| `vkCmdBindPipeline` | Append CSR snapshot from the pipeline object. |
| `VkPipeline` | Driver-compiled blob: shader code + CSR script + binding layout. |
| `VkDescriptorSet` | Driver-side binding table; bound via CSR slot writes. |
| `vkCmdPipelineBarrier` | `FENCE` (mem visibility) + `BAR.WG` (execution) in the stream. |
| `vkCmdBeginRenderPass` | Driver: `CLR.*` for loadOp=CLEAR, `TILE.LOAD` for loadOp=LOAD. |
| `vkCmdEndRenderPass`   | `TILE.STORE` for storeOp=STORE, otherwise drop. |
| `VkSemaphore` (binary/timeline) | KMD timeline counter + dispatch dependency. |
| `VkFence` | KMD wavefront-complete IRQ. |
| `vkQueueSubmit` | Hand the stream + dep graph to KMD. |
| `VkSwapchainKHR`, `vkQueuePresentKHR` | Driver FB rotation + `DSP.PRESENT`. |
| Push constants | Driver writes to a reserved const-buffer slot before dispatch. |
| Specialization constants | Patched into shader code at pipeline-create time. |
| Subpasses | Compiled into tile-based render passes: a sequence of shaders sharing the on-chip tile buffer via `TILE.*`. |
| Multi-view (`VK_KHR_multiview`) | Driver duplicates dispatch per view; layer index goes into `a*`. |

### 5.3 OpenCL

| OpenCL concept | Jade mapping |
|---|---|
| `cl_context`, `cl_command_queue` | Driver objects + KMD queue. |
| `cl_program` / `cl_kernel` | Driver-compiled shader (CMP-stage). |
| `clEnqueueNDRangeKernel` | Driver lays out work-groups; KMD dispatches. |
| `__global` | `LDG.*` / `STG.*`. |
| `__local` | `LDS.*` / `STS.*`. |
| `__constant` | `LDC.*`. |
| `__private` | VGPRs first; spill to `LDL`/`STL`. |
| `barrier(CLK_LOCAL_MEM_FENCE)` | `BAR.WG`. |
| `atomic_add` etc. | `ATOM.*`. |
| `async_work_group_copy` | `DMA.CPY` + `DMA.WAIT`. |
| `get_global_id` / `get_local_id` | `CSRRD` of `TID.x/y/z`, `LANE_ID`. |
| `printf` | Ring buffer + `DBG.PR` drain. |
| `clEnqueueReadBuffer` | KMD-side DMA. |
| Sub-buffers, sub-devices | Driver-side. |
| OpenCL images | Same path as Vulkan/GL textures: `TEX*` for sampled images, `IMG.*` for storage images. |

---

## 6. Things the Driver Should Be Careful About

Bug-classes Jade specifically *exposes* to the driver because hardware
keeps the fast path narrow:

- **Binding-slot exhaustion** — when an app binds the 17th texture, the
  driver must materialize that across multiple draws. Don't silently
  drop bindings.
- **Register pressure** — only 32 VGPRs per lane. The compiler must
  honestly account; otherwise spills to `LDL`/`STL` will tank perf.
  Don't let optimization passes hide spill cost.
- **Helper-lane side effects** — `FRAG.DDX`/`DDY` only work because the
  2×2 quad keeps helper lanes alive. Loads/stores in helper lanes are
  *suppressed* by hardware, but **atomics are not**. The driver must
  guard atomics with `FRAG.HELPER` checks.
- **Divergence stack depth** — 8 entries. Nested branches deeper than
  that must be lowered to a register-resident mask by the compiler.
- **Display timing** — `DSP.PRESENT` queued during scan-out causes
  tearing. Always pair with `DSP.VSYNC` unless the app explicitly opts
  in to immediate present.
- **Render-target layout transitions** — Jade's ROP and TEX units want
  different memory layouts (tiled vs linear). Forgetting a `TILE.STORE`
  before a subsequent `TEX.2D` sample of the same RT will read garbage.
- **Bus alignment** — anything not aligned to 32 B costs an extra bus
  beat. Pad uniform structs; align attribute strides to 16 B; group
  vec4s contiguously.

---

## 7. Minimum Viable Driver

A first cut of the Jade driver, scoped to get a single OpenGL ES 2.0 /
Vulkan 1.0 / OpenCL 1.2 demo on screen, needs:

1. **KMD**: submission queue, fence IRQs, vblank IRQ, DMA, mode-setting
   wired to one connector with one default mode from EDID, page table
   plumbing for 32-B-aligned allocations.
2. **UMD core**: object table, simple suballocator (32-B aligned),
   command-stream builder with CSR-write + dispatch records,
   one binding-table per draw, simple fence/semaphore wrapper.
3. **Shader compiler**:
   - GLSL → IR (use an existing frontend, e.g. glslang).
   - SPIR-V → IR.
   - IR → Jade ISA backend implementing § 4's lowering rules.
   - Naive register allocator (linear scan) + spill to `LDL`/`STL`.
4. **WSI**: swap chain with two framebuffers, `DSP.PRESENT` on
   `DSP.VSYNC`, gamma LUT loaded from EDID.
5. **Validation**: at submission time, walk the command stream and reject
   anything that uses a binding slot the pipeline didn't declare —
   hardware will not catch it.

Optimizations (tile-based rendering via `TILE.*`, multi-RT, HDR
metadata, anisotropic filtering presets, BC/ASTC online transcoding,
async compute) come later.

---

## 8. Summary

The driver is a *translator*, not an executor. It translates each API's
state-machine bookkeeping into a tiny command stream of CSR writes,
binding-table updates, and shader dispatches. Everything visual — every
pixel of every triangle, every blended fragment, every scanned-out
frame — happens entirely below the driver, in the SMs and the
fixed-function blocks called out in `isa.md`.

If a driver patch makes a per-pixel decision, it is in the wrong place.
That decision belongs in the RTL.
