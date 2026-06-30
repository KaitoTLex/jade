# Jade ISA Specification

**Jade** — *Just Another Display Engine*.

A display-first GPU instruction set. Unlike Vortex (a RISC-V general-purpose
GPU) or TinyGPU (a minimal compute-leaning teaching core), Jade pushes the
graphics pipeline — turning triangles into pixels, sampling textures, blending,
and sending the result to a monitor — *into hardware as first-class
instructions*. The driver compiles a single shader stream against a small,
predictable arithmetic vocabulary and lets the fixed-function units do the heavy
lifting. The compute side (a small lockstep machine) exists to feed the display
pipeline, not the other way around.

Everything in this document is the *hardware contract*. Anything the driver must
build on top of these primitives is in `driver.md`.

This spec uses the names defined in `nomenclature.md`:

- **Execution Cluster (EC)** — the programmable block that runs shader code.
- **Cohort** — a group of 32 strands that execute the same instruction in
  lockstep (one program counter for all 32).
- **Strand** — one per-element execution context: one pixel, one vertex, or one
  compute item. Each strand has its own registers.
- **Active Set** — the 32-bit on/off mask saying which strands actually commit
  the current instruction (held in predicate `p0`).
- **Path Stack** — the hardware stack that remembers where strands split apart
  on a branch so they can rejoin later.

---

## 1. Design Philosophy

| Principle | Consequence |
|---|---|
| **Display first, compute incidental** | Dedicated opcodes for rasterization, interpolation, blending, scan-out. |
| **Reduced instruction set, fixed 32-bit instructions** | One instruction = 4 bytes. No prefix bytes, no variable encodings. |
| **Lockstep execution** | A cohort of 32 strands executes one instruction per cycle. Divergent branches are managed by a per-EC Path Stack. |
| **Push API redundancy into the driver** | The three big APIs (OpenGL/Vulkan/OpenCL) overlap heavily on state-machine bookkeeping. That bookkeeping is software. |
| **Pull display-relevant work into hardware** | Texture filtering, perspective-correct interpolation, blend modes, pixel-format packing/unpacking, sRGB conversion, scan-out: all fixed-function. |
| **Single addressable instruction = single retired operation** | No fused micro-ops, no horizontal math inside a strand. Width comes from the cohort, not the word. |

---

## 2. Execution Model

```
+-------------------------------------------------------+
|                    Jade GPU                           |
|                                                       |
|   +-------------------+     +-------------------+      |
|   |   EC 0            |     |   EC 1            |      |
|   |  +-------------+  |     |  +-------------+  |      |
|   |  | Cohort 0    |  |     |  | Cohort 0    |  |      |
|   |  | (32 strands)|  |     |  | (32 strands)|  |      |
|   |  +-------------+  |     |  +-------------+  |      |
|   |  | Cohort 1    |  |     |  | Cohort 1    |  |      |
|   |  +-------------+  |     |  +-------------+  |      |
|   |   ...             |     |   ...             |      |
|   +-------------------+     +-------------------+      |
|                                                       |
|   +--------+ +--------+ +------+ +-----+ +-------+     |
|   | Raster | |Texture | |Interp| |Blend| |Display|    |
|   | Unit   | | Unit   | | Unit | |Unit | |Unit   |    |
|   +--------+ +--------+ +------+ +-----+ +-------+     |
|                                                       |
|   +-------------------- L2 ---------------------+      |
|   +-------------------- MC ---------------------+      |
+-------------------------------------------------------+
```

(`L2` = second-level cache shared by all Execution Clusters; `MC` = memory
controller to main memory.)

- **Execution Cluster (EC):** the programmable block. It fetches, decodes, and
  issues one instruction per cycle to one cohort. Holds the Path Stack, the
  predicate registers, the scalar registers, and the ports to local memory.
- **Cohort:** 32 strands that execute in lockstep — one instruction drives all
  32 at once. Each strand has its own vector register file.
- **Strand:** the per-element execution context; one per pixel, vertex, or
  compute item.
- **Fixed-function blocks:** Raster Unit, Texture Unit, Interpolation Unit,
  Blend Unit / Target Writer, Display Unit. Driven by dedicated opcodes, not by
  reads and writes to memory-mapped state.

Cohorts are launched by one of three *dispatchers*:
- **Vertex Dispatch:** one strand per vertex, runs the vertex shader.
- **Fragment Dispatch:** one strand per fragment (pixel), runs the fragment
  shader. Launched by the Raster Unit after `RAST` instructions.
- **Compute Dispatch:** one strand per work-item, runs a compute kernel.

---

## 3. Register File

Per *strand*:

| Name | Count | Width | Role |
|---|---|---|---|
| `v0`–`v31` | 32 | 32 b | Vector registers. Each strand has its own copy. Used for per-strand state. |
| `a0`–`a15` | 16 | 32 b | Attribute registers. Hold the inputs handed to the strand (vertex inputs under Vertex Dispatch, interpolated fragment inputs under Fragment Dispatch, work-item arguments under Compute Dispatch). Read-only inside the shader. |
| `o0`–`o7` | 8 | 32 b | Output registers. The values handed to the next stage (vertex outputs out of vertex shading, color/depth out of fragment shading). |

Per *cohort* (shared across all 32 strands):

| Name | Count | Width | Role |
|---|---|---|---|
| `s0`–`s15` | 16 | 32 b | Scalar registers. One value shared by the whole cohort. |
| `p0`–`p7` | 8 | 1 b × 32 strands | Predicate registers. `p0` is the **Active Set** (managed by the Path Stack). `p1`–`p7` are general. |
| `pc` | 1 | 32 b | Program counter. |
| `lr` | 1 | 32 b | Link register (set by `CALL`, read by `RET`). |
| `pstk` | 8 | 32 b × 32 strands | Path Stack (hardware-managed for `DIV`/`CONV`). |

Per *Execution Cluster*, read via `CSR`:

| CSR | Role |
|---|---|
| `item.x/y/z` | Dispatch item coordinate for the current strand within the workgroup / draw call. |
| `cohort_id` | Cohort index within the Execution Cluster. |
| `ec_id` | Execution Cluster index within the GPU. |
| `strand_id` | Strand index within the cohort (0–31). |
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
special-function unit, conversion, packing, comparison.

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

Jade does *not* carry a per-instruction predicate field. Per-strand control
flow is handled by the **Path Stack** (`DIV`/`CONV` instructions): when strands
diverge, the Active Set `p0` is updated and the previous Active Set is pushed.
`CONV` pops. Most instructions read `p0` implicitly to gate side effects
(writes, stores, sample requests).

Predicate registers `p1`–`p7` are used as comparison results and as guards
on `PRED.MOV`, `PRED.SEL`, conditional `BR`, etc.

---

## 5. Major Opcode Map

The table below maps each major opcode to its mnemonic prefix and the unit or
class it belongs to. The detailed per-instruction encodings and the
plain-English description of each unit are in §6.

| Major | Mnemonic prefix | Class |
|---|---|---|
| `0x00` | `SYS`   | System: NOP, halt, strand-terminate, fence, barrier |
| `0x01` | `SALU`  | Scalar ALU — integer/logic on per-cohort scalar registers `s*` |
| `0x02` | `VALU.I`| Vector integer ALU — per-strand integer/logic on `v*` |
| `0x03` | `VALU.F`| Vector floating-point ALU — per-strand single precision on `v*` |
| `0x04` | `SFU`   | Special function unit: reciprocal, square root, sine, cosine, exp2, log2 |
| `0x05` | `CMP`   | Comparison → predicate-register write |
| `0x06` | `CVT`   | Numeric conversion (int↔float, half↔float, signed/unsigned) |
| `0x07` | `PACK`  | Pixel-format pack/unpack (RGBA8, RGB10A2, F16x2, sRGB) |
| `0x08` | `LDG`   | Load from a global / storage buffer |
| `0x09` | `STG`   | Store to a global / storage buffer |
| `0x0A` | `LDS`   | Load from EC-Local Memory |
| `0x0B` | `STS`   | Store to EC-Local Memory |
| `0x0C` | `LDC`   | Load from a constant buffer |
| `0x0D` | `LDL`   | Load from the Private Stack |
| `0x0E` | `STL`   | Store to the Private Stack |
| `0x0F` | `ATOM`  | Atomic op (add, min, max, compare-and-swap, exchange) on global/EC-Local |
| `0x10` | `BR`    | Branch (uniform, taken when `s_rs1` matches condition) |
| `0x11` | `CALL`  | Subroutine call, writes `lr` |
| `0x12` | `RET`   | Subroutine return |
| `0x13` | `DIV`   | Divergent branch: push Active Set onto Path Stack, take taken-path strands |
| `0x14` | `CONV`  | Converge: pop Path Stack, run not-taken strands (or finish join) |
| `0x15` | `BAR`   | Barrier: cohort-local or workgroup-wide sync |
| `0x16` | `WAVE`  | Cohort collectives: vote, ballot, shuffle, broadcast, any, all |
| `0x17` | `PRED`  | Predicate ops: set, copy, AND/OR/NOT between predicates |
| `0x18` | `TEX`   | Texture sample (filtered, automatic level-of-detail) |
| `0x19` | `TEXL`  | Texture sample with explicit level-of-detail / bias / derivatives / gather / fetch |
| `0x1A` | `IMG`   | Image load/store (unfiltered, typed) |
| `0x1B` | `RAST`  | Raster Unit: setup, dispatch, edge test |
| `0x1C` | `INTRP` | Interpolation Unit: perspective-correct, linear, flat, centroid |
| `0x1D` | `FRAG`  | Fragment ops: discard, derivatives (rate of change in x/y), helper-strand checks |
| `0x1E` | `ZTST`  | Depth test (early or late), depth write |
| `0x1F` | `STCL`  | Stencil test + op |
| `0x20` | `BLND`  | Blend with current render target |
| `0x21` | `FBWR`  | Framebuffer write (commits color + optional depth) |
| `0x22` | `ROP`   | Logic raster ops (for 2D blits / bitblts) |
| `0x23` | `CLR`   | Fast clear: color, depth, stencil |
| `0x24` | `DSP`   | Display Unit: scan, vsync wait, present, mode set |
| `0x25` | `CUR`   | Hardware cursor: position, image, enable |
| `0x26` | `PRIM`  | Primitive assembly: triangle, line, point, strip, fan |
| `0x27` | `CLIP`  | Clip test against frustum / user clip planes |
| `0x28` | `CULL`  | Face culling (clockwise/counter-clockwise), zero-area, scissor reject |
| `0x29` | `VIEW`  | Viewport / scissor application |
| `0x2A` | `MSAA`  | Multisample coverage / resolve |
| `0x2B` | `TILE`  | Tile-buffer load/store (for tile-based rendering) |
| `0x2C` | `DMA`   | Asynchronous copy between memory regions |
| `0x2D` | `DOT`   | Dot products: DP2, DP3, DP4 |
| `0x2E` | `GEOM`  | Geometric helpers: cross, length, normalize, reflect, refract |
| `0x2F` | `MAT`   | Matrix helpers: 4×4 multiply, 3×3 multiply, transpose |
| `0x30` | `CSR`   | Control/status register read / write |
| `0x31` | `DBG`   | Debug: print, breakpoint, performance counter read |
| `0x32`–`0x3F` | — | **Reserved for extensions** (e.g., ray traversal, video decode) |

---

## 6. Instruction Tables

In all tables: every instruction is **32 bits** wide (fixed length). The
**Fmt** column gives the format (R / I / B / X). `funct11` / `funct7` are
shown in hex.

### 6.1 `SYS` — System (op `0x00`)

- The housekeeping instructions for a cohort. They start nothing and compute
  no math: they halt the cohort, end strands early, and keep memory accesses
  and cohorts in order with respect to each other.
- They act on the whole cohort, on the Active Set, or on the memory system —
  never on a single strand's arithmetic.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `NOP`     | `0x000` | R | 32 | No operation. |
| `HLT`     | `0x001` | R | 32 | Halt the cohort. |
| `KILL`    | `0x002` | R | 32 | Mark every active strand as terminated (for vertex/fragment early-out). |
| `FENCE`   | `0x010` | R | 32 | Memory fence; orders prior loads/stores. |
| `BARRIER` | `0x020` | R | 32 | Workgroup barrier (sync all cohorts in the workgroup). |
| `WAVEBAR` | `0x021` | R | 32 | Cohort-internal reconverge — force all strands to the same program counter. |

### 6.2 `SALU` — Scalar ALU (op `0x01`)

- The scalar arithmetic-and-logic unit. It does integer math and bit
  operations on the scalar registers, which hold one value shared by the whole
  cohort — every strand sees the same number. Use it for loop counters, memory
  addresses, and uniform control values.
- Operates on `s*` registers. All three-register form (two source registers,
  one destination).

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

- The per-strand integer arithmetic-and-logic unit. All 32 strands run the
  same operation on their own vector-register values at the same time. Only
  strands switched on by the Active Set keep their result.
- Reads `v*`, writes `v*`. A scalar register can be fed in as a shared
  (broadcast) operand by using the `.S` variants — encoded by setting the high
  bit of the `funct11` field.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `VADD`   | `0x000` | R | 32 | `vd[i] = v1[i] + v2[i]` for active strand `i`. |
| `VADD.S` | `0x001` | R | 32 | `vd[i] = v1[i] + s2`. |
| `VSUB`   | `0x002` | R | 32 | Per-strand subtract. |
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
| `VBCST`  | `0x041` | R | 32 | Broadcast `s1` into every active strand of `vd`. |
| `VLI`    | `0x042` | I | 32 | Load sign-extended `imm16` into all active strands of `vd`. |

### 6.4 `VALU.F` — Vector Floating-Point ALU (op `0x03`)

- The per-strand floating-point unit. Same idea as the integer unit, but it
  works on 32-bit floating-point numbers following the IEEE-754 standard. By
  default it rounds to the nearest even result and flushes very small
  ("denormal") numbers to zero; the driver can change this through a control
  register.
- "Not-a-Number" (NaN) results follow a "first NaN wins" rule.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `FADD`  | `0x000` | R | 32 | Per-strand floating-point add. |
| `FSUB`  | `0x001` | R | 32 | Floating-point subtract. |
| `FMUL`  | `0x002` | R | 32 | Floating-point multiply. |
| `FDIV`  | `0x003` | R | 32 | Floating-point divide (done in steps via the special function unit). |
| `FMAD`  | `0x004` | R | 32 | Non-fused multiply-add (`(v1*v2)+vd`, two roundings). |
| `FFMA`  | `0x005` | R | 32 | Fused multiply-add (single rounding). |
| `FMIN`  | `0x006` | R | 32 | min, returning the non-NaN operand if exactly one is NaN. |
| `FMAX`  | `0x007` | R | 32 | max, same NaN rule. |
| `FABS`  | `0x008` | R | 32 | ```vd = |v1|``` |
| `FNEG`  | `0x009` | R | 32 | Flip sign bit. |
| `FSAT`  | `0x00A` | R | 32 | Saturate (clamp) to `[0.0, 1.0]` — common enough in shaders to deserve its own slot. |
| `FFLR`  | `0x010` | R | 32 | floor (round down). |
| `FCEL`  | `0x011` | R | 32 | ceil (round up). |
| `FRND`  | `0x012` | R | 32 | round-to-nearest-even. |
| `FTRC`  | `0x013` | R | 32 | truncate (drop fraction). |
| `FFRC`  | `0x014` | R | 32 | fract(x) = x - floor(x). |
| `FMOV`  | `0x040` | R | 32 | Copy. |
| `FLI`   | `0x041` | I | 32 | Load `imm16` as a 16-bit float, expanded to 32-bit float. |

### 6.5 `SFU` — Special Function Unit (op `0x04`)

- The special-function unit handles the expensive math the plain ALU can't do
  in a single step: reciprocal (1/x), square roots, powers, logarithms, and
  trigonometry. It accepts one value per cycle but takes several cycles to
  produce the answer.
- It works on 32-bit floating-point values. Internally it reduces the input to
  a small range and evaluates a polynomial approximation, so results are
  accurate to roughly 22 bits rather than exact.

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
| `POW`    | `0x00A` | R | 32 | pow(v1, v2) — internally `EXP2(v2 * LOG2(v1))`. |

### 6.6 `CMP` — Comparison (op `0x05`)

- The compare unit tests two values and writes a single yes/no bit per strand
  into a predicate register (a 32-bit mask, one bit per strand) instead of a
  normal register. Those bits then steer branching and per-strand selection.
- The destination predicate `pd` is encoded in bits `[10:8]` of `funct11`.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `CMPEQ.I` | `0x000` | R | 32 | `pd[i] = (v1[i] == v2[i])`. |
| `CMPNE.I` | `0x001` | R | 32 | not-equal. |
| `CMPLT.I` | `0x002` | R | 32 | signed less-than. |
| `CMPLE.I` | `0x003` | R | 32 | signed less-or-equal. |
| `CMPLTU`  | `0x004` | R | 32 | unsigned less-than. |
| `CMPEQ.F` | `0x010` | R | 32 | floating-point ordered equal. |
| `CMPNE.F` | `0x011` | R | 32 | floating-point ordered not-equal. |
| `CMPLT.F` | `0x012` | R | 32 | floating-point ordered less-than. |
| `CMPLE.F` | `0x013` | R | 32 | floating-point ordered less-or-equal. |
| `CMPUO`   | `0x014` | R | 32 | unordered (either operand is NaN). |
| `CMPS.EQ` | `0x020` | R | 32 | Scalar compare on `s*`, writes one shared predicate bit for the cohort. |

### 6.7 `CVT` — Conversion (op `0x06`)

- The format-conversion unit. It turns one number format into another: integer
  to floating-point and back, full 32-bit float to half-size 16-bit float,
  sign- or zero-extending narrow values, and swapping byte order. Each strand
  converts its own value.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `I2F`    | `0x000` | R | 32 | signed 32-bit int → 32-bit float. |
| `U2F`    | `0x001` | R | 32 | unsigned 32-bit int → 32-bit float. |
| `F2I`    | `0x002` | R | 32 | 32-bit float → signed 32-bit int (round toward zero). |
| `F2U`    | `0x003` | R | 32 | 32-bit float → unsigned 32-bit int. |
| `F2H`    | `0x010` | R | 32 | 32-bit float → 16-bit float (packed in low 16 bits). |
| `H2F`    | `0x011` | R | 32 | 16-bit float → 32-bit float. |
| `S2U16`  | `0x020` | R | 32 | sign-extend low 16 bits. |
| `Z2U16`  | `0x021` | R | 32 | zero-extend low 16 bits. |
| `BSWAP`  | `0x030` | R | 32 | Byte swap (endian flip). |

### 6.8 `PACK` — Pixel Format Pack/Unpack (op `0x07`)

- The pixel-packing unit. It converts between the floating-point colors a
  shader works with and the compact byte layouts a framebuffer or texture
  actually stores — for example four 8-bit color channels squeezed into one
  32-bit word — and converts between sRGB (the standard monitor color space)
  and linear color.
- This is *the* reason display drivers don't have to hand-code format
  conversion. Each one is fully fixed-function inside the Execution Cluster.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `PK.RGBA8`   | `0x000` | R | 32 | Pack four `f32` ∈ [0,1] from `v1..v1+3` into one `RGBA8` word in `vd`. |
| `UPK.RGBA8`  | `0x001` | R | 32 | Unpack `RGBA8` from `v1` into `vd..vd+3` as `f32`. |
| `PK.RGB10A2` | `0x002` | R | 32 | Pack to 10/10/10/2 unsigned-normalized. |
| `UPK.RGB10A2`| `0x003` | R | 32 | Unpack 10/10/10/2 unsigned-normalized. |
| `PK.RG11B10` | `0x004` | R | 32 | Pack to 11/11/10 unsigned float. |
| `UPK.RG11B10`| `0x005` | R | 32 | Unpack 11/11/10 unsigned float. |
| `PK.F16x2`   | `0x006` | R | 32 | Pack `(v1, v2)` `f32` pair into two 16-bit-float halves. |
| `UPK.F16x2`  | `0x007` | R | 32 | Unpack two 16-bit-float halves into `vd, vd+1`. |
| `PK.SNORM8`  | `0x008` | R | 32 | Pack four `f32` ∈ [-1,1] → 4× signed 8-bit. |
| `UPK.SNORM8` | `0x009` | R | 32 | Unpack 4× signed 8-bit → 4×`f32`. |
| `PK.D24S8`   | `0x010` | R | 32 | Pack depth `f32` + stencil `u8` to 24-bit-depth/8-bit-stencil. |
| `UPK.D24S8`  | `0x011` | R | 32 | Unpack 24-bit-depth/8-bit-stencil to f32 depth + u8 stencil. |
| `SRGB2LIN`   | `0x020` | R | 32 | Per-channel sRGB → linear (lookup table in hardware). |
| `LIN2SRGB`   | `0x021` | R | 32 | Linear → sRGB (hardware). |

### 6.9 Memory — `LDG`/`STG`/`LDS`/`STS`/`LDC`/`LDL`/`STL` (ops `0x08`–`0x0E`)

- The load/store instructions. They move data between registers and the
  various memory regions: global memory (large, shared by everything),
  EC-Local Memory (small and fast, shared inside one Execution Cluster),
  constant buffers (read-only driver data), and the Private Stack (per-strand
  scratch).
- All loads/stores are I-type: `addr = base + sext(imm16)`. Width is selected
  by a field in the opcode subspace (only enumerated for `LDG`/`STG` below; the
  others follow the same pattern).

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
| `LDS.W`   | `0x0A` | `0x04` | I | 32 | Load from EC-Local Memory. |
| `STS.W`   | `0x0B` | `0x04` | I | 32 | Store to EC-Local Memory. |
| `LDC.W`   | `0x0C` | `0x04` | I | 32 | Load from a constant-buffer slot (`rs1` indexes `C0..C7`, `imm16` is the offset). |
| `LDL.W`   | `0x0D` | `0x04` | I | 32 | Load from the Private Stack (`sp + imm16`). |
| `STL.W`   | `0x0E` | `0x04` | I | 32 | Store to the Private Stack. |

### 6.10 `ATOM` — Atomic Memory Ops (op `0x0F`)

- An "atomic" operation reads a memory location, changes it, and writes it
  back as one indivisible step, so two strands hitting the same address can't
  corrupt each other. Each returns the value that was there before the change.
- I-type. Works on global or EC-Local Memory; the address space is picked by
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

- These change which instruction runs next. `BR`/`CALL`/`RET` move the whole
  cohort together. `DIV`/`CONV` handle the case where strands need to take
  different paths: `DIV` sends the strands whose condition is true down one
  path and remembers the rest on the Path Stack; `CONV` brings them back.

| Mnemonic | Op | funct | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `BR`       | `0x10` | `0x00` | B | 32 | Unconditional uniform branch. |
| `BR.EQZ`   | `0x10` | `0x01` | B | 32 | Branch if `s_rs1 == 0`. |
| `BR.NEZ`   | `0x10` | `0x02` | B | 32 | Branch if `s_rs1 != 0`. |
| `BR.P`     | `0x10` | `0x03` | B | 32 | Branch if predicate `p_rs1` true on **any** active strand. |
| `BR.PA`    | `0x10` | `0x04` | B | 32 | Branch if predicate `p_rs1` true on **all** active strands. |
| `CALL`     | `0x11` | `0x00` | B | 32 | Save return PC into `lr`, jump. |
| `RET`      | `0x12` | `0x00` | R | 32 | Jump to `lr`. |
| `DIV`      | `0x13` | `0x00` | B | 32 | Divergent branch on predicate `rs1`. Strands where it is true take the branch; the rest are pushed onto the Path Stack with a join target = `offset21`. |
| `CONV`     | `0x14` | `0x00` | R | 32 | Convergence point. Pops the top of the Path Stack. |

### 6.12 `BAR` / `WAVE` — Sync & Collectives (ops `0x15`, `0x16`)

- `BAR` instructions are barriers: they make strands or cohorts wait at a point
  until others reach it. `WAVE` instructions are cohort collectives: they let
  the 32 strands of one cohort share or combine values without going through
  memory — voting, counting which strands are active, broadcasting one value to
  all, and shuffling values between strands.

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `BAR.WG`    | `0x15` | `0x000` | R | 32 | Workgroup barrier (all cohorts in the workgroup). |
| `BAR.WV`    | `0x15` | `0x001` | R | 32 | Cohort-local sync (forces reconverge). |
| `WAVE.VOTE.ANY`| `0x16` | `0x000` | R | 32 | `sd = (any active strand's p_rs1 == 1)`. |
| `WAVE.VOTE.ALL`| `0x16` | `0x001` | R | 32 | `sd = (all active strands' p_rs1 == 1)`. |
| `WAVE.BALLOT`  | `0x16` | `0x002` | R | 32 | `sd = bitmask of strands where p_rs1 == 1`. |
| `WAVE.SHFL`    | `0x16` | `0x010` | R | 32 | Shuffle: `vd[i] = v1[v2[i] mod 32]`. |
| `WAVE.SHFL.UP` | `0x16` | `0x011` | R | 32 | Shuffle up by `s2`. |
| `WAVE.SHFL.DN` | `0x16` | `0x012` | R | 32 | Shuffle down by `s2`. |
| `WAVE.SHFL.XOR`| `0x16` | `0x013` | R | 32 | Shuffle XOR by `s2` (butterfly). |
| `WAVE.BCAST`   | `0x16` | `0x020` | R | 32 | Broadcast `v1[s2]` to all active strands of `vd`. |

### 6.13 `PRED` — Predicate ops (op `0x17`)

- Predicate registers hold one yes/no bit per strand. These instructions
  combine those masks with logic (AND/OR/NOT), copy them, and use one to pick
  between two values per strand. `p0` is the Active Set.

| Mnemonic | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|
| `PRED.AND` | `0x000` | R | 32 | `pd = p1 AND p2`. |
| `PRED.OR`  | `0x001` | R | 32 | `pd = p1 OR p2`. |
| `PRED.XOR` | `0x002` | R | 32 | `pd = p1 XOR p2`. |
| `PRED.NOT` | `0x003` | R | 32 | `pd = NOT p1`. |
| `PRED.MOV` | `0x004` | R | 32 | `pd = p1`. |
| `PRED.SET` | `0x005` | R | 32 | Set all strands of `pd` to bit 0 of `s1`. |
| `PRED.SEL` | `0x010` | R | 32 | `vd[i] = p_rs2[i] ? v_rs1[i] : vd[i]` (per-strand select via predicate). |

### 6.14 `TEX` / `TEXL` — Texture Sampling (ops `0x18`, `0x19`)

- The texture unit reads images ("textures") and filters them into smooth
  colors. It picks the right mipmap (a pre-shrunk copy of the image, chosen by
  "level of detail" so distant surfaces use smaller copies), blends neighboring
  texels, decodes compressed formats, and converts sRGB to linear on the way
  in — all in hardware.
- X-type. `slot` is the binding slot for the texture; the sampler is encoded in
  the low 3 bits of `funct7`. Coordinates come from `v_rs1..v_rs1+N-1` where N
  depends on dimensionality. The result lands in `vd..vd+3` as four `f32`
  channels (red, green, blue, alpha).

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `TEX.1D`     | `0x18` | `0x00` | X | 32 | Sample 1D texture, level-of-detail from screen-space rate of change (helper strands). |
| `TEX.2D`     | `0x18` | `0x01` | X | 32 | Sample 2D texture, level-of-detail from the rate of change of the texture coordinates. |
| `TEX.3D`     | `0x18` | `0x02` | X | 32 | Sample 3D texture. |
| `TEX.CUBE`   | `0x18` | `0x03` | X | 32 | Sample cube map. |
| `TEX.ARR2D`  | `0x18` | `0x04` | X | 32 | Sample 2D array (uses 3-component coord, w = layer). |
| `TEX.SHDW`   | `0x18` | `0x05` | X | 32 | Sample depth texture with hardware compare (percentage-closer filtering for soft shadows). |
| `TEXL.LOD`   | `0x19` | `0x00` | X | 32 | Sample with explicit level-of-detail (`v_rs2` = level as f32). |
| `TEXL.BIAS`  | `0x19` | `0x01` | X | 32 | Sample with a bias added to the automatic level-of-detail. |
| `TEXL.GRAD`  | `0x19` | `0x02` | X | 32 | Sample with explicit rate-of-change gradients. |
| `TEXL.FETCH` | `0x19` | `0x03` | X | 32 | Unfiltered fetch at an exact integer texel coordinate. |
| `TEXL.GATHER`| `0x19` | `0x04` | X | 32 | Gather the 4 texels around the sample point — used for shadows / area sampling. Returns 4 reds (or any chosen channel). |
| `TEXL.QUERY` | `0x19` | `0x05` | X | 32 | Query texture size / mip count into `vd`. |

### 6.15 `IMG` — Image Load/Store (op `0x1A`)

- The image unit reads and writes images directly by integer pixel coordinate,
  with no filtering. Unlike a texture, an image can be written from any shader
  stage. Format conversion to and from `f32` is done in hardware.
- Address is `(x, y, z, layer)` in `v_rs1..v_rs1+3` depending on
  dimensionality.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `IMG.LD.2D`  | `0x00` | X | 32 | Load typed pixel from 2D image. Format conversion to f32 is hardware. |
| `IMG.LD.3D`  | `0x01` | X | 32 | 3D image load. |
| `IMG.ST.2D`  | `0x10` | X | 32 | Store typed pixel to 2D image. |
| `IMG.ST.3D`  | `0x11` | X | 32 | 3D image store. |
| `IMG.ATOM`   | `0x20` | X | 32 | Atomic on image (add, min, max, exchange). |

### 6.16 `RAST` — Rasterization (op `0x1B`)

- The Raster Unit turns a triangle into the pixels (fragments) it covers. The
  vertex shader writes the corner positions to `o0..o3`; a `PRIM` instruction
  groups them into a triangle; then `RAST.SETUP` works out the edges and slopes
  and `RAST.DISPATCH` launches a fragment shader for the covered pixels.
- "Barycentric" coordinates describe where a pixel sits inside the triangle
  (how much of each corner it gets); the hardware computes these so attributes
  can be interpolated later.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `RAST.SETUP.TRI`  | `0x00` | X | 32 | Pass the three clip-space positions in `o0..o2` to the Raster Unit; hardware computes the screen-space transform, edge equations, 1/w, depth slopes, and barycentric basis. |
| `RAST.SETUP.LINE` | `0x01` | X | 32 | Line setup with width. |
| `RAST.SETUP.PT`   | `0x02` | X | 32 | Point setup with size. |
| `RAST.DISPATCH`   | `0x10` | X | 32 | Launch fragment cohorts for the previously set-up primitive. Each fragment strand is initialized with barycentrics, screen coords, 1/w in `a0..a3`. |
| `RAST.SCISSOR`    | `0x20` | X | 32 | Set scissor rect (from `v_rs1`, `v_rs2`). |
| `RAST.EDGE`       | `0x30` | X | 32 | Per-fragment edge equation evaluation (used by tile-binning code). |

### 6.17 `INTRP` — Interpolation (op `0x1C`)

- The Interpolation Unit fills in a per-pixel value by blending the three
  triangle-corner values according to where the pixel sits. "Perspective
  correct" means it accounts for 3D foreshortening, so textures on a tilted
  surface don't warp.
- Issued by fragment shaders. The hardware reads the per-vertex attribute, the
  barycentrics in `a0..a2`, and the perspective divisor in `a3`, then returns
  the interpolated value.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `INTRP.PC`  | `0x00` | X | 32 | Perspective-correct interpolation. `slot` selects the attribute index. |
| `INTRP.LIN` | `0x01` | X | 32 | Linear (screen-space) interpolation — for values that should not be perspective-corrected. |
| `INTRP.FLAT`| `0x02` | X | 32 | Flat: take the provoking vertex's value, no interpolation. |
| `INTRP.CTRD`| `0x03` | X | 32 | Centroid interpolation (stays inside the covered area for multisampling). |
| `INTRP.SMPL`| `0x04` | X | 32 | Per-sample interpolation. |

### 6.18 `FRAG` — Fragment-Stage Ops (op `0x1D`)

- Helpers that only make sense while shading pixels. `DISCARD` throws a pixel
  away. The derivative ops (`DDX`/`DDY`) measure how fast a value changes from
  one pixel to the next in the x and y directions — textures need this to stay
  sharp. To compute derivatives the hardware shades pixels in 2×2 groups
  ("quads"); pixels kept alive only to supply that data are **Helper Strands**
  and commit no color or depth.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `FRAG.DISCARD` | `0x00` | R | 32 | Mark active strands as discarded — they stop contributing to color/depth writes but stay as helpers for derivatives until end of quad. |
| `FRAG.DDX`     | `0x10` | R | 32 | Screen-space rate of change in x of `v_rs1`, computed across the 2×2 helper quad. |
| `FRAG.DDY`     | `0x11` | R | 32 | Screen-space rate of change in y. |
| `FRAG.DDX.F`   | `0x12` | R | 32 | Fine x (per-pixel, requires per-sample shading). |
| `FRAG.DDY.F`   | `0x13` | R | 32 | Fine y. |
| `FRAG.FWIDTH`  | `0x14` | R | 32 | `abs(ddx(x)) + abs(ddy(x))`. |
| `FRAG.HELPER`  | `0x20` | R | 32 | `pd = (this strand is a Helper Strand)` — used to skip side effects. |

### 6.19 `ZTST` / `STCL` — Depth & Stencil (ops `0x1E`, `0x1F`)

- The depth test hides pixels that sit behind a closer surface, using a
  per-pixel depth ("z") buffer. The stencil test is a per-pixel scratch mask
  used for effects like outlines, masking, and mirrors. Both are fully
  fixed-function: the driver sets up the comparison, write mask, and stencil
  operations through control registers, and the shader just commits.

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `ZTST.EARLY` | `0x1E` | `0x00` | X | 32 | Early depth test against the current z-buffer slot; strands that fail are switched off in the Active Set. Runs before the fragment shader if the shader does not write depth or discard. |
| `ZTST.LATE`  | `0x1E` | `0x01` | X | 32 | Late depth test; runs after the shader. |
| `ZTST.WR`    | `0x1E` | `0x02` | X | 32 | Write the depth value in `v_rs1` to the z-buffer at the current fragment. |
| `STCL.TEST`  | `0x1F` | `0x00` | X | 32 | Stencil test using the current control-register ref/mask/op. |
| `STCL.OP`    | `0x1F` | `0x01` | X | 32 | Apply stencil operation (KEEP/ZERO/REPLACE/INCR/DECR/INVERT) per active strand. |
| `STCL.WR`    | `0x1F` | `0x02` | X | 32 | Commit stencil value. |

### 6.20 `BLND` — Blending (op `0x20`)

- The Blend Unit mixes the new color a strand produced (the "source") with the
  color already sitting in the render target (the "destination") — this is how
  transparency and similar effects work. It supports the standard OpenGL/Vulkan
  blend modes.
- `slot` picks the render target. `funct7` encodes the blend equation
  (add / subtract / reverse-subtract / min / max) and the factor pair
  (source / destination / one / zero / source-alpha / one-minus-source-alpha /
  constant / etc.) via a lookup table configured when the pipeline is bound. The
  result is the blended color, ready for `FBWR`.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `BLND.CFG` | `0x00` | X | 32 | (driver-issued, not in shader) — sets the blend table entry for `slot`. |
| `BLND.SRC` | `0x10` | X | 32 | Blend source RGBA from `v_rs1..v_rs1+3` with current render target, result to `vd..vd+3`. |
| `BLND.RB`  | `0x11` | X | 32 | Blend using two source colors — `v_rs1` is color0 and `v_rs2` is color1. |
| `BLND.LOG` | `0x20` | X | 32 | Logical-op blend (XOR/AND/etc.) — driven by `ROP`. |

### 6.21 `FBWR` / `ROP` / `CLR` (ops `0x21`–`0x23`)

- `FBWR` is the Target Writer: it commits the final pixel (color, and
  optionally depth) to a render target. `ROP` does simple 2D copy and logic
  operations between images (block transfers, or "blits"). `CLR` quickly fills
  a whole buffer with one value, the way a screen is cleared at the start of a
  frame.

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
| `CLR.CDS`   | `0x23` | `0x03` | X | 32 | Clear color + depth + stencil in one cycle (hardware compresses). |

### 6.22 `DSP` — Display Controller (op `0x24`)

- The Display Unit is what makes Jade a display engine rather than a plain
  compute chip. It drives the actual monitor output: it sets the screen
  resolution and refresh rate, swaps the finished image to the screen, waits
  for the monitor's refresh boundary ("vertical sync", or vsync), and feeds the
  HDMI / DisplayPort transmitter.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `DSP.MODE`    | `0x00` | X | 32 | Set display mode: width × height × refresh × format. Params in `s_rs1`. |
| `DSP.SETFB`   | `0x01` | X | 32 | Bind framebuffer slot `slot` as the scan-out source. |
| `DSP.PRESENT` | `0x10` | X | 32 | Atomically present the back buffer (front/back swap on next vsync). |
| `DSP.VSYNC`   | `0x11` | X | 32 | Block the cohort until the next vsync. |
| `DSP.SCAN.ON` | `0x20` | X | 32 | Enable scan-out. |
| `DSP.SCAN.OFF`| `0x21` | X | 32 | Disable (used during a mode change). |
| `DSP.GAMMA`   | `0x30` | X | 32 | Upload gamma lookup-table entry: index from `s_rs1`, value from `v_rs1..+2`. |
| `DSP.DITHER`  | `0x31` | X | 32 | Configure dithering (Bayer / error-diffusion) for panels with few bits per color. |
| `DSP.SCALE`   | `0x40` | X | 32 | Configure the scan-out scaler (nearest / bilinear / Lanczos). |
| `DSP.HDR`     | `0x50` | X | 32 | Set high-dynamic-range metadata (PQ / HLG / standard, peak brightness, primaries). |
| `DSP.IRQ`     | `0x60` | X | 32 | Enable/disable vertical-blank and underrun interrupts. |

### 6.23 `CUR` — Hardware Cursor (op `0x25`)

- A hardware cursor draws the mouse pointer as a small sprite composited over
  the screen during scan-out. Because the hardware does it, the pointer can
  move smoothly without the GPU redrawing the frame underneath it.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `CUR.POS`     | `0x00` | X | 32 | Move cursor to (`s_rs1.x`, `s_rs1.y`). |
| `CUR.IMG`     | `0x01` | X | 32 | Bind cursor sprite (image slot in `slot`). |
| `CUR.EN`      | `0x02` | X | 32 | Enable cursor. |
| `CUR.DIS`     | `0x03` | X | 32 | Disable. |
| `CUR.HOTSPOT` | `0x04` | X | 32 | Set hotspot offset (the pixel inside the sprite that counts as the click point). |

### 6.24 `PRIM` / `CLIP` / `CULL` / `VIEW` (ops `0x26`–`0x29`)

- The geometry front-end, run after vertex shading. `PRIM` groups vertices into
  points, lines, and triangles. `CLIP` trims geometry to the viewable region.
  `CULL` drops triangles that face away from the camera or have zero area.
  `VIEW` maps the remaining geometry into actual screen pixel coordinates.

| Mnemonic | Op | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `PRIM.TRI`     | `0x26` | `0x00` | X | 32 | Emit a triangle from three vertices already in `o0..o2`. |
| `PRIM.LINE`    | `0x26` | `0x01` | X | 32 | Emit a line. |
| `PRIM.PT`      | `0x26` | `0x02` | X | 32 | Emit a point sprite. |
| `PRIM.STRIP`   | `0x26` | `0x03` | X | 32 | Emit one triangle from a triangle strip (uses internal restart bit). |
| `PRIM.FAN`     | `0x26` | `0x04` | X | 32 | Emit one triangle from a triangle fan. |
| `CLIP.FRUSTUM` | `0x27` | `0x00` | X | 32 | Test the current vertex against the 6 frustum planes; produces a clip-code mask in `vd`. |
| `CLIP.USER`    | `0x27` | `0x01` | X | 32 | Test against the user clip plane in slot `slot`. |
| `CULL.FACE`    | `0x28` | `0x00` | X | 32 | Reject if winding (hardware-computed signed area) matches the current cull mode. |
| `CULL.AREA`    | `0x28` | `0x01` | X | 32 | Reject zero-area triangles (subpixel test). |
| `VIEW.XFORM`   | `0x29` | `0x00` | X | 32 | Apply the viewport transform to the clip-space position in `v_rs1..v_rs1+3`, write window-space to `vd..vd+3`. |
| `VIEW.SCISSOR` | `0x29` | `0x01` | X | 32 | Apply scissor rejection to the current fragment. |

### 6.25 `MSAA` — Multisample (op `0x2A`)

- Multisample anti-aliasing smooths jagged triangle edges by testing coverage
  at several sample points inside each pixel instead of just one, then
  combining them. These instructions compute the coverage mask, let the shader
  override it, and "resolve" the multiple samples down to one final color.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `MSAA.COV`     | `0x00` | X | 32 | Compute the coverage mask for the current fragment (writes `vd`). |
| `MSAA.SETMASK` | `0x01` | X | 32 | Override the coverage mask (for alpha-to-coverage). |
| `MSAA.RESOLVE` | `0x10` | X | 32 | Resolve a multisampled render target to a single-sample target (hardware box / custom filter). |

### 6.26 `TILE` — Tile-Based Render Buffer (op `0x2B`)

- Optional, for tile-based rendering. Instead of writing every pixel straight
  to main memory, the screen is split into small tiles and each tile is kept in
  fast on-chip memory while it is being drawn, then written out once. This cuts
  main-memory traffic. Each Execution Cluster has its own tile buffer.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `TILE.LOAD`  | `0x00` | X | 32 | Load tile contents from main memory into the on-chip tile buffer. |
| `TILE.STORE` | `0x01` | X | 32 | Store the tile buffer back to main memory. |
| `TILE.CLR`   | `0x02` | X | 32 | Clear the on-chip tile buffer (used at tile begin to avoid a load). |
| `TILE.RES`   | `0x03` | X | 32 | Resolve a multisampled tile to a single-sample tile in place. |

### 6.27 `DMA` (op `0x2C`)

- The Copy Unit moves blocks of memory in the background while the shader keeps
  running. The shader fires the copy and forgets it; a later `FENCE` or `BAR`
  waits for it to finish.

| Mnemonic | funct7 | Fmt | Bits | Description |
|---|---|---|---|---|
| `DMA.CPY`   | `0x00` | X | 32 | Copy `s_rs1` bytes from the `v_rs1` pointer to the `v_rs2` pointer. |
| `DMA.FILL`  | `0x01` | X | 32 | Fill `s_rs1` bytes at `v_rs1` with the 32-bit pattern in `v_rs2`. |
| `DMA.WAIT`  | `0x02` | X | 32 | Wait for outstanding copies on this Execution Cluster. |

### 6.28 `DOT` / `GEOM` / `MAT` (ops `0x2D`–`0x2F`)

- Built-in vector and matrix math that shaders use constantly: dot products,
  cross products, length and normalize, reflect/refract, and matrix multiply.
  Each is a small fixed-function pipe that finishes in a few cycles — not a
  macro that expands into many instructions.

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `DP4`     | `0x2D` | `0x000` | R | 32 | `vd = v1.x*v2.x + v1.y*v2.y + v1.z*v2.z + v1.w*v2.w` over 4 consecutive vector registers. |
| `DP3`     | `0x2D` | `0x001` | R | 32 | 3-component dot. |
| `DP2`     | `0x2D` | `0x002` | R | 32 | 2-component dot. |
| `CROSS`   | `0x2E` | `0x000` | R | 32 | `vd..vd+2 = cross(v_rs1..v_rs1+2, v_rs2..v_rs2+2)`. |
| `LEN3`    | `0x2E` | `0x001` | R | 32 | Length of a 3-vector. |
| `NRM3`    | `0x2E` | `0x002` | R | 32 | Normalize a 3-vector. |
| `REFL`    | `0x2E` | `0x003` | R | 32 | Reflect: `I - 2*dot(N,I)*N`. |
| `RFRCT`   | `0x2E` | `0x004` | R | 32 | Refract (Snell's law). |
| `MIX`     | `0x2E` | `0x005` | R | 32 | `vd = v1*(1-s2) + v2*s2`. |
| `SMSTP`   | `0x2E` | `0x006` | R | 32 | `smoothstep(edge0=v1, edge1=v2, x=vd)`. |
| `MUL4x4`  | `0x2F` | `0x000` | R | 32 | 4×4 × 4-vector multiply. `v_rs1..v_rs1+15` is a row-major 4×4; `v_rs2..v_rs2+3` is the column; result in `vd..vd+3`. |
| `MUL3x3`  | `0x2F` | `0x001` | R | 32 | 3×3 × 3-vector multiply. |
| `TRP4x4`  | `0x2F` | `0x002` | R | 32 | Transpose a 4×4. |

### 6.29 `CSR` / `DBG` (ops `0x30`, `0x31`)

- Control/status registers (CSRs) are the hardware's configuration knobs and
  read-only counters — identity (which strand / cohort / Execution Cluster am
  I), the cycle counter, render state, and display state. `CSR` reads and
  writes them. `DBG` is for debugging: printing values back to the host,
  hitting a breakpoint, and reading performance counters.

| Mnemonic | Op | funct11 | Fmt | Bits | Description |
|---|---|---|---|---|---|
| `CSRRD` | `0x30` | `0x000` | I | 32 | Read CSR at index `imm16` into `s_rd`. |
| `CSRWR` | `0x30` | `0x001` | I | 32 | Write `s_rs1` into CSR at `imm16`. |
| `CSRRW` | `0x30` | `0x002` | I | 32 | Atomic read+write. |
| `DBG.PR`| `0x31` | `0x000` | R | 32 | Debug print (drains `v_rs1` to the host trace ring). |
| `DBG.BP`| `0x31` | `0x001` | R | 32 | Breakpoint trap. |
| `DBG.PC`| `0x31` | `0x002` | R | 32 | Read perf counter `s_rs1` into `s_rd`. |

---

## 7. CSR Map (selected)

| CSR # | Name | Access | Description |
|---|---|---|---|
| `0x000` | `COHORT_ID` | RO | Cohort ID. |
| `0x001` | `STRAND_ID` | RO | Strand ID (0–31). |
| `0x002` | `EC_ID`   | RO | Execution Cluster ID. |
| `0x010` | `CYCLE_L` | RO | Low 32 of cycle counter. |
| `0x011` | `CYCLE_H` | RO | High 32 of cycle counter. |
| `0x100` | `FB_BASE` | RW | Current framebuffer base addr. |
| `0x101` | `ZB_BASE` | RW | Current z-buffer base addr. |
| `0x102` | `RT_FMT0` | RW | Render target 0 format descriptor. |
| `0x110` | `BLEND_CFG0` | RW | Blend config for render target 0 (equation + factors). |
| `0x120` | `Z_CMP`   | RW | Depth compare function (LESS, LEQ, etc.). |
| `0x121` | `Z_WMASK` | RW | Depth write mask. |
| `0x130` | `STCL_REF` | RW | Stencil ref + mask. |
| `0x140` | `VIEWPORT` | RW | Viewport (x, y, w, h, near, far). |
| `0x141` | `SCISSOR`  | RW | Scissor rect. |
| `0x200` | `DSP_MODE` | RW | Display mode register. |
| `0x201` | `DSP_FB`   | RW | Scan-out framebuffer slot. |
| `0x300` | `FP_MODE`  | RW | Floating-point rounding + denormal-flush flags. |
| `0x301` | `BUS_WIDTH`| RO | Memory bus width in bits (= `256` on Jade v1). |
| `0x302` | `LINE_SIZE`| RO | Cache line size in bytes (= `32` on Jade v1). |
| `0x400` | `PERF_CTL` | RW | Performance counter control. |

---

## 8. Pipeline Stages

Each Execution Cluster has a 7-stage in-order pipeline:

1. **IF** — Instruction Fetch (32-bit, one per cycle per cohort).
2. **ID** — Decode (no register renaming — straight in-order).
3. **RR** — Read vector/scalar/predicate registers; resolve scoreboard hazards.
4. **EX1/EX2** — Execute. Single-cycle ALU; multi-cycle special-function,
   fused multiply-add, and matrix ops.
5. **MEM** — Address-generate for loads/stores; dispatch to the fixed-function
   units (Texture, Interpolation, Raster, Blend, Target Writer).
6. **WB** — Write back to a register or predicate.

The fixed-function units (Raster Unit, Texture Unit, Interpolation Unit, Blend
Unit, Display Unit) sit *off* the Execution Cluster pipeline; the cluster issues
a request and waits on a scoreboard slot.

---

## 9. Memory Model

### 9.1 Memory bus

Jade uses a **256-bit (32-byte) memory bus** between the L2 cache and main
memory, and between each Execution Cluster's L1 cache and L2. This sets the
natural transaction size for every load and store and shapes the rest of the
pipeline.

| Transaction | Width | Bytes | Notes |
|---|---|---|---|
| **Bus beat** | 256 b | 32 B | One cycle on the main bus moves one beat. |
| **Cache line** | 256 b | 32 B | One line = one beat — no multi-beat lines, so no critical-word-first logic. |
| **Coalesced word load** | 8 strands × 32 b | 32 B | A cohort `LDG.W` (32 strands × 4 B = 128 B) issues in **4 bus beats** when fully coalesced. |
| **Coalesced vec4 load** | 2 strands × 128 b | 32 B | A cohort `LDG.V4` (32 strands × 16 B = 512 B) issues in **16 bus beats** when fully coalesced. |
| **Texture tile fetch** | 256 b | 32 B | Texture units request one tile/footprint at a time as 32 B beats; a bilinear footprint = 1 beat for most narrow formats. |
| **Framebuffer write** | 256 b | 32 B | The Target Writer commits in 32 B color-block-aligned writes. |

The bus width is exposed to the driver and shader compiler via the
`BUS_WIDTH` CSR (`0x301`, RO, = `256`) so software can pick natural
alignments without baking the constant into binaries.

### 9.2 Coalescing rules

The L1 coalescer collapses concurrent strand requests in a cohort into the
smallest set of 32 B-aligned bus beats. To stay at peak bandwidth:

- Align global buffers to **32 B**.
- Use `LDG.V4` for vertex / pixel data — each beat covers two strands of
  vec4, so `LDG.V4` from a 16-B-strided array is the same cost as
  `LDG.W` on a 4-B-strided array (16 beats vs 4 beats, but 4× the data).
- Cross-32-B-boundary loads cost +1 beat. The compiler emits `LDG.V2`
  pairs when an array straddles boundaries.

### 9.3 Region table

| Region | Path | Latency | Coherency | Used for |
|---|---|---|---|---|
| **Vector register** | reg file | 1 c | n/a | Per-strand scratch. |
| **Scalar register** | reg file | 1 c | n/a | Per-cohort uniforms. |
| **EC-Local Memory** | 32 banks × 32 b | ~5 c | Coherent within the workgroup. | Cross-strand communication. Bank conflicts cost +1 c per conflict. |
| **L1 D-cache** | 32 B line, 256-bit fill | ~10 c | Read-coherent. | Buffer / constant / global. |
| **L2** | 32 B line, 256-bit fill | ~50 c | Coherent across Execution Clusters after `FENCE`. | Cross-cluster. |
| **Main mem** | 256-bit bus | ~250 c | Coherent after `FENCE` + `BAR.WG`. | Backing store. |
| **Tile buffer** | EC-local SRAM, 256-bit ports | ~3 c | EC-local, never spills until `TILE.STORE`. | Tile-based fragment ops. |

---

## 10. End-to-end Example: A Textured Triangle

```
# Vertex shader (per-vertex, one strand per vertex)
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

# Fragment shader (per-pixel, one strand per fragment)
INTRP.PC  v0, slot=1       # interpolate UV (slot 1 = o1)
TEX.2D    v4, v0, slot=0   # sample texture 0 with UVs in v0..v1
FBWR.CD   slot=0, v4       # commit color + depth (hardware depth-test + blend ran already)
```

This is the *whole* pipeline. The driver never schedules a perspective
divide, never interpolates an attribute, never blends a fragment, never
packs a color. Hardware does. The driver's job is upstream of the
shader: bind resources, set CSR state, submit work.

---

## 11. Out-of-scope (Future Extensions)

Reserved opcode space `0x32`–`0x3F`:

- **Ray traversal** (walking a bounding-volume hierarchy for ray tracing).
- **Video decode/encode** dispatch (separate codec block).
- **Mesh / task shading** (Vulkan-style alternative geometry pipeline).
- **Tensor / matrix-multiply accelerator** (for machine learning in display
  pipelines: upscaling, frame generation).

These are *not* part of Jade v1.
