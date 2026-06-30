package jade

import chisel3._
import chisel3.util._

object CmpOp extends ChiselEnum {
  val CMPEQ_I, CMPNE_I, CMPLT_I, CMPLE_I, CMPLTU = Value // 0x000..0x004
  val CMPEQ_F                                    = Value(0x010.U(11.W))
  val CMPNE_F, CMPLT_F, CMPLE_F, CMPUO           = Value // 0x011..0x014
  val CMPS_EQ                                    = Value(0x020.U)
}

class Cmp(lanes: Int = 32) extends Module {
  val io = IO(new Bundle {
    val op    = Input(CmpOp())
    val a     = Input(Vec(lanes, UInt(32.W)))
    val b     = Input(Vec(lanes, UInt(32.W)))
    val sa    = Input(UInt(32.W)) // operands for CMPS.*
    val sb    = Input(UInt(32.W))
    val pred  = Input(Vec(lanes, Bool())) // execution mask
    val res   = Output(Vec(lanes, Bool())) // one predicate bit per strand
    val valid = Output(Bool())
  })

  io.valid := true.B
  io.res   := DontCare

  // float NaN, ex = {1} or frac = {!Z}
  private def isNaN(x: UInt): Bool = x(30, 23).andR && x(22, 0).orR

  private def fkey(x: UInt): UInt = Mux(x(31), ~x, x | "h80000000".U)

  for (i <- 0 until lanes) {
    when(io.pred(i) === true.B) {
      val a = io.a(i)
      val b = io.b(i)

      val unordered = isNaN(a) || isNaN(b) // either side is NaN
      val bothZero  = a(30, 0) === 0.U && b(30, 0) === 0.U // +0 equals -0
      val feq       = !unordered && (a === b || bothZero)

      switch(io.op) {
        is(CmpOp.CMPEQ_I) { io.res(i) := a === b }
        is(CmpOp.CMPNE_I) { io.res(i) := a =/= b }
        is(CmpOp.CMPLT_I) { io.res(i) := a.asSInt < b.asSInt }
        is(CmpOp.CMPLE_I) { io.res(i) := a.asSInt <= b.asSInt }
        is(CmpOp.CMPLTU)  { io.res(i) := a < b }
        is(CmpOp.CMPEQ_F) { io.res(i) := feq }
        is(CmpOp.CMPNE_F) { io.res(i) := !unordered && !feq }
        is(CmpOp.CMPLT_F) { io.res(i) := !unordered && fkey(a) < fkey(b) }
        is(CmpOp.CMPLE_F) { io.res(i) := !unordered && fkey(a) <= fkey(b) }
        is(CmpOp.CMPUO)   { io.res(i) := unordered }
        is(CmpOp.CMPS_EQ) { io.res(i) := io.sa === io.sb }
      }
    }
  }
}
