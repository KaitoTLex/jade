# Jade ISA Specification

**Jade** — *Just Another Display Engine*.

A display-first GPU ISA. Unlike Vortex (a RISC-V GPGPU) or TinyGPU (a minimal
compute-leaning teaching core), Jade pushes the graphics pipeline — rasterization,
texture sampling, interpolation, blending, and scan-out — *into hardware as
first-class instructions*, so the driver compiles a single shader stream against
a small, predictable ALU vocabulary and lets the fixed-function units do the
heavy lifting. The compute side (a small SIMT/wavefront machine) exists to feed
the display pipeline, not the other way around.

Everything in this document is the *hardware contract*. Anything the driver must
synthesize on top of these primitives is in `driver.md`.

---

## 1. Design Philosophy

| Principle | Consequence |
|---|---|
| **Display first, compute incidental** | Dedicated opcodes for rasterization, interpolation, blending, scan-out. |
| **RISC, fixed 32-bit instructions** | One instruction = 4 bytes. No prefix bytes, no variable encodings. |
| **SIMT execution** | A wavefront of 32 lanes executes one instruction per cycle. Divergence is managed by a per-SM mask stack. |
| **Push API redundancy into the driver** | The three big APIs (OpenGL/Vulkan/OpenCL) overlap heavily on state-machine bookkeeping. That bookkeeping is software. |
| **Pull display-relevant work into hardware** | Texture filtering, perspective-correct interpolation, blend modes, format packing/unpacking, sRGB conversion, scan-out: all fixed-function. |
| **Single addressable instruction = single retired operation** | No fused micro-ops, no horizontal SIMD inside a lane. Width comes from the wavefront, not the word. |

---

## 2. Execution Model

```
+-------------------------------------------------------+
|                    Jade GPU                           |
|                                                       |
|   +-------------------+     +-------------------+     |
|   |   SM 0            |     |   SM 1            |     |
|   |  +-------------+  |     |  +-------------+  |     |
|   |  | Wavefront 0 |  |     |  | Wavefront 0 |  |     |
|   |  | (32 lanes)  |  |     |  | (32 lanes)  |  |     |
|   |  +-------------+  |     |  +-------------+  |     |
|   |  | Wavefront 1 |  |     |  | Wavefront 1 |  |     |
|   |  +-------------+  |     |  +-------------+  |     |
|   |   ...             |     |   ...             |     |
|   +-------------------+     +-------------------+     |
|                                                       |
|   +----------+ +----------+ +------+ +----+ +----+    |
|   | Raster   | | Texture  | |Interp| |ROP | |Disp|    |
|   | Engine   | | Units    | | Unit | |Unit| |Out |    |
|   +----------+ +----------+ +------+ +----+ +----+    |
|                                                       |
|   +-------------------- L2 ---------------------+     |
|   +-------------------- MC ---------------------+     |
+-------------------------------------------------------+
```

- **Streaming Multiprocessor (SM):** issues one instruction per cycle to one
  wavefront. Holds the divergence mask stack, predicate file, and scalar GPRs.
- **Wavefront:** 32 lanes, executes in lockstep. Each lane has its own VGPR file.
- **Lane:** the "thread" abstraction; one per pixel/vertex/work-item.
- **Fixed-function blocks:** Raster Engine, Texture Units, Interpolator, ROP/Blend
  Unit, Display Controller. Driven by dedicated opcodes, not memory-mapped state.

Wavefronts are launched by one of three *dispatchers*:
- **VTX dispatch:** one lane per vertex, runs the vertex shader.
- **FRAG dispatch:** one lane per fragment, runs the fragment shader. Launched
  by the Raster Engine after `RAST` instructions.
- **CMP dispatch:** one lane per work-item, runs a compute kernel.

---

## 3. Register File

Per *lane*:

| Name | Count | Width | Role |
|---|---|---|---|
| `v0`–`v31` | 32 | 32 b | Vector GPRs. Each lane has its own copy. Used for per-thread state. |
| `a0`–`a15` | 16 | 32 b | Attribute registers. Holds interpolated/dispatched attributes (vertex inputs for VTX, fragment inputs for FRAG, work-item args for CMP). Read-only inside the shader. |
| `o0`–`o7` | 8 | 32 b | Output registers. The values committed to the next stage (varyings out of VTX, color/depth out of FRAG). |

Per *wavefront* (shared across all 32 lanes):

| Name | Count | Width | Role |
|---|---|---|---|
| `s0`–`s15` | 16 | 32 b | Scalar GPRs. Uniform across the wavefront. |
| `p0`–`p7` | 8 | 1 b × 32 lanes | Predicate registers. `p0` is the **active mask** (managed by the divergence stack). `p1`–`p7` are general. |
| `pc` | 1 | 32 b | Program counter. |
| `lr` | 1 | 32 b | Link register (set by `CALL`, read by `RET`). |
| `mstk` | 8 | 32 b × 32 lanes | Mask stack (HW-managed for `DIV`/`CONV`). |

Per *SM*, read via `CSR`:

| CSR | Role |
|---|---|
| `tid.x/y/z` | Thread (lane) ID within the work-group / draw call. |
| `wid` | Wavefront ID within the SM. |
| `smid` | SM ID within the GPU. |
| `lid` | Lane ID within the wavefront (0–31). |
| `cycle` | Free-running 64-bit cycle counter (low half here, high in `cycle_h`). |
| `fb_base` | Current bound framebuffer base address. |
| `zb_base` | Current bound depth buffer base address. |

**Binding slots** (programmed by the driver, referenced by index):

| Slot table | Count | Used by |
|---|---|---|
| Texture slots `T0`–`T15` | 16 | `TEX*` instructions |
| Sampler slots `S0`–`S7` | 8 | `TEX*` instructions |
| Constant-buffer slots `C0`–`C7` | 8 | `LDC` |
| Storage-buffer slots `B0`–`B7` | 8 | `LDG`/`STG` |
| Render-target slots `R0`–`R3` | 4 | `FBWR`, `BLEND` |

---

## 4. Instruction Format

All instructions are exactly **32 bits**, little-endian. There are four formats.

### 4.1 R-type (register-register-register)

```
 31      26 25  21 20  16 15  11 10   0
+----------+------+------+------+--------+
|  opcode  |  rd  | rs1  | rs2  | funct11|
+----------+------+------+------+--------+
   6         5      5      5       11
```

`funct11` selects the sub-operation within the major opcode. Used by ALU,
SFU, conversion, packing, comparison.

### 4.2 I-type (register-register-immediate)

```
 31      26 25  21 20  16 15            0
+----------+------+------+----------------+
|  opcode  |  rd  | rs1  |    imm16       |
+----------+------+------+----------------+
   6         5      5         16
```

Used for load/store offsets, immediates, CSR access, branch targets relative
to a register.

### 4.3 B-type (branch / dispatch)

```
 31      26 25  21 20                    0
+----------+------+-----------------------+
|  opcode  | rs1  |       offset21        |
+----------+------+-----------------------+
   6         5             21
```

`offset21` is sign-extended and shifted left by 2 (instructions are
4-aligned) — gives ±4 MiB branch range. Used by `BR`, `CALL`, `DIV`, `CONV`.

### 4.4 X-type (fixed-function dispatch)

```
 31      26 25  21 20  16 15  11 10   7 6     0
+----------+------+------+------+------+-------+
|  opcode  |  rd  | rs1  | rs2  | slot |funct7 |
+----------+------+------+------+------+-------+
   6         5      5      5      4      7
```

Used by texture, raster, interp, blend, framebuffer, display ops. `slot`
indexes a binding table (texture, sampler, render target). `funct7` selects
the variant (e.g., `TEX` vs `TEXL` vs `TEXD`).

### 4.5 Predication

Jade does *not* carry a per-instruction predicate field. Per-lane control
flow is handled by the **divergence stack** (`DIV`/`CONV` instructions):
when lanes diverge, the active mask `p0` is updated and the previous mask
is pushed. `CONV` pops. Most instructions read `p0` implicitly to gate
side effects (writes, stores, sample requests).

Predicate registers `p1`–`p7` are used as comparison results and as guards
on `PRED.MOV`, `PRED.SEL`, conditional `BR`, etc.

---

## 5. Major Opcode Map

| Major | Mnemonic prefix | Class |
|---|---|---|
| `0x00` | `SYS`   | System: NOP, HLT, FENCE, BARRIER, KILL |
| `0x01` | `SALU`  | Scalar ALU (operates on `s*`) |
| `0x02` | `VALU.I`| Vector integer ALU |
| `0x03` | `VALU.F`| Vector FP ALU |
| `0x04` | `SFU`   | Special function unit: RCP, RSQRT, SIN, COS, EXP2, LOG2 |
| `0x05` | `CMP`   | Comparison → predicate write |
| `0x06` | `CVT`   | Numeric conversion (int↔fp, half↔float, signed/unsigned) |
| `0x07` | `PACK`  | Format pack/unpack (RGBA8, RGB10A2, F16x2, sRGB) |
| `0x08` | `LDG`   | Load from global / storage buffer |
| `0x09` | `STG`   | Store to global / storage buffer |
| `0x0A` | `LDS`   | Load from shared (SM-local) memory |
| `0x0B` | `STS`   | Store to shared memory |
| `0x0C` | `LDC`   | Load from constant buffer |
| `0x0D` | `LDL`   | Load from lane-private stack |
| `0x0E` | `STL`   | Store to lane-private stack |
| `0x0F` | `ATOM`  | Atomic op (add, min, max, cas, exch) on global/shared |
| `0x10` | `BR`    | Branch (uniform, taken when `s_rs1` matches condition) |
| `0x11` | `CALL`  | Subroutine call, writes `lr` |
| `0x12` | `RET`   | Subroutine return |
| `0x13` | `DIV`   | Divergent branch: push mask, take taken-path lanes |
| `0x14` | `CONV`  | Converge: pop mask, run not-taken lanes (or finish join) |
| `0x15` | `BAR`   | Barrier: wavefront-local or work-group-wide sync |
| `0x16` | `WAVE`  | Wavefront collectives: VOTE, BALLOT, SHFL, BCAST, ANY, ALL |
| `0x17` | `PRED`  | Predicate ops: set, copy, AND/OR/NOT between predicates |
| `0x18` | `TEX`   | Texture sample (filtered, derivative-LOD) |
| `0x19` | `TEXL`  | Texture sample with explicit LOD / bias / derivatives / gather / fetch |
| `0x1A` | `IMG`   | Image load/store (unfiltered, typed) |
| `0x1B` | `RAST`  | Raster engine: setup, dispatch, edge test |
| `0x1C` | `INTRP` | Interpolation: PC (perspective-correct), LIN, FLAT, CENTROID |
| `0x1D` | `FRAG`  | Fragment ops: KILL/DISCARD, derivatives (DDX/DDY), helper-lane checks |
| `0x1E` | `ZTST`  | Depth test (early or late), depth write |
| `0x1F` | `STCL`  | Stencil test + op |
| `0x20` | `BLND`  | Blend with current render target |
| `0x21` | `FBWR`  | Framebuffer write (commits color + optional depth) |
| `0x22` | `ROP`   | Logic raster ops (for 2D blits / bitblts) |
| `0x23` | `CLR`   | Fast clear: color, depth, stencil |
| `0x24` | `DSP`   | Display controller: scan, vsync wait, present, mode set |
| `0x25` | `CUR`   | Hardware cursor: position, image, enable |
| `0x26` | `PRIM`  | Primitive assembly: TRI, LINE, POINT, STRIP, FAN |
| `0x27` | `CLIP`  | Clip test against frustum / user clip planes |
| `0x28` | `CULL`  | Face culling (CW/CCW), zero-area, scissor reject |
| `0x29` | `VIEW`  | Viewport / scissor application |
| `0x2A` | `MSAA`  | Multisample coverage / resolve |
| `0x2B` | `TILE`  | Tile-buffer load/store (for tile-based rendering) |
| `0x2C` | `DMA`   | Asynchronous DMA copy between memory regions |
| `0x2D` | `DOT`   | Geometric helpers: DP3, DP4 |
| `0x2E` | `GEOM`  | Geometric helpers: CROSS, LEN, NRM, REFL, RFRCT |
| `0x2F` | `MAT`   | Matrix helpers: MUL4x4, MUL3x3, TRANSPOSE (intrinsic forms) |
| `0x30` | `CSR`   | CSR read / write |
| `0x31` | `DBG`   | Debug: print, breakpoint, performance counter read |
| `0x32`–`0x3F` | — | **Reserved for extensions** (e.g., ray traversal, video decode) |

---

## 6. Instruction Tables

In all tables: every instruction is **32 bits** wide (RISC fixed length). The
**Fmt** column gives the format (R / I / B / X). `funct11` / `funct7` are
shown in hex.

### 6.1 `SYS` — System (op `0x00`)

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `NOP`     | `0x000` | R | 32 | No operation. |
| `HLT`     | `0x001` | R | 32 | Halt the wavefront. |
| `KILL`    | `0x002` | R | 32 | Mark every active lane as terminated (for VTX/FRAG early-out). |
| `FENCE`   | `0x010` | R | 32 | Memory fence; orders prior loads/stores. |
| `BARRIER` | `0x020` | R | 32 | Work-group barrier (sync all wavefronts in the work-group). |
| `WAVEBAR` | `0x021` | R | 32 | Wavefront-internal reconverge — force all lanes to the same PC. |

### 6.2 `SALU` — Scalar ALU (op `0x01`)

Operates on `s*` registers. All RISC three-register form.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `SADD`  | `0x000` | R | 32 | `sd = s1 + s2` (32-bit two's complement, no overflow trap). |
| `SADDI` | `0x001` | I | 32 | `sd = s1 + sext(imm16)`. |
| `SSUB`  | `0x002` | R | 32 | `sd = s1 - s2`. |
| `SMUL`  | `0x003` | R | 32 | `sd = (s1 * s2) & 0xFFFFFFFF`. |
| `SMULH` | `0x004` | R | 32 | `sd = high32(s1 * s2)` (signed). |
| `SDIV`  | `0x005` | R | 32 | Signed integer divide. |
| `SREM`  | `0x006` | R | 32 | Signed remainder. |
| `SAND`  | `0x010` | R | 32 | Bitwise AND. |
| `SOR`   | `0x011` | R | 32 | Bitwise OR. |
| `SXOR`  | `0x012` | R | 32 | Bitwise XOR. |
| `SNOT`  | `0x013` | R | 32 | `sd = ~s1`. |
| `SSHL`  | `0x020` | R | 32 | Logical shift left. |
| `SSHR`  | `0x021` | R | 32 | Logical shift right. |
| `SSRA`  | `0x022` | R | 32 | Arithmetic shift right. |
| `SMIN`  | `0x030` | R | 32 | Signed min. |
| `SMAX`  | `0x031` | R | 32 | Signed max. |
| `SMOV`  | `0x040` | R | 32 | `sd = s1`. |
| `SLI`   | `0x041` | I | 32 | `sd = sext(imm16)` (load immediate). |
| `SLUI`  | `0x042` | I | 32 | `sd = imm16 << 16` (load upper). |

### 6.3 `VALU.I` — Vector Integer ALU (op `0x02`)

Per-lane integer ops. Reads `v*`, writes `v*`. `s*` may be used as a
broadcast operand by setting the high bit of the corresponding `funct11`
field (`.S` variants).

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `VADD`   | `0x000` | R | 32 | `vd[i] = v1[i] + v2[i]` for active `i`. |
| `VADD.S` | `0x001` | R | 32 | `vd[i] = v1[i] + s2`. |
| `VSUB`   | `0x002` | R | 32 | Per-lane subtract. |
| `VMUL`   | `0x003` | R | 32 | 32×32→32 multiply. |
| `VMULH`  | `0x004` | R | 32 | High half of signed multiply. |
| `VMAD`   | `0x005` | R | 32 | `vd = v1*v2 + vd` (integer multiply-accumulate). |
| `VAND`   | `0x010` | R | 32 | Bitwise AND. |
| `VOR`    | `0x011` | R | 32 | Bitwise OR. |
| `VXOR`   | `0x012` | R | 32 | Bitwise XOR. |
| `VSHL`   | `0x020` | R | 32 | Logical shift left. |
| `VSHR`   | `0x021` | R | 32 | Logical shift right. |
| `VSRA`   | `0x022` | R | 32 | Arithmetic shift right. |
| `VMIN`   | `0x030` | R | 32 | Signed min. |
| `VMAX`   | `0x031` | R | 32 | Signed max. |
| `VMOV`   | `0x040` | R | 32 | Copy. |
| `VBCST`  | `0x041` | R | 32 | Broadcast `s1` into every active lane of `vd`. |
| `VLI`    | `0x042` | I | 32 | Load sign-extended `imm16` into all active lanes of `vd`. |

### 6.4 `VALU.F` — Vector FP ALU (op `0x03`)

IEEE-754 single precision, round-to-nearest-even, flush-denormals-to-zero
(driver may flip via CSR). NaN propagation is "first NaN wins".

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `FADD`  | `0x000` | R | 32 | Per-lane FP add. |
| `FSUB`  | `0x001` | R | 32 | FP subtract. |
| `FMUL`  | `0x002` | R | 32 | FP multiply. |
| `FDIV`  | `0x003` | R | 32 | FP divide (microcoded via SFU). |
| `FMAD`  | `0x004` | R | 32 | Non-fused multiply-add (`(v1*v2)+vd`, two roundings). |
| `FFMA`  | `0x005` | R | 32 | Fused multiply-add (single rounding). |
| `FMIN`  | `0x006` | R | 32 | min, returning the non-NaN operand if exactly one is NaN. |
| `FMAX`  | `0x007` | R | 32 | max, same NaN rule. |
| `FABS`  | `0x008` | R | 32 | `vd = |v1|`. |
| `FNEG`  | `0x009` | R | 32 | Flip sign bit. |
| `FSAT`  | `0x00A` | R | 32 | Saturate to `[0.0, 1.0]` — common enough in shaders to deserve its own slot. |
| `FFLR`  | `0x010` | R | 32 | floor. |
| `FCEL`  | `0x011` | R | 32 | ceil. |
| `FRND`  | `0x012` | R | 32 | round-to-nearest-even. |
| `FTRC`  | `0x013` | R | 32 | truncate. |
| `FFRC`  | `0x014` | R | 32 | fract(x) = x - floor(x). |
| `FMOV`  | `0x040` | R | 32 | Copy. |
| `FLI`   | `0x041` | I | 32 | Load `imm16` as `f16`, expanded to `f32`. |

### 6.5 `SFU` — Special Function Unit (op `0x04`)

One-cycle-issue, multi-cycle-latency transcendentals. Hardware uses
range reduction + minimax polynomials. Operand and result are `f32`.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `RCP`    | `0x000` | R | 32 | 1/x, ~22-bit mantissa accurate. |
| `RSQRT`  | `0x001` | R | 32 | 1/sqrt(x). |
| `SQRT`   | `0x002` | R | 32 | sqrt(x). |
| `EXP2`   | `0x003` | R | 32 | 2^x. |
| `LOG2`   | `0x004` | R | 32 | log2(x). |
| `SIN`    | `0x005` | R | 32 | sin(x), x in radians. |
| `COS`    | `0x006` | R | 32 | cos(x). |
| `TAN`    | `0x007` | R | 32 | tan(x). |
| `ATAN`   | `0x008` | R | 32 | atan(x). |
| `ATAN2`  | `0x009` | R | 32 | atan2(v1, v2). |
| `POW`    | `0x00A` | R | 32 | pow(v1, v2) — internal `EXP2(v2 * LOG2(v1))`. |

### 6.6 `CMP` — Comparison (op `0x05`)

Result lands in a predicate register, **not** a VGPR. `pd` is encoded in
bits `[10:8]` of `funct11`.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `CMPEQ.I` | `0x000` | R | 32 | `pd[i] = (v1[i] == v2[i])`. |
| `CMPNE.I` | `0x001` | R | 32 | not-equal. |
| `CMPLT.I` | `0x002` | R | 32 | signed less-than. |
| `CMPLE.I` | `0x003` | R | 32 | signed less-or-equal. |
| `CMPLTU`  | `0x004` | R | 32 | unsigned less-than. |
| `CMPEQ.F` | `0x010` | R | 32 | FP ordered equal. |
| `CMPNE.F` | `0x011` | R | 32 | FP ordered not-equal. |
| `CMPLT.F` | `0x012` | R | 32 | FP ordered less-than. |
| `CMPLE.F` | `0x013` | R | 32 | FP ordered less-or-equal. |
| `CMPUO`   | `0x014` | R | 32 | unordered (either operand NaN). |
| `CMPS.EQ` | `0x020` | R | 32 | Scalar compare on `s*`, writes a uniform predicate bit. |

### 6.7 `CVT` — Conversion (op `0x06`)

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `I2F`    | `0x000` | R | 32 | signed int32 → f32. |
| `U2F`    | `0x001` | R | 32 | unsigned int32 → f32. |
| `F2I`    | `0x002` | R | 32 | f32 → signed int32 (round-to-zero). |
| `F2U`    | `0x003` | R | 32 | f32 → unsigned int32. |
| `F2H`    | `0x010` | R | 32 | f32 → f16 (packed in low 16 bits). |
| `H2F`    | `0x011` | R | 32 | f16 → f32. |
| `S2U16`  | `0x020` | R | 32 | sign-extend low 16 bits. |
| `Z2U16`  | `0x021` | R | 32 | zero-extend low 16 bits. |
| `BSWAP`  | `0x030` | R | 32 | Byte swap (endian flip). |

### 6.8 `PACK` — Format pack/unpack (op `0x07`)

These are *the* reason display drivers don't have to micro-ops format
conversion. Each one is fully fixed-function inside the SM.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `PK.RGBA8`   | `0x000` | R | 32 | Pack four `f32` ∈ [0,1] from `v1..v1+3` into one `RGBA8` word in `vd`. |
| `UPK.RGBA8`  | `0x001` | R | 32 | Unpack `RGBA8` from `v1` into `vd..vd+3` as `f32`. |
| `PK.RGB10A2` | `0x002` | R | 32 | Pack to 10/10/10/2 unorm. |
| `UPK.RGB10A2`| `0x003` | R | 32 | Unpack 10/10/10/2 unorm. |
| `PK.RG11B10` | `0x004` | R | 32 | Pack to 11/11/10 unsigned float. |
| `UPK.RG11B10`| `0x005` | R | 32 | Unpack 11/11/10 unsigned float. |
| `PK.F16x2`   | `0x006` | R | 32 | Pack `(v1, v2)` `f32` pair into two `f16` halves. |
| `UPK.F16x2`  | `0x007` | R | 32 | Unpack two `f16` halves into `vd, vd+1`. |
| `PK.SNORM8`  | `0x008` | R | 32 | Pack four `f32` ∈ [-1,1] → 4×`s8`. |
| `UPK.SNORM8` | `0x009` | R | 32 | Unpack 4×`s8` → 4×`f32`. |
| `PK.D24S8`   | `0x010` | R | 32 | Pack depth `f32` + stencil `u8` to D24S8. |
| `UPK.D24S8`  | `0x011` | R | 32 | Unpack D24S8 to f32 depth + u8 stencil. |
| `SRGB2LIN`   | `0x020` | R | 32 | Per-channel sRGB → linear (LUT in HW). |
| `LIN2SRGB`   | `0x021` | R | 32 | Linear → sRGB (HW). |

### 6.9 Memory — `LDG`/`STG`/`LDS`/`STS`/`LDC`/`LDL`/`STL` (ops `0x08`–`0x0E`)

All loads/stores are I-type: `addr = base + sext(imm16)`. Width selected
by funct field in the opcode subspace (only enumerated for `LDG`/`STG`
below; the others follow the same pattern).

| Mnemonic | Op | funct | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `LDG.B`   | `0x08` | `0x00` | I | 32 | Load 8-bit byte, sign-extended. |
| `LDG.BU`  | `0x08` | `0x01` | I | 32 | Load 8-bit byte, zero-extended. |
| `LDG.H`   | `0x08` | `0x02` | I | 32 | Load 16-bit half-word, sign-extended. |
| `LDG.HU`  | `0x08` | `0x03` | I | 32 | Load 16-bit half-word, zero-extended. |
| `LDG.W`   | `0x08` | `0x04` | I | 32 | Load 32-bit word. |
| `LDG.V2`  | `0x08` | `0x05` | I | 32 | Load 2×32 (`vd, vd+1`). |
| `LDG.V4`  | `0x08` | `0x06` | I | 32 | Load 4×32 (vector load — used heavily for vertex attributes). |
| `STG.B`   | `0x09` | `0x00` | I | 32 | Store byte. |
| `STG.H`   | `0x09` | `0x02` | I | 32 | Store half-word. |
| `STG.W`   | `0x09` | `0x04` | I | 32 | Store word. |
| `STG.V4`  | `0x09` | `0x06` | I | 32 | Store 4×32. |
| `LDS.W`   | `0x0A` | `0x04` | I | 32 | Load shared (SM-local SRAM). |
| `STS.W`   | `0x0B` | `0x04` | I | 32 | Store shared. |
| `LDC.W`   | `0x0C` | `0x04` | I | 32 | Load from a constant buffer slot (`rs1` indexes `C0..C7`, `imm16` is the offset). |
| `LDL.W`   | `0x0D` | `0x04` | I | 32 | Load from lane-private stack (`sp + imm16`). |
| `STL.W`   | `0x0E` | `0x04` | I | 32 | Store to lane-private stack. |

### 6.10 `ATOM` — Atomic Memory Ops (op `0x0F`)

I-type. Operates on global or shared memory; address space picked by
`funct11`.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `ATOM.ADD`  | `0x000` | I | 32 | atomic add, returns old value. |
| `ATOM.SUB`  | `0x001` | I | 32 | atomic subtract. |
| `ATOM.AND`  | `0x002` | I | 32 | atomic and. |
| `ATOM.OR`   | `0x003` | I | 32 | atomic or. |
| `ATOM.XOR`  | `0x004` | I | 32 | atomic xor. |
| `ATOM.MIN`  | `0x005` | I | 32 | atomic signed min. |
| `ATOM.MAX`  | `0x006` | I | 32 | atomic signed max. |
| `ATOM.XCH`  | `0x007` | I | 32 | atomic exchange. |
| `ATOM.CAS`  | `0x008` | R | 32 | compare-and-swap: `vd_old = cas(*addr, expected=v1, new=v2)`. |
| `ATOM.INC`  | `0x009` | I | 32 | atomic increment with wrap. |

### 6.11 Control Flow — `BR`/`CALL`/`RET`/`DIV`/`CONV` (ops `0x10`–`0x14`)

| Mnemonic | Op | funct | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `BR`       | `0x10` | `0x00` | B | 32 | Unconditional uniform branch. |
| `BR.EQZ`   | `0x10` | `0x01` | B | 32 | Branch if `s_rs1 == 0`. |
| `BR.NEZ`   | `0x10` | `0x02` | B | 32 | Branch if `s_rs1 != 0`. |
| `BR.P`     | `0x10` | `0x03` | B | 32 | Branch if predicate `p_rs1` true on **any** active lane. |
| `BR.PA`    | `0x10` | `0x04` | B | 32 | Branch if predicate `p_rs1` true on **all** active lanes. |
| `CALL`     | `0x11` | `0x00` | B | 32 | Push return PC into `lr`, jump. |
| `RET`      | `0x12` | `0x00` | R | 32 | Jump to `lr`. |
| `DIV`      | `0x13` | `0x00` | B | 32 | Divergent branch on predicate `rs1`. Lanes where it is true take the branch; the rest are pushed onto the divergence stack with a join-PC = `offset21`. |
| `CONV`     | `0x14` | `0x00` | R | 32 | Convergence point. Pops the top of the divergence stack. |

### 6.12 `BAR` / `WAVE` — Sync & Collectives (ops `0x15`, `0x16`)

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `BAR.WG`    | `0x15` | `0x000` | R | 32 | Work-group barrier (all wavefronts in the work-group). |
| `BAR.WV`    | `0x15` | `0x001` | R | 32 | Wavefront-local sync (forces reconverge). |
| `WAVE.VOTE.ANY`| `0x16` | `0x000` | R | 32 | `sd = (any active lane's p_rs1 == 1)`. |
| `WAVE.VOTE.ALL`| `0x16` | `0x001` | R | 32 | `sd = (all active lanes' p_rs1 == 1)`. |
| `WAVE.BALLOT`  | `0x16` | `0x002` | R | 32 | `sd = bitmask of lanes where p_rs1 == 1`. |
| `WAVE.SHFL`    | `0x16` | `0x010` | R | 32 | Shuffle: `vd[i] = v1[v2[i] mod 32]`. |
| `WAVE.SHFL.UP` | `0x16` | `0x011` | R | 32 | Shuffle up by `s2`. |
| `WAVE.SHFL.DN` | `0x16` | `0x012` | R | 32 | Shuffle down by `s2`. |
| `WAVE.SHFL.XOR`| `0x16` | `0x013` | R | 32 | Shuffle XOR by `s2` (butterfly). |
| `WAVE.BCAST`   | `0x16` | `0x020` | R | 32 | Broadcast `v1[s2]` to all active lanes of `vd`. |

### 6.13 `PRED` — Predicate ops (op `0x17`)

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `PRED.AND` | `0x000` | R | 32 | `pd = p1 AND p2`. |
| `PRED.OR`  | `0x001` | R | 32 | `pd = p1 OR p2`. |
| `PRED.XOR` | `0x002` | R | 32 | `pd = p1 XOR p2`. |
| `PRED.NOT` | `0x003` | R | 32 | `pd = NOT p1`. |
| `PRED.MOV` | `0x004` | R | 32 | `pd = p1`. |
| `PRED.SET` | `0x005` | R | 32 | Set all lanes of `pd` to bit 0 of `s1`. |
| `PRED.SEL` | `0x010` | R | 32 | `vd[i] = p_rs2[i] ? v_rs1[i] : vd[i]` (per-lane select via predicate). |

### 6.14 `TEX` / `TEXL` — Texture Sampling (ops `0x18`, `0x19`)

X-type. `slot` is the binding slot for the texture; the sampler is
encoded in `funct7`'s low 3 bits. Coordinates come from `v_rs1..v_rs1+N-1`
where N depends on dimensionality. Result lands in `vd..vd+3` as four
`f32` channels (R, G, B, A).

Hardware handles: address calculation, wrap modes, mipmap selection,
bilinear / trilinear / anisotropic filtering, sRGB→linear on read,
compressed format decode (BC1–BC7 / ASTC / ETC2 — implementation-defined
which formats are present).

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `TEX.1D`     | `0x18` | `0x00` | X | 32 | Sample 1D texture, LOD from screen-space derivatives (helper lanes). |
| `TEX.2D`     | `0x18` | `0x01` | X | 32 | Sample 2D texture, LOD from ddx/ddy of UV. |
| `TEX.3D`     | `0x18` | `0x02` | X | 32 | Sample 3D texture. |
| `TEX.CUBE`   | `0x18` | `0x03` | X | 32 | Sample cube map. |
| `TEX.ARR2D`  | `0x18` | `0x04` | X | 32 | Sample 2D array (uses 3-component coord, w = layer). |
| `TEX.SHDW`   | `0x18` | `0x05` | X | 32 | Sample depth texture with hardware compare (PCF). |
| `TEXL.LOD`   | `0x19` | `0x00` | X | 32 | Sample with explicit LOD (`v_rs2` = LOD as f32). |
| `TEXL.BIAS`  | `0x19` | `0x01` | X | 32 | Sample with LOD bias added to derivative LOD. |
| `TEXL.GRAD`  | `0x19` | `0x02` | X | 32 | Sample with explicit ddx/ddy gradients. |
| `TEXL.FETCH` | `0x19` | `0x03` | X | 32 | Unfiltered integer-coordinate fetch (texelFetch). |
| `TEXL.GATHER`| `0x19` | `0x04` | X | 32 | Gather 4 texels around the sample point — used for PCF / area sampling. Returns 4 reds (or any chosen channel). |
| `TEXL.QUERY` | `0x19` | `0x05` | X | 32 | Query texture size/mip count into `vd`. |

### 6.15 `IMG` — Image Load/Store (op `0x1A`)

Typed, unfiltered, can be written from any shader stage. Address is
`(x, y, z, layer)` in `v_rs1..v_rs1+3` depending on dimensionality.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `IMG.LD.2D`  | `0x00` | X | 32 | Load typed pixel from 2D image. Format conversion to f32 is HW. |
| `IMG.LD.3D`  | `0x01` | X | 32 | 3D image load. |
| `IMG.ST.2D`  | `0x10` | X | 32 | Store typed pixel to 2D image. |
| `IMG.ST.3D`  | `0x11` | X | 32 | 3D image store. |
| `IMG.ATOM`   | `0x20` | X | 32 | Atomic on image (add, min, max, exch). |

### 6.16 `RAST` — Rasterization (op `0x1B`)

The Raster Engine is treated as a coprocessor. The vertex shader emits
positions to `o0..o3`; a `PRIM` instruction assembles them into
primitives; then `RAST.SETUP` followed by `RAST.DISPATCH` launches
fragment wavefronts.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `RAST.SETUP.TRI`  | `0x00` | X | 32 | Pass the three clip-space positions in `o0..o2` to the raster engine; HW computes screen-space transform, edge equations, 1/w, depth slopes, and barycentric basis. |
| `RAST.SETUP.LINE` | `0x01` | X | 32 | Line setup with width. |
| `RAST.SETUP.PT`   | `0x02` | X | 32 | Point setup with size. |
| `RAST.DISPATCH`   | `0x10` | X | 32 | Launch fragment wavefronts for the previously set-up primitive. Each fragment lane is initialized with barycentrics, screen coords, 1/w in `a0..a3`. |
| `RAST.SCISSOR`    | `0x20` | X | 32 | Set scissor rect (from `v_rs1`, `v_rs2`). |
| `RAST.EDGE`       | `0x30` | X | 32 | Per-fragment edge equation evaluation (used by tile-binning code). |

### 6.17 `INTRP` — Interpolation (op `0x1C`)

Issued by fragment shaders. The hardware Interpolator reads the
per-vertex attribute, the barycentrics in `a0..a2`, and the perspective
divisor in `a3`, then returns the interpolated value.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `INTRP.PC`  | `0x00` | X | 32 | Perspective-correct interpolation. `slot` selects the attribute index. |
| `INTRP.LIN` | `0x01` | X | 32 | Linear (screen-space) interpolation — for noperspective varyings. |
| `INTRP.FLAT`| `0x02` | X | 32 | Flat: take the provoking vertex's value, no interpolation. |
| `INTRP.CTRD`| `0x03` | X | 32 | Centroid-correct interpolation (MSAA-safe). |
| `INTRP.SMPL`| `0x04` | X | 32 | Per-sample interpolation. |

### 6.18 `FRAG` — Fragment-Stage Ops (op `0x1D`)

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `FRAG.DISCARD` | `0x00` | R | 32 | Mark active lanes as discarded — they stop contributing to color/depth writes but remain as helpers for derivatives until end of quad. |
| `FRAG.DDX`     | `0x10` | R | 32 | Screen-space derivative dx of `v_rs1`, computed across the 2×2 helper quad. |
| `FRAG.DDY`     | `0x11` | R | 32 | Screen-space derivative dy. |
| `FRAG.DDX.F`   | `0x12` | R | 32 | Fine dx (per-pixel, requires sample shading). |
| `FRAG.DDY.F`   | `0x13` | R | 32 | Fine dy. |
| `FRAG.FWIDTH`  | `0x14` | R | 32 | `abs(ddx(x)) + abs(ddy(x))`. |
| `FRAG.HELPER`  | `0x20` | R | 32 | `pd = (this lane is a helper lane)` — used to skip side effects. |

### 6.19 `ZTST` / `STCL` — Depth & Stencil (ops `0x1E`, `0x1F`)

Per-fragment depth and stencil are fully fixed-function. Driver
configures the comparator, write mask, and stencil ops via CSRs; the
shader just commits.

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `ZTST.EARLY` | `0x1E` | `0x00` | X | 32 | Early-Z test against current z-buffer slot; lanes that fail are masked off. Runs before fragment shader if the shader does not write depth/discard. |
| `ZTST.LATE`  | `0x1E` | `0x01` | X | 32 | Late-Z test; runs after the shader. |
| `ZTST.WR`    | `0x1E` | `0x02` | X | 32 | Write the depth value in `v_rs1` to the z-buffer at the current fragment. |
| `STCL.TEST`  | `0x1F` | `0x00` | X | 32 | Stencil test using current CSR-configured ref/mask/op. |
| `STCL.OP`    | `0x1F` | `0x01` | X | 32 | Apply stencil operation (KEEP/ZERO/REPLACE/INCR/DECR/INVERT) per active lane. |
| `STCL.WR`    | `0x1F` | `0x02` | X | 32 | Commit stencil value. |

### 6.20 `BLND` — Blending (op `0x20`)

Standard OpenGL/Vulkan blend modes. The Render Output Unit (ROP)
fetches the destination RGBA at the current fragment, combines with the
source from `v_rs1..v_rs1+3`, and yields the blended result in
`vd..vd+3` (or writes directly with `FBWR`).

`slot` picks the render target. `funct7` encodes the blend equation
(ADD/SUB/REV_SUB/MIN/MAX) and the factor pair (SRC/DST/ONE/ZERO/
SRC_ALPHA/ONE_MINUS_SRC_ALPHA/CONSTANT/etc.) via a lookup table
configured at pipeline-bind time. The result is the cleanly blended
RGBA, ready for `FBWR`.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `BLND.CFG` | `0x00` | X | 32 | (driver-issued, not in shader) — sets the blend table entry for `slot`. |
| `BLND.SRC` | `0x10` | X | 32 | Blend source RGBA from `v_rs1..v_rs1+3` with current RT, result to `vd..vd+3`. |
| `BLND.RB`  | `0x11` | X | 32 | Blend using "dual-source" — `v_rs1` is color0 and `v_rs2` is color1. |
| `BLND.LOG` | `0x20` | X | 32 | Logical-op blend (XOR/AND/etc.) — driven by `ROP`. |

### 6.21 `FBWR` / `ROP` / `CLR` (ops `0x21`–`0x23`)

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `FBWR.C`    | `0x21` | `0x00` | X | 32 | Commit color RGBA from `v_rs1..v_rs1+3` to render-target `slot` at current fragment coord. |
| `FBWR.CD`   | `0x21` | `0x01` | X | 32 | Commit color + depth in one go. |
| `FBWR.MRT`  | `0x21` | `0x02` | X | 32 | Commit to multiple render targets (mask in `s_rs2`). |
| `ROP.COPY`  | `0x22` | `0x00` | X | 32 | Raster-op copy (2D blit) between two image slots. |
| `ROP.XOR`   | `0x22` | `0x01` | X | 32 | XOR raster-op. |
| `ROP.ALPHA` | `0x22` | `0x02` | X | 32 | Alpha-blit. |
| `CLR.C`     | `0x23` | `0x00` | X | 32 | Fast clear color buffer in slot to `(v_rs1..v_rs1+3)`. |
| `CLR.D`     | `0x23` | `0x01` | X | 32 | Fast clear depth to `v_rs1`. |
| `CLR.S`     | `0x23` | `0x02` | X | 32 | Fast clear stencil to low byte of `v_rs1`. |
| `CLR.CDS`   | `0x23` | `0x03` | X | 32 | Clear color + depth + stencil in one cycle (HW compresses). |

### 6.22 `DSP` — Display Controller (op `0x24`)

This is the most important opcode group separating Jade from a GPGPU.
The Display Controller drives the scan-out engine and HDMI/DP TX.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `DSP.MODE`    | `0x00` | X | 32 | Set display mode: width × height × refresh × format. Params in `s_rs1`. |
| `DSP.SETFB`   | `0x01` | X | 32 | Bind framebuffer slot `slot` as the scan-out source. |
| `DSP.PRESENT` | `0x10` | X | 32 | Atomically present the back buffer (front/back swap on next vsync). |
| `DSP.VSYNC`   | `0x11` | X | 32 | Block the wavefront until next vsync. |
| `DSP.SCAN.ON` | `0x20` | X | 32 | Enable scan-out. |
| `DSP.SCAN.OFF`| `0x21` | X | 32 | Disable (used during mode change). |
| `DSP.GAMMA`   | `0x30` | X | 32 | Upload gamma LUT entry: index from `s_rs1`, value from `v_rs1..+2`. |
| `DSP.DITHER`  | `0x31` | X | 32 | Configure spatial dither (Bayer / FS error-diffusion) for low-bit panels. |
| `DSP.SCALE`   | `0x40` | X | 32 | Configure the scan-out scaler (nearest / bilinear / Lanczos). |
| `DSP.HDR`     | `0x50` | X | 32 | Set HDR metadata (PQ / HLG / SDR, peak nits, primaries). |
| `DSP.IRQ`     | `0x60` | X | 32 | Enable/disable vblank and underrun IRQs. |

### 6.23 `CUR` — Hardware Cursor (op `0x25`)

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `CUR.POS`     | `0x00` | X | 32 | Move cursor to (`s_rs1.x`, `s_rs1.y`). |
| `CUR.IMG`     | `0x01` | X | 32 | Bind cursor sprite (image slot in `slot`). |
| `CUR.EN`      | `0x02` | X | 32 | Enable cursor. |
| `CUR.DIS`     | `0x03` | X | 32 | Disable. |
| `CUR.HOTSPOT` | `0x04` | X | 32 | Set hotspot offset. |

### 6.24 `PRIM` / `CLIP` / `CULL` / `VIEW` (ops `0x26`–`0x29`)

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `PRIM.TRI`     | `0x26` | `0x00` | X | 32 | Emit a triangle from three vertices already in `o0..o2`. |
| `PRIM.LINE`    | `0x26` | `0x01` | X | 32 | Emit a line. |
| `PRIM.PT`      | `0x26` | `0x02` | X | 32 | Emit a point sprite. |
| `PRIM.STRIP`   | `0x26` | `0x03` | X | 32 | Emit one tri from a triangle strip (uses internal restart bit). |
| `PRIM.FAN`     | `0x26` | `0x04` | X | 32 | Emit one tri from a triangle fan. |
| `CLIP.FRUSTUM` | `0x27` | `0x00` | X | 32 | Test current vertex against the 6 frustum planes; produces a clip-code mask in `vd`. |
| `CLIP.USER`    | `0x27` | `0x01` | X | 32 | Test against the user clip plane in slot `slot`. |
| `CULL.FACE`    | `0x28` | `0x00` | X | 32 | Reject if winding (HW-computed signed area) matches the current cull mode. |
| `CULL.AREA`    | `0x28` | `0x01` | X | 32 | Reject zero-area triangles (subpixel test). |
| `VIEW.XFORM`   | `0x29` | `0x00` | X | 32 | Apply viewport transform to the clip-space position in `v_rs1..v_rs1+3`, write window-space to `vd..vd+3`. |
| `VIEW.SCISSOR` | `0x29` | `0x01` | X | 32 | Apply scissor rejection to current fragment. |

### 6.25 `MSAA` — Multisample (op `0x2A`)

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `MSAA.COV`     | `0x00` | X | 32 | Compute coverage mask for current fragment (writes `vd`). |
| `MSAA.SETMASK` | `0x01` | X | 32 | Override the coverage mask (for alpha-to-coverage). |
| `MSAA.RESOLVE` | `0x10` | X | 32 | Resolve a multisampled render target to a single-sample target (HW-driven box / custom filter). |

### 6.26 `TILE` — Tile-Based Render Buffer (op `0x2B`)

Optional, for tile-binning architectures. Each SM has a tile buffer in
on-chip SRAM.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `TILE.LOAD`  | `0x00` | X | 32 | Load tile contents from main memory into on-chip tile buffer. |
| `TILE.STORE` | `0x01` | X | 32 | Store tile buffer back to main memory. |
| `TILE.CLR`   | `0x02` | X | 32 | Clear the on-chip tile buffer (used at tile begin to avoid load). |
| `TILE.RES`   | `0x03` | X | 32 | Resolve MSAA tile to single-sample tile in place. |

### 6.27 `DMA` (op `0x2C`)

Async copy via the GPU's DMA engine. The shader fires-and-forgets; a
later `FENCE` or `BAR` waits for completion.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `DMA.CPY`   | `0x00` | X | 32 | Copy `s_rs1` bytes from `v_rs1`-pointer to `v_rs2`-pointer. |
| `DMA.FILL`  | `0x01` | X | 32 | Fill `s_rs1` bytes at `v_rs1` with the 32-bit pattern in `v_rs2`. |
| `DMA.WAIT`  | `0x02` | X | 32 | Wait for outstanding DMA on this SM. |

### 6.28 `DOT` / `GEOM` / `MAT` (ops `0x2D`–`0x2F`)

Common graphics maths. They are *not* macro-instructions — each is a
fixed-function pipe that retires in a few cycles.

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `DP4`     | `0x2D` | `0x000` | R | 32 | `vd = v1.x*v2.x + v1.y*v2.y + v1.z*v2.z + v1.w*v2.w` over 4 consecutive VGPRs. |
| `DP3`     | `0x2D` | `0x001` | R | 32 | 3-component dot. |
| `DP2`     | `0x2D` | `0x002` | R | 32 | 2-component dot. |
| `CROSS`   | `0x2E` | `0x000` | R | 32 | `vd..vd+2 = cross(v_rs1..v_rs1+2, v_rs2..v_rs2+2)`. |
| `LEN3`    | `0x2E` | `0x001` | R | 32 | Length of 3-vector. |
| `NRM3`    | `0x2E` | `0x002` | R | 32 | Normalize 3-vector. |
| `REFL`    | `0x2E` | `0x003` | R | 32 | Reflect: `I - 2*dot(N,I)*N`. |
| `RFRCT`   | `0x2E` | `0x004` | R | 32 | Refract (Snell's law). |
| `MIX`     | `0x2E` | `0x005` | R | 32 | `vd = v1*(1-s2) + v2*s2`. |
| `SMSTP`   | `0x2E` | `0x006` | R | 32 | `smoothstep(edge0=v1, edge1=v2, x=vd)`. |
| `MUL4x4`  | `0x2F` | `0x000` | R | 32 | 4×4 × 4-vector multiply. `v_rs1..v_rs1+15` is a row-major 4×4; `v_rs2..v_rs2+3` is the column; result in `vd..vd+3`. |
| `MUL3x3`  | `0x2F` | `0x001` | R | 32 | 3×3 × 3-vector multiply. |
| `TRP4x4`  | `0x2F` | `0x002` | R | 32 | Transpose 4×4. |

### 6.29 `CSR` / `DBG` (ops `0x30`, `0x31`)

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `CSRRD` | `0x30` | `0x000` | I | 32 | Read CSR at index `imm16` into `s_rd`. |
| `CSRWR` | `0x30` | `0x001` | I | 32 | Write `s_rs1` into CSR at `imm16`. |
| `CSRRW` | `0x30` | `0x002` | I | 32 | Atomic read+write. |
| `DBG.PR`| `0x31` | `0x000` | R | 32 | Debug print (drains `v_rs1` to host trace ring). |
| `DBG.BP`| `0x31` | `0x001` | R | 32 | Breakpoint trap. |
| `DBG.PC`| `0x31` | `0x002` | R | 32 | Read perf counter `s_rs1` into `s_rd`. |

---

## 7. CSR Map (selected)

| CSR # | Name | Access | Description |
|---|---|---|---|
| `0x000` | `WAVE_ID` | RO | Wavefront ID. |
| `0x001` | `LANE_ID` | RO | Lane ID (0–31). |
| `0x002` | `SM_ID`   | RO | SM ID. |
| `0x010` | `CYCLE_L` | RO | Low 32 of cycle counter. |
| `0x011` | `CYCLE_H` | RO | High 32 of cycle counter. |
| `0x100` | `FB_BASE` | RW | Current framebuffer base addr. |
| `0x101` | `ZB_BASE` | RW | Current z-buffer base addr. |
| `0x102` | `RT_FMT0` | RW | Render target 0 format descriptor. |
| `0x110` | `BLEND_CFG0` | RW | Blend config for RT0 (eq + factors). |
| `0x120` | `Z_CMP`   | RW | Depth compare function (LESS, LEQ, etc.). |
| `0x121` | `Z_WMASK` | RW | Depth write mask. |
| `0x130` | `STCL_REF` | RW | Stencil ref + mask. |
| `0x140` | `VIEWPORT` | RW | Viewport (x, y, w, h, near, far). |
| `0x141` | `SCISSOR`  | RW | Scissor rect. |
| `0x200` | `DSP_MODE` | RW | Display mode register. |
| `0x201` | `DSP_FB`   | RW | Scan-out framebuffer slot. |
| `0x300` | `FP_MODE`  | RW | FP rounding + denorm-flush flags. |
| `0x301` | `BUS_WIDTH`| RO | Memory bus width in bits (= `256` on Jade v1). |
| `0x302` | `LINE_SIZE`| RO | Cache line size in bytes (= `32` on Jade v1). |
| `0x400` | `PERF_CTL` | RW | Performance counter control. |

---

## 8. Pipeline Stages

Each SM has a 7-stage in-order pipeline:

1. **IF** — Instruction Fetch (32-bit, one per cycle per wavefront).
2. **ID** — Decode + register rename (none — straight RISC).
3. **RR** — Read GPR/SGPR/predicate; resolve scoreboard hazards.
4. **EX1/EX2** — Execute. Single-cycle ALU; multi-cycle SFU, FMA, mat ops.
5. **MEM** — Address-generate for LDST; dispatch to fixed-function units
   (TEX/INTRP/RAST/BLND/FBWR).
6. **WB** — Write back to GPR / predicate.

Fixed-function units (Raster Engine, Texture Units, Interpolator, ROP,
Display Controller) sit *off* the SM pipeline; the SM issues a request
and waits on a scoreboard slot.

---

## 9. Memory Model

### 9.1 Memory bus

Jade uses a **256-bit (32-byte) memory bus** between L2 and main memory,
and between each SM's L1 and L2. This sets the natural transaction size
for every load and store and shapes the rest of the pipeline.

| Transaction | Width | Bytes | Notes |
|---|---|---|---|
| **Bus beat** | 256 b | 32 B | One cycle on the main bus moves one beat. |
| **Cache line** | 256 b | 32 B | One line = one beat — no multi-beat lines, so no critical-word-first logic. |
| **Coalesced word load** | 8 lanes × 32 b | 32 B | A wavefront `LDG.W` (32 lanes × 4 B = 128 B) issues in **4 bus beats** when fully coalesced. |
| **Coalesced vec4 load** | 2 lanes × 128 b | 32 B | A wavefront `LDG.V4` (32 lanes × 16 B = 512 B) issues in **16 bus beats** when fully coalesced. |
| **Texture tile fetch** | 256 b | 32 B | Texture units request one tile/footprint at a time as 32 B beats; bilinear footprint = 1 beat for most narrow formats. |
| **Framebuffer write** | 256 b | 32 B | ROP commits in 32 B color-block-aligned writes. |

The bus width is exposed to the driver and shader compiler via the
`BUS_WIDTH` CSR (`0x301`, RO, = `256`) so software can pick natural
alignments without baking the constant into binaries.

### 9.2 Coalescing rules

The L1 coalescer collapses concurrent lane requests in a wavefront into
the smallest set of 32 B-aligned bus beats. To stay at peak bandwidth:

- Align global buffers to **32 B**.
- Use `LDG.V4` for vertex / pixel data — each beat covers two lanes of
  vec4, so `LDG.V4` from a 16-B-strided array is the same cost as
  `LDG.W` on a 4-B-strided array (16 beats vs 4 beats, but 4× the data).
- Cross-32-B-boundary loads cost +1 beat. The compiler emits `LDG.V2`
  pairs when an array straddles boundaries.

### 9.3 Region table

| Region | Path | Latency | Coherency | Used for |
|---|---|---|---|---|
| **VGPR** | reg file | 1 c | n/a | Per-lane scratch. |
| **SGPR** | reg file | 1 c | n/a | Per-wavefront uniforms. |
| **Shared** (SM-local SRAM) | 32 banks × 32 b | ~5 c | Coherent within work-group. | Cross-lane communication. Bank conflicts cost +1 c per conflict. |
| **L1 D-cache** | 32 B line, 256-bit fill | ~10 c | Read-coherent. | Buffer / constant / global. |
| **L2** | 32 B line, 256-bit fill | ~50 c | Coherent across SMs after `FENCE`. | Cross-SM. |
| **Main mem** | 256-bit bus | ~250 c | Coherent after `FENCE` + `BAR.WG`. | Backing store. |
| **Tile buffer** | SM-local SRAM, 256-bit ports | ~3 c | SM-local, never spills until `TILE.STORE`. | Tile-based fragment ops. |

---

## 10. End-to-end Example: A Textured Triangle

```
# Vertex shader (per-vertex, one lane per vertex)
LDG.V4   v0, a0, #0       # load position attribute
LDG.V4   v4, a1, #0       # load UV attribute
MUL4x4   v8,  s0, v0       # MVP * pos -> clip space   (s0 points to matrix base)
FMOV     o0, v8            # emit clip position
FMOV     o1, v4            # emit UV varying

# Primitive assembly
PRIM.TRI

# Clip / cull / viewport / setup (driver-injected after PRIM)
CLIP.FRUSTUM v16, o0
CULL.FACE
VIEW.XFORM   v20, o0
RAST.SETUP.TRI
RAST.DISPATCH

# Fragment shader (per-pixel, one lane per fragment)
INTRP.PC  v0, slot=1       # interpolate UV (slot 1 = o1)
TEX.2D    v4, v0, slot=0   # sample texture 0 with UVs in v0..v1
FBWR.CD   slot=0, v4       # commit color + depth (HW Z-test + blend ran already)
```

This is the *whole* pipeline. The driver never schedules a perspective
divide, never interpolates an attribute, never blends a fragment, never
packs a color. Hardware does. The driver's job is upstream of the
shader: bind resources, set CSR state, submit work.

---

## 11. Out-of-scope (Future Extensions)

Reserved opcode space `0x32`–`0x3F`:

- **Ray traversal** (BVH walk).
- **Video decode/encode** dispatch (separate codec block).
- **Mesh / task shading** (Vulkan-style alternative geometry pipeline).
- **Tensor / matrix-multiply accelerator** (for ML in display
  pipelines: upscaling, frame generation).

These are *not* part of Jade v1.
