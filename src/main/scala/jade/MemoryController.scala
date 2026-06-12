/*
 * Memory controller.
 * Consumers are the cores that send requests
 * Controller waits for producer (main memory) response to return data to cores
 */

package jade

import chisel3._
import chisel3.util._

class MemoryController(
    addrBits: Int,
    dataBits: Int,
    numConsumers: Int = 4,
    numChannels: Int = 1, // Allocated based on external memory bandwidth
    writeEnabled: Boolean = true
) extends Module {
  // Payload bundles
  class ReadPayload(ports: Int) extends Bundle {
    val address = Output(Vec(ports, UInt(addrBits.W)))
    val data = Input(Vec(ports, UInt(dataBits.W)))
  }

  class WritePayload(ports: Int) extends Bundle {
    val address = Output(Vec(ports, UInt(addrBits.W)))
    val data = Output(Vec(ports, UInt(dataBits.W)))
  }

  // Ready: Consumer is ready
  // Valid: Producer has valid output
  val io = IO(new Bundle {
    val consumer = new Bundle {
      val read = Flipped(Decoupled(new ReadPayload(numConsumers)))
      val write = Flipped(Decoupled(new WritePayload(numConsumers)))
    }
    val memory = new Bundle {
      val read = Decoupled(new ReadPayload(numChannels))
      val write = Decoupled(new WritePayload(numChannels))
    }
  })
}
