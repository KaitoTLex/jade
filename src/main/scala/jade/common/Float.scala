package jade.common

import chisel3._

class F32 extends Bundle {
  val sign        = Bool()
  val exp         = UInt(8.W)
  val significand = UInt(23.W)
}
