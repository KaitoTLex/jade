package jade.common

import chisel3._
import chisel3.util._

// Scalar general purpose register file (per wavefront)
class SGPR(lanes: Int = 32) extends Module {
  val io = IO(new Bundle {
    val wData  = Input(UInt(32.W))
    val rd     = Input(UInt(4.W)) // s
    val rs1    = Input(UInt(4.W)) // s
    val rs2    = Input(UInt(4.W)) // s
    val rsp    = Input(UInt(3.W)) // p
    val wEn    = Input(Bool())
    val rData1 = Output(UInt(32.W))
    val rData2 = Output(UInt(32.W))
    val rPred  = Output(Vec(lanes, Bool()))
  })

  val s    = RegInit(Vec(16, UInt(32.W)))
  val p    = RegInit(Vec(8, Vec(lanes, Bool())))
  val pc   = RegInit(UInt(32.W))
  val lr   = RegInit(UInt(32.W))
  val mstk = RegInit(Vec(lanes, UInt(32.W)))

  io.rData1 := s(io.rs1)
  io.rData2 := s(io.rs2)
  io.rPred  := p(io.rsp)

  when(io.wEn) {
    s(io.rd) := io.wData
  }
}

// Vector general purpose register file (per lane)
class VGPR extends Module {
  val io = IO(new Bundle {
    val wData  = Input(UInt(32.W))
    val rd     = Input(UInt(5.W))
    val rs1    = Input(UInt(5.W))
    val rs2    = Input(UInt(5.W))
    val rsa    = Input(UInt(4.W))
    val wEn    = Input(Bool())
    val rData1 = Output(UInt(32.W))
    val rData2 = Output(UInt(32.W))
    val rAttr  = Output(UInt(32.W))
  })

  val v = RegInit(Vec(32, UInt(32.W)))
  val a = RegInit(Vec(16, UInt(32.W))) // Read only for core
  val o = RegInit(Vec(8, UInt(32.W)))

  io.rData1 := v(io.rs1)
  io.rData2 := v(io.rs2)
  io.rAttr  := a(io.rsa)

  when(io.wEn) {
    v(io.rd) := io.wData
  }
}
