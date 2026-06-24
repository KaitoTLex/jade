# Jade Nomenclature

This document defines Jade-native names for the ISA, hardware blocks, and driver
contract. Terms are descriptive, vendor-neutral, and defined by Jade behavior
rather than by comparison to another GPU design.

## Naming Rules

- Use ordinary functional names before branded or architecture-specific names.
- Define each term by ownership, scope, and architectural visibility.
- Keep ISA-visible names stable once published. Internal RTL names may differ.
- Avoid borrowed names for 32-wide execution groups and per-element execution
  slots. Use **Cohort** and **Strand**.

## Execution Terms

| Term | Short | Definition |
|---|---:|---|
| **Execution Cluster** | `EC` | Programmable compute block that fetches, decodes, issues, and retires instructions for resident cohorts. Owns scheduling, scoreboarding, predicate state, scalar state, and local memory ports. |
| **Cohort** | `CH` | Fixed 32-strand issue group. A cohort has one program counter, one scalar register file, one predicate set, and one path stack. Cohort width is architectural for Jade v1. |
| **Strand** | `ST` | One per-element execution context inside a cohort. A strand owns its vector registers, attributes, outputs, and private stack state. A strand maps to one vertex, fragment, or compute item. |
| **Active Set** | `AS` | 32-bit participation mask for a cohort. Bit `i` controls whether strand `i` commits side effects for the current instruction. Predicate `p0` is the architectural active set. |
| **Path Stack** | `PS` | Eight-entry hardware stack used by split and join control flow. Each entry stores a saved active set and join target. |
| **Helper Strand** | `HS` | Fragment-stage strand kept alive only to provide derivative or coverage data. It does not commit color, depth, stores, or normal side effects. |
| **Cohort Collective** | `COH` | Operation that communicates or reduces values across active strands in one cohort. Examples include vote, ballot, broadcast, and shuffle. |

## Dispatch Terms

| Term | Definition |
|---|---|
| **Vertex Dispatch** | Launches cohorts where each strand processes one input vertex. |
| **Fragment Dispatch** | Launches cohorts from raster output where each strand processes one fragment position or helper position. |
| **Compute Dispatch** | Launches cohorts where each strand processes one compute item. |
| **Workgroup** | Compute scheduling unit made from one or more cohorts that share EC-local memory and can synchronize with a workgroup barrier. |
| **Draw Dispatch** | Graphics submission that binds state, starts vertex dispatch, runs fixed-function primitive/raster stages, and emits fragment dispatch. |
| **Dispatch Record** | Driver-built command-stream entry that names the shader entry point, dispatch shape, resource bindings, and relevant fixed-function state. |

## Register And State Terms

| Term | ISA Form | Definition |
|---|---:|---|
| **Vector Register** | `v0`-`v31` | Per-strand 32-bit register. Each strand has its own value for each vector register name. |
| **Attribute Register** | `a0`-`a15` | Per-strand read-only input register initialized by dispatch or interpolation. |
| **Output Register** | `o0`-`o7` | Per-strand output register committed to the next graphics stage or final target. |
| **Scalar Register** | `s0`-`s15` | Per-cohort 32-bit register shared by all strands in that cohort. |
| **Predicate Register** | `p0`-`p7` | Per-cohort 32-bit bitset. Each bit corresponds to one strand. `p0` is the active set. |
| **Program Counter** | `pc` | Per-cohort instruction pointer. |
| **Link Register** | `lr` | Per-cohort return address register used by call and return instructions. |
| **Control Register** | `CSR` | Hardware-visible control/status register read or written through `CSR` instructions. |

## CSR Naming

| CSR Name | Definition |
|---|---|
| `COHORT_ID` | Cohort index within the current execution cluster. |
| `STRAND_ID` | Strand index within the current cohort, in the range `0..31`. |
| `EC_ID` | Execution cluster index within the GPU. |
| `ITEM_ID.x/y/z` | Dispatch item coordinate for the current strand. |
| `CYCLE_L/H` | Low and high halves of the free-running cycle counter. |
| `BUS_WIDTH` | Memory bus width in bits. Jade v1 exposes `256`. |
| `LINE_SIZE` | Cache line and bus-beat size in bytes. Jade v1 exposes `32`. |

## Instruction Family Names

| Family | Definition |
|---|---|
| `SALU` | Scalar arithmetic on per-cohort scalar registers. |
| `VALU.I` | Integer arithmetic on per-strand vector registers. |
| `VALU.F` | Floating-point arithmetic on per-strand vector registers. |
| `COH` | Cohort collective operations across active strands. Preferred forms: `COH.ANY`, `COH.ALL`, `COH.BALLOT`, `COH.SHUFFLE`, `COH.BCAST`. |
| `BAR.CT` | Cohort-local synchronization and reconvergence barrier. |
| `BAR.WG` | Workgroup synchronization across all cohorts in the workgroup. |
| `SPLIT` | Predicate-controlled path split. Pushes the inactive path onto the path stack. |
| `JOIN` | Path-stack join point. Restores the next pending active set or completes the split region. |

## Fixed-Function Terms

| Term | Definition |
|---|---|
| **Primitive Unit** | Builds points, lines, and triangles from shader outputs. |
| **Clip Unit** | Rejects or marks geometry outside clip planes. |
| **Raster Unit** | Converts primitives into fragment positions, coverage, barycentric basis, and depth slopes. |
| **Interpolation Unit** | Produces per-fragment attributes from vertex outputs and raster-generated interpolation data. |
| **Texture Unit** | Performs texture address calculation, filtering, format decode, and sampled-image reads. |
| **Image Unit** | Performs typed, unfiltered image loads, stores, and image atomics. |
| **Depth-Stencil Unit** | Performs depth tests, stencil tests, depth writes, and stencil updates. |
| **Blend Unit** | Combines shader color output with render-target contents according to bound blend state. |
| **Target Writer** | Commits color, depth, stencil, and multisample data to render targets. |
| **Display Unit** | Drives scan-out, present, mode setting, cursor composition, gamma, HDR metadata, and vblank signaling. |
| **Copy Unit** | Performs asynchronous memory copies and fills outside the shader issue path. |

## Memory Terms

| Term | Definition |
|---|---|
| **Bus Beat** | One 32-byte transfer on Jade v1 memory fabric. |
| **Cache Line** | One 32-byte cache allocation unit. Equal to one bus beat on Jade v1. |
| **EC-Local Memory** | Explicitly addressed memory local to one execution cluster and shared by a workgroup. |
| **Private Stack** | Per-strand spill and private storage addressed by `LDL` and `STL`. |
| **Binding Slot** | Small integer handle naming a texture, sampler, constant buffer, storage buffer, or render target in the current binding tables. |
| **Tile Buffer** | On-chip render storage used by tile-based paths before final target writeback. |

## Driver Terms

| Term | Definition |
|---|---|
| **User Driver** | User-mode component that owns API objects, shader compilation, resource binding, and command-stream construction. |
| **Kernel Driver** | Kernel-mode component that owns queues, memory mappings, interrupts, display integration, clocks, and protected submission. |
| **Command Stream** | Linear GPU-readable stream of state writes, binding updates, dispatch records, and synchronization commands. |
| **State Packet** | Command-stream record that writes one or more CSRs or fixed-function configuration fields. |
| **Binding Snapshot** | Complete set of resource-slot bindings used by one draw or compute dispatch. |
| **Shader Image** | Driver-emitted sequence of 32-bit Jade instructions plus metadata needed for dispatch. |
| **Stage Link Table** | Driver-built mapping from vertex outputs to fragment attributes. |

## Canonical Replacements

| Use | Meaning |
|---|---|
| **Execution Cluster** | Programmable shader execution block. |
| **Cohort** | 32-strand lockstep issue group. |
| **Strand** | One per-element execution context. |
| **Active Set** | Current per-strand participation mask. |
| **Path Stack** | Hardware control-flow stack for split and join. |
| **Cohort Collective** | Cross-strand operation within one cohort. |
| **EC-Local Memory** | Explicit shared memory inside one execution cluster. |
