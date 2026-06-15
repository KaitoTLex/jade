package jade.Salu

import chisel3._
import chisel3.util._
import chisel3.tester._
import chisel3.tester.RawTester.test
import dotvisualizer._

object SaluFn {
  private def fn(x: Int): UInt = x.U(11.W)

  val SADD = fn(0x000) // sd = s1 + s2
  val SADDI = fn(0x001) // sd = s1 + sext(imm16)
  val SSUB = fn(0x002) // sd = s1 - s2
  val SMUL = fn(0x003) // sd = (s1 * s2)[31:0]
  val SMULH = fn(0x004) // sd = (s1 * s2)[63:32], signed
  val SDIV = fn(0x005) // signed
  val SREM = fn(0x006) // signed
  val SAND = fn(0x010)
  val SOR = fn(0x011)
  val SXOR = fn(0x012)
  val SNOT = fn(0x013) // sd = !s1
  val SSHL = fn(0x020) // logical left
  val SSHR = fn(0x021) // logical right
  val SSRA = fn(0x022) // arithmetic right
  val SMIN = fn(0x030) // signed
  val SMAX = fn(0x031)
  val SMOV = fn(0x040) // sd = s1
  val SLI = fn(0x041) // sd = sext(imm16)
  val SLUI = fn(0x042) // sd = imm16 << 16
}

class SaluReq extends Bundle {
  val funct11 = UInt(11.W)
  val s1 = UInt(32.W)
  val s2 = UInt(32.W)
  val imm16 = UInt(16.W)
}

class Salu extends Module {
  val io = IO(new Bundle {
    val req = Flipped(Valid(new SaluReq)) // inputs
    val sd = Valid(UInt(32.W))
  })

}
