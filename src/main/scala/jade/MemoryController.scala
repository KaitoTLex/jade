/*
 * Memory controller.
 * Requestors are the cores that send requests
 * Controller waits for producer (main memory) response to return data/ack to cores
 */

package jade

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._

import jade.common._

class MemoryController(
    addrBits: Int,
    dataBits: Int,
    numRequestors: Int    = 4,
    numChannels: Int      = 1, // Allocated based on external memory bandwidth
    writeEnabled: Boolean = true,
    ageBits: Int          = 8,
    ageThreshold: Int     = 128
) extends Module {
  class MemoryControllerIO(n: Int) extends Bundle {
    val readReq   = Vec(n, Flipped(Decoupled(new ReadRequest(addrBits, numRequestors)))) // requestor sends address
    val readResp  = Vec(n, Decoupled(new ReadResponse(dataBits, numRequestors))) // we forward data that dram returns
    val writeReq  = Vec(n, Flipped(Decoupled(new WriteRequest(addrBits, dataBits, numRequestors)))) // requestor sends address and data
    val writeResp = Vec(n, Decoupled(Bool())) // dram sends ack
  }

  val io = IO(new Bundle {
    val req = new MemoryControllerIO(numRequestors)
    val mem = Flipped(new MemoryControllerIO(numChannels))
  })

  // Age counted per requestor that increments per cycle
  val readAge  = RegInit(VecInit(Seq.fill(numRequestors)(0.U(ageBits.W))))
  val writeAge = RegInit(VecInit(Seq.fill(numRequestors)(0.U(ageBits.W))))

  // Arbiter per channel that decides which requestor gets served
  val readArb      = Seq.fill(numChannels)(Module(new RRArbiter(new ReadRequest(addrBits, numRequestors), numRequestors)))
  val writeArb     = Seq.fill(numChannels)(Module(new RRArbiter(new WriteRequest(addrBits, dataBits, numRequestors), numRequestors)))
  val readGranted  = WireDefault(VecInit(Seq.fill(numRequestors)(false.B)))
  val writeGranted = WireDefault(VecInit(Seq.fill(numRequestors)(false.B)))

  for (r <- 0 until numRequestors) {
    io.req.readReq(r).ready  := false.B
    io.req.writeReq(r).ready := false.B
  }

  for (c <- 0 until numChannels) {
    val readStarving     = VecInit(readAge.map(_ > ageThreshold.U))
    val writeStarving    = VecInit(writeAge.map(_ > ageThreshold.U))
    val anyReadStarving  = readStarving.asUInt.orR
    val anyWriteStarving = writeStarving.asUInt.orR
    val oldestRead       = PriorityEncoder(readStarving)
    val oldestWrite      = PriorityEncoder(writeStarving)

    // val resp = io.mem.readResp(c)
    // val ack = io.mem.writeResp(c)

    for (r <- 0 until numRequestors) {
      // if requestor is starving, ignore priority rules from arbiter
      val forceRead  = anyReadStarving && (oldestRead === r.U)
      val forceWrite = anyWriteStarving && (oldestWrite === r.U)

      // Arbiter ignores if request is already granted
      readArb(c).io.in(r).valid        := io.req.readReq(r).valid && !readGranted(r) || forceRead
      readArb(c).io.in(r).bits         := io.req.readReq(r).bits
      readArb(c).io.in(r).bits.origin  := r.U
      writeArb(c).io.in(r).valid       := io.req.writeReq(r).valid && !writeGranted(r) || forceWrite
      writeArb(c).io.in(r).bits        := io.req.writeReq(r).bits
      writeArb(c).io.in(r).bits.origin := r.U

      // increment age
      when(io.req.readReq(r).valid) { readAge(r) := readAge(r) + 1.U }.otherwise { readAge(r) := 0.U }
      when(io.req.writeReq(r).valid) { writeAge(r) := writeAge(r) + 1.U }.otherwise { writeAge(r) := 0.U }
    }

    // arbiter out drives memory channels
    io.mem.readReq(c)  <> readArb(c).io.out
    io.mem.writeReq(c) <> writeArb(c).io.out

    // reset age on grant and update exlusion mask
    // ready update for requestors if they were chosen this cycle

    // exclusion: set on valid when arbiter selects this cycle
    when(readArb(c).io.out.valid) {
      val chosen = readArb(c).io.chosen
      readGranted(chosen) := true.B
    }
    when(writeArb(c).io.out.valid) {
      val chosen = writeArb(c).io.chosen
      writeGranted(chosen) := true.B
    }

    // channel accepts and transaction is completed
    when(readArb(c).io.out.fire) {
      val chosen = readArb(c).io.chosen
      readAge(chosen)              := 0.U
      io.req.readReq(chosen).ready := true.B
    }
    when(writeArb(c).io.out.fire) {
      val chosen = writeArb(c).io.chosen
      writeAge(chosen)              := 0.U
      io.req.writeReq(chosen).ready := true.B
    }
  }

  // Responses need to be routed from channels back to original requestor
  // By default outputs shouldn't be valid
  for (r <- 0 until numRequestors) {
    io.req.readResp(r).valid  := false.B
    io.req.readResp(r).bits   := DontCare
    io.req.writeResp(r).valid := false.B
    io.req.writeResp(r).bits  := DontCare
  }

  for (c <- 0 until numChannels) {
    val readResp = io.mem.readResp(c)
    val writeAck = io.mem.writeResp(c)

    // Reroute to requestors ID
    val readDest = readResp.bits.origin
    io.req.readResp(readDest).valid := readResp.valid
    io.req.readResp(readDest).bits  := readResp.bits
    readResp.ready                  := io.req.readResp(readDest).ready

    val writeDest = writeAck.bits
    io.mem.writeResp(c).ready := true.B
  }
}
