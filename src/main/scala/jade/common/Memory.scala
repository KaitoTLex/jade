package jade.common

import chisel3._
import chisel3.util._

// Memory interfaces
class ReadRequest(addrBits: Int, n: Int) extends Bundle {
  val address = UInt(addrBits.W)
  val origin  = UInt(log2Ceil(n).W)
}

class ReadResponse(dataBits: Int, n: Int) extends Bundle {
  val data   = UInt(dataBits.W)
  val origin = UInt(log2Ceil(n).W)
}

class WriteRequest(addrBits: Int, dataBits: Int, n: Int) extends Bundle {
  val address = UInt(addrBits.W)
  val data    = UInt(dataBits.W)
  val origin  = UInt(log2Ceil(n).W)
}
