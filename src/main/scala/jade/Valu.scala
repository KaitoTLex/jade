package jade

import chisel3._
import chisel3.util._

import jade.common.F32

object ValuIOp extends ChiselEnum {
  val VADD, VADD_S, VSUB, VMUL, VMULH, VMAD = Value
  val VAND                                  = Value(0x010.U(11.W))
  val VOR, VXOR                             = Value
  val VSHL                                  = Value(0x020.U)
  val VSHR, VSRA                            = Value
  val VMIN                                  = Value(0x030.U)
  val VMAX                                  = Value
  val VMOV                                  = Value(0x040.U)
  val VBCST, VLI                            = Value
}

// Vector integer ALU
class ValuI(lanes: Int = 32) extends Module {
  val io = IO(new Bundle {
    val op    = Input(ValuIOp())
    val a     = Input(Vec(lanes, UInt(32.W)))
    val b     = Input(Vec(lanes, UInt(32.W)))
    val s     = Input(UInt(32.W)) // if immediate, control needs to sign extend
    val pred  = Input(Vec(lanes, Bool())) // execution mask
    val res   = Output(Vec(lanes, UInt(32.W)))
    val valid = Output(Bool())
  })

  io.valid := true.B
  io.res   := DontCare

  for (i <- 0 until lanes) {
    when(io.pred(i) === true.B) {
      switch(io.op) {
        is(ValuIOp.VADD)   { io.res(i) := io.a(i) + io.b(i) }
        is(ValuIOp.VADD_S) { io.res(i) := io.a(i) + io.s }
        is(ValuIOp.VSUB)   { io.res(i) := io.a(i) - io.b(i) }
        is(ValuIOp.VMUL)   { io.res(i) := (io.a(i) * io.b(i))(31, 0) } // Since multiplication is performed on 32b primitives its not that bad
        is(ValuIOp.VMULH)  { io.res(i) := (io.a(i) * io.b(i))(63, 32) }
        is(ValuIOp.VMAD)   { io.res(i) := io.res(i) + (io.a(i) * io.b(i)) }
        is(ValuIOp.VAND)   { io.res(i) := io.a(i) & io.b(i) }
        is(ValuIOp.VOR)    { io.res(i) := io.a(i) | io.b(i) }
        is(ValuIOp.VXOR)   { io.res(i) := io.a(i) ^ io.b(i) }
        is(ValuIOp.VSHL)   { io.res(i) := io.a(i) << io.b(i) }
        is(ValuIOp.VSHR)   { io.res(i) := io.a(i) >> io.b(i) }
        is(ValuIOp.VSRA)   { io.res(i) := io.a(i).asSInt >> io.b(i) }
        is(ValuIOp.VMIN)   { io.res(i) := io.a(i).min(io.b(i)) }
        is(ValuIOp.VMAX)   { io.res(i) := io.a(i).max(io.b(i)) }
        is(ValuIOp.VMOV)   { io.res(i) := io.a(i) }
        is(ValuIOp.VBCST)  { io.res(i) := io.s }
        is(ValuIOp.VLI)    { io.res(i) := io.s }
      }
    }
  }
}

object ValuFOp extends ChiselEnum {
  val FADD, FSUB, FMUL, FDIV, FMAD, FFMA, FMIN, FMAX, FABS, FNEG, FSAT = Value
  val FFLR                                                             = Value(0x010.U(11.W))
  val FCEL, FRND, FTRC, FFRC                                           = Value
  val FMOV                                                             = Value(0x040.U)
  val FLI                                                              = Value
}

/** Vector Floating-point ALU
  *
  * IEEE-754 single precision, round to nearest even, toggleable flush denormals to zero.
  *
  * @param lanes
  *   Number of lanes
  */
class ValuF(lanes: Int) extends Module {
  val io = IO(new Bundle {
    val op    = Input(ValuFOp())
    val a     = Input(Vec(lanes, new F32()))
    val b     = Input(Vec(lanes, new F32()))
    val pred  = Input(Vec(lanes, Bool())) // execution mask
    val ftz   = Input(Bool()) // Flush denormals to zero?
    val res   = Output(Vec(lanes, UInt(32.W)))
    val valid = Output(Bool())
  })

  io.valid := true.B
  io.res   := DontCare

  for (i <- 0 until lanes) {
    when(io.pred(i) === true.B) {
      switch(io.op) {
        is(ValuFOp.FADD) {}
        is(ValuFOp.FSUB) {}
        is(ValuFOp.FMUL) {}
        is(ValuFOp.FDIV) {}
        is(ValuFOp.FMAD) {}
        is(ValuFOp.FFMA) {}
        is(ValuFOp.FMIN) {}
        is(ValuFOp.FMAX) {}
        is(ValuFOp.FABS) {}
        is(ValuFOp.FNEG) {}
        is(ValuFOp.FSAT) {}
        is(ValuFOp.FFLR) {}
        is(ValuFOp.FCEL) {}
        is(ValuFOp.FRND) {}
        is(ValuFOp.FTRC) {}
        is(ValuFOp.FFRC) {}
        is(ValuFOp.FMOV) {}
        is(ValuFOp.FLI)  {}
      }
    }
  }
}

class FpClassification extends Bundle {
  val normal = Bool()
  val zero   = Bool()
  val denorm = Bool()
  val inf    = Bool()
  val nan    = Bool()
  val poison = Bool() // AKA signalling NaN
}

private class FpClassify extends Module {
  val io = IO(new Bundle {
    val x              = Input(new F32())
    val classification = Output(new FpClassification())
  })

  val exp         = io.x.exp
  val significand = io.x.significand

  io.classification.normal := (exp =/= 0.U) && !(exp.andR)
  io.classification.zero   := (exp === 0.U) && (significand === 0.U)
  io.classification.denorm := (exp === 0.U) && !io.classification.zero
  io.classification.inf    := exp.andR && significand === 0.U
  io.classification.nan    := exp.andR && significand =/= 0.U
  io.classification.poison := io.classification.nan && (significand(significand.getWidth - 1) === true.B)
}

private class FpAdd extends Module {
  val io = IO(new Bundle {
    val mode           = Input(Bool()) // 0 = add, 1 = sub
    val a              = Input(new F32())
    val b              = Input(new F32())
    val res            = Output(new F32())
    val needs_rounding = Output(Bool())
    val valid          = Output(Bool())
  })
}
