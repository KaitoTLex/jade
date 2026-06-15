package jade.salu

import chisel3._
import chisel3.util._

object SaluFn {
  private def fn(x: Int): UInt = x.U(11.W)
  // ISA funct
  val SADD = fn(0x000) // sd = s1 + s2
  val SADDI = fn(0x001) // sd = s1 + sext(imm16)
  val SSUB = fn(0x002) // sd = s1 - s2
  val SMUL = fn(0x003) // sd = (s1 * s2)[31:0]
  val SMULH = fn(0x004) // sd = (s1 * s2)[63:32], signed
  val SDIV = fn(0x005) // signed divide
  val SREM = fn(0x006) // signed remainder
  val SAND = fn(0x010)
  val SOR = fn(0x011)
  val SXOR = fn(0x012)
  val SNOT = fn(0x013) // sd = ~s1
  val SSHL = fn(0x020) // logical shift left
  val SSHR = fn(0x021) // logical shift right
  val SSRA = fn(0x022) // arithmetic shift right
  val SMIN = fn(0x030)
  val SMAX = fn(0x031)
  val SMOV = fn(0x040) // sd = s1
  val SLI = fn(0x041) // sd = sext(imm16)
  val SLUI = fn(0x042) // sd = imm16 << 16
}

//SALU inputs
class SaluReq extends Bundle {
  val funct11 = UInt(11.W)
  val s1 = UInt(32.W) // scalar GPR operand 1
  val s2 = UInt(32.W) // scalar GPR operand 2 (r type only)
  val imm16 = UInt(16.W) // immediate
}

//Core
class Salu extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Valid(new SaluReq))
    val sd = Valid(UInt(32.W))
  })

  val f = io.req.bits.funct11
  val a = io.req.bits.s1
  val b = io.req.bits.s2

  // sign extend imm16 to 32 bits: replicate bit 15
  val immSext = Cat(Fill(16, io.req.bits.imm16(15)), io.req.bits.imm16)

  val aS = a.asSInt
  val bS = b.asSInt

  val shamt = b(4, 0)

  val prod64 = (aS * bS).asUInt // 64 bits wide

  val divByZero = b === 0.U
  val quot = Mux(divByZero, ~0.U(32.W), ((aS / bS).asUInt)(31, 0))
  val rem = Mux(divByZero, a, ((aS % bS).asUInt)(31, 0))

  val sd = WireDefault(0.U(32.W))
  switch(f) {
    is(SaluFn.SADD) { sd := a + b }
    is(SaluFn.SADDI) { sd := a + immSext }
    is(SaluFn.SSUB) { sd := a - b }
    is(SaluFn.SMUL) { sd := prod64(31, 0) }
    is(SaluFn.SMULH) { sd := prod64(63, 32) }
    is(SaluFn.SDIV) { sd := quot }
    is(SaluFn.SREM) { sd := rem }
    is(SaluFn.SAND) { sd := a & b }
    is(SaluFn.SOR) { sd := a | b }
    is(SaluFn.SXOR) { sd := a ^ b }
    is(SaluFn.SNOT) { sd := ~a }
    is(SaluFn.SSHL) { sd := (a << shamt)(31, 0) }
    is(SaluFn.SSHR) { sd := a >> shamt }
    is(SaluFn.SSRA) { sd := (aS >> shamt).asUInt }
    is(SaluFn.SMIN) { sd := Mux(aS < bS, a, b) }
    is(SaluFn.SMAX) { sd := Mux(aS < bS, b, a) }
    is(SaluFn.SMOV) { sd := a }
    is(SaluFn.SLI) { sd := immSext }
    is(SaluFn.SLUI) { sd := Cat(io.req.bits.imm16, 0.U(16.W)) }
  }

  io.sd.valid := io.req.valid
  io.sd.bits := sd

  when(io.req.valid && f === SaluFn.SADD) {
    assert(
      io.sd.bits === io.req.bits.s1 + io.req.bits.s2,
      "SADD violation"
    )
  }
  when(io.req.valid && f === SaluFn.SSUB) {
    assert(
      io.sd.bits === io.req.bits.s1 - io.req.bits.s2,
      "SSUB violation"
    )
  }
}
//TODO: proper GPU piplining
class SaluPipelined extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Valid(new SaluReq))
    val sd = Valid(UInt(32.W))
  })

  val core = Module(new Salu)
  core.io.req := io.req

  io.sd.valid := RegNext(core.io.sd.valid, init = false.B)
  io.sd.bits := RegNext(core.io.sd.bits)
}

/* sv synthesis */
// object SaluMain extends App {
//   println(circt.stage.ChiselStage.emitSystemVerilog(new Salu))
// }
