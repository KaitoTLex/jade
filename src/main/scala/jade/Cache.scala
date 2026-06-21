package jade

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals._

import jade.lib._

// TODO: Coalescing

class Cache(
    numRequestors: Int,
    dataBits: Int  = 32,
    addrBits: Int  = 32,
    lineWords: Int = 4 // Number of words in each line
) extends Module {
  class CacheIO extends Bundle {
    val readReq   = Vec(numRequestors, Flipped(Decoupled(new ReadRequest(addrBits, numRequestors))))
    val readResp  = Vec(numRequestors, Decoupled(new ReadResponse(dataBits, numRequestors)))
    val writeReq  = Vec(numRequestors, Flipped(Decoupled(new WriteRequest(addrBits, dataBits, numRequestors))))
    val writeResp = Vec(numRequestors, Decoupled(Bool()))
  }

  val io = IO(new CacheIO);
}

/** Set associative cache with `numSets` sets each with `numWays` ways. Replaces least recently used in case of conflict. Write policy is writeback; i.e. writes
  * are committed to main memory when a dirty line is evicted.
  *
  * @param numRequestors
  * @param lineWords
  * @param numSets
  * @param numWays
  * @param tagBits
  * @param idxBits
  */
class LRUSetAssociativeCache(
    numRequestors: Int,
    dataBits: Int  = 32,
    addrBits: Int  = 32,
    lineWords: Int = 4, // Number of words in each line
    numSets: Int,
    numWays: Int = 4, // Number of lines per set
    tagBits: Int,
    idxBits: Int
) extends Cache(numRequestors, dataBits, addrBits, lineWords) {
  val lineSize = lineWords * 4;
  val numLines = numWays * numSets;
  val byteSize = lineSize * numLines;

  val data = SRAM.masked(numLines, Vec(lineWords, UInt(dataBits.W)), numRequestors, numRequestors, numRequestors)
  val tags = SRAM.masked(numLines, Vec(2, Bool()), numRequestors, numRequestors, numRequestors) // For each line, valid / dirty

  println(s"Instantiating cache with ${data.addrWidth}-bit addresses")

  val addrWidth                      = data.addrWidth
  val offsetBits                     = addrWidth - tagBits - idxBits;
  def getOffset(address: UInt): UInt = address(offsetBits - 1, 0)
  def getIdx(address: UInt): UInt    = address(idxBits + offsetBits - 1, offsetBits)
  def getTag(address: UInt): UInt    = address(addrWidth - 1, idxBits + offsetBits)

  val ready :: clearing :: Nil = Enum(3)

  val state = RegInit(ready)
  val cl    = RegInit(0.U(addrWidth.W))

  when(reset.asBool) {
    state := clearing
  }

  switch(state) {
    is(ready) {
      // Look at all requests
      for (rq <- io.readReq) {
        // Find set corresponding to request idx

      }
    }
    is(clearing) {
      // Don't gaf about security so just zero the metadata
      for (wp <- tags.writePorts) {
        wp.enable  := true.B
        wp.address := cl
        wp.data    := Vec(2, false.B)
        wp.mask match {
          case Some(mask) => mask := Vec(2, true.B)
          case _          => {}
        }

        when(cl === (numLines - 1).U) {
          state := idle
        }.otherwise {
          cl := cl + 1.U
        }
      }
    }
  }
}
