package jade

import chisel3._
import chisel3.util._

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
    val funct11 = Input(ValuIOp())
    val a       = Input(Vec(lanes, UInt(32.W)))
    val b       = Input(Vec(lanes, UInt(32.W)))
    val s       = Input(UInt(32.W)) // if immediate, control needs to sign extend
    val pred    = Input(Vec(lanes, Bool())) // execution mask
    val res     = Output(Vec(lanes, UInt(32.W)))
    val valid   = Output(Bool())
  })

  io.valid := true.B
  io.res   := DontCare

  for (i <- 0 until lanes) {
    when(io.pred(i) === true.B) {
      switch(io.funct11) {
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
