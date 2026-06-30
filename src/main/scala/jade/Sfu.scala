//In theory this is the SFU(Special Function Unit) that abide by the IEEE-754 standard when approximating

package jade

import chisel3._
import chisel3.util._

object SfuFn {
  private def fn(x: Int): UInt = x.U(11.W)

  val RCP   = fn(0x000)
  val RSQRT = fn(0x001)
  val SQRT  = fn(0x002)
  val EXP2  = fn(0x003)
  val LOG2  = fn(0x004)
  val SIN   = fn(0x005)
  val COS   = fn(0x006)
  val TAN   = fn(0x007)
  val ATAN  = fn(0x008)
  val ATAN2 = fn(0x009)
  val POW   = fn(0x00a)
}

class SfuReq(lanes: Int) extends Bundle {
  val funct11 = UInt(11.W)
  val a       = Vec(lanes, UInt(32.W))
  val b       = Vec(lanes, UInt(32.W))
  val pred    = Vec(lanes, Bool())
}

class Sfu(lanes: Int = 32, latency: Int = 4) extends Module {
  require(lanes > 0, "SFU needs at least one lane") // input handling, refuse 0 lanes
  require(latency > 0, "SFU latency must be positive") // multi-cycle latency refuse 0 latency

  val io = IO(new Bundle {
    val req = Flipped(Valid(new SfuReq(lanes)))
    val vd  = Valid(Vec(lanes, UInt(32.W)))
  }) // need funct11 opcode

  // pred(i) - perlane copy of funct11

  // Error handeling
  private val QNaN   = "h7fc00000".U(32.W)
  private val PosInf = "h7f800000".U(32.W)
  private val NegInf = "hff800000".U(32.W)

  // Random Consts, IEEE bit level magic, 2^16
  private val OneQ       = 65536.S(32.W)
  private val TwoQ       = 131072.S(32.W)
  private val PiQ        = 205887.S(32.W)
  private val TwoPiQ     = 411775.S(32.W)
  private val HalfPiQ    = 102944.S(32.W)
  private val QuarterPiQ = 51472.S(32.W)

  // "hazard handeling"
  private def isNan(x: UInt): Bool       = x(30, 23) === "hff".U && x(22, 0).orR
  private def isInf(x: UInt): Bool       = x(30, 23) === "hff".U && !x(22, 0).orR
  private def isZero(x: UInt): Bool      = x(30, 0) === 0.U
  private def signInf(sign: Bool): UInt  = Cat(sign, "hff".U(8.W), 0.U(23.W))
  private def signZero(sign: Bool): UInt = Cat(sign, 0.U(31.W))

  private def satQ16(x: SInt): SInt = { // wide to 64
    val max = 2147483647.S(64.W)
    val min = (-2147483648L).S(64.W)
    val x64 = x.asSInt.pad(64)

    Mux(
      x64 > max,
      2147483647.S(32.W),
      Mux(x64 < min, (-2147483648L).S(32.W), x64.asUInt(31, 0).asSInt)
    )
  }

  private def absQ(x: SInt): SInt = Mux(x < 0.S, -x, x)

  private def mulQ16(a: SInt, b: SInt): SInt = satQ16((a * b) >> 16) // Q32.32

  private def divQ16(a: SInt, b: SInt): SInt = { // irescaling
    val den = Mux(b === 0.S, 1.S(32.W), b)
    satQ16((a.pad(64) << 16) / den.pad(64))
  }

  private def f32ToQ16(x: UInt): SInt = { // mantissa
    val sign = x(31)
    val exp  = x(30, 23)
    val frac = x(22, 0)
    val mant = Cat(1.U(1.W), frac) // 24 bit

    val leftShift  = (exp - 134.U)(4, 0)
    val rightShift = (134.U(8.W) - exp)(7, 0)
    val left       = (mant << leftShift)(31, 0)
    val right      = mant >> rightShift
    val mag = Mux(
      exp === 0.U,
      0.U(32.W),
      Mux(exp > 157.U, "h7fffffff".U(32.W), Mux(exp >= 134.U, left, right))
    )

    Mux(sign, -mag.asSInt, mag.asSInt)
  }

  private def q16ToF32(q: SInt): UInt = { // align mantissa 23 bit
    val sign = q < 0.S
    val mag  = Mux(sign, (-q).asUInt, q.asUInt)(31, 0)
    val zero = mag === 0.U

    val leadingZeros = PriorityEncoder(Reverse(mag))
    val msb          = 31.U(5.W) - leadingZeros
    val exp          = msb + 111.U
    val rightShift   = msb - 23.U
    val leftShift    = 23.U - msb
    val normRight    = mag >> rightShift(4, 0)
    val normLeft     = (mag << leftShift(4, 0))(23, 0)
    val norm         = Mux(msb >= 23.U, normRight(23, 0), normLeft)

    Mux(zero, Cat(sign, 0.U(31.W)), Cat(sign, exp(7, 0), norm(22, 0)))
  }

  private def reduceRadians(x: SInt): SInt = {
    val wrapped = x % TwoPiQ
    Mux(wrapped > PiQ, wrapped - TwoPiQ, Mux(wrapped < -PiQ, wrapped + TwoPiQ, wrapped))
  }

  private def sinQ16(x: SInt): SInt = {
    val xr = reduceRadians(x)
    val ax = absQ(xr)
    val y0 = mulQ16(xr, 83443.S(32.W)) - mulQ16(mulQ16(xr, ax), 26561.S(32.W))
    satQ16(y0 + mulQ16(14746.S(32.W), mulQ16(y0, absQ(y0)) - y0))
  }

  private def atanQ16(x: SInt): SInt = {
    def atanSmall(v: SInt): SInt = {
      val av    = absQ(v)
      val scale = QuarterPiQ + mulQ16(17891.S(32.W), OneQ - av)
      mulQ16(v, scale)
    }

    val ax    = absQ(x)
    val inv   = divQ16(OneQ, ax)
    val large = HalfPiQ - atanSmall(inv)
    val mag   = Mux(ax <= OneQ, atanSmall(ax), large)
    Mux(x < 0.S, -mag, mag)
  }

  private def log2Q16(x: UInt): SInt = {
    val exp   = x(30, 23)
    val frac  = x(22, 0)
    val whole = (exp.zext - 127.S) << 16
    val m     = Cat(1.U(1.W), frac(22, 7)).asSInt
    val t     = m - OneQ
    val t2    = mulQ16(t, t)
    val t3    = mulQ16(t2, t)

    satQ16(whole + mulQ16(94548.S(32.W), t) - mulQ16(47274.S(32.W), t2) + mulQ16(31516.S(32.W), t3))
  }

  private def exp2Q16(x: SInt): UInt = {
    val floor    = x >> 16
    val frac     = x - (floor << 16)
    val f2       = mulQ16(frac, frac)
    val f3       = mulQ16(f2, frac)
    val mant0    = OneQ + mulQ16(45426.S(32.W), frac) + mulQ16(15747.S(32.W), f2) + mulQ16(3638.S(32.W), f3)
    val mant     = Mux(mant0 >= TwoQ, TwoQ - 1.S, Mux(mant0 < OneQ, OneQ, mant0))
    val exp      = floor + 127.S
    val fracBits = ((mant - OneQ).asUInt(15, 0) << 7)(22, 0)

    Mux(
      x < (-126 * 65536).S,
      0.U(32.W),
      Mux(x > (127 * 65536).S, PosInf, Cat(0.U(1.W), exp.asUInt(7, 0), fracBits))
    )
  }

  private def rcpBits(x: UInt): UInt = { // 2^32/x
    val q   = f32ToQ16(x)
    val den = Mux(q === 0.S, 1.S(32.W), q)
    val out = q16ToF32(satQ16((BigInt(1) << 32).S(64.W) / den.pad(64)))

    Mux(
      isNan(x),
      QNaN,
      Mux(isZero(x), signInf(x(31)), Mux(isInf(x), signZero(x(31)), Mux(q === 0.S, signInf(x(31)), out)))
    )
  }

  private def rsqrtBits(x: UInt): UInt = { // evil floating point bit level hacking
    val approx = ("h5f3759df".U(32.W) - (x >> 1))(31, 0) // what the fuck?
    Mux(
      isNan(x) || (x(31) && !isZero(x)),
      QNaN,
      Mux(isZero(x), signInf(x(31)), Mux(isInf(x), 0.U(32.W), approx)) // newton method
    )
  }
  // 22bit accuracy
  private def sqrtBits(x: UInt): UInt = {
    val approx = ((x >> 1) + "h1fc00000".U)(31, 0)
    Mux(
      isNan(x) || (x(31) && !isZero(x)),
      QNaN,
      Mux(isZero(x), x, Mux(isInf(x), PosInf, approx))
    )
  }

  private def log2Bits(x: UInt): UInt = {
    Mux(
      isNan(x) || x(31),
      QNaN,
      Mux(isZero(x), NegInf, Mux(isInf(x), PosInf, q16ToF32(log2Q16(x))))
    )
  }

  private def exp2Bits(x: UInt): UInt = {
    Mux(
      isNan(x),
      QNaN,
      Mux(isInf(x) && !x(31), PosInf, Mux(isInf(x) && x(31), 0.U(32.W), exp2Q16(f32ToQ16(x))))
    )
  }
  // https://www.desmos.com/calculator/7s42bykmor -- parabolic estimation of sin functions
  private def sinBits(x: UInt): UInt = Mux(isNan(x) || isInf(x), QNaN, q16ToF32(sinQ16(f32ToQ16(x))))
  private def cosBits(x: UInt): UInt = Mux(isNan(x) || isInf(x), QNaN, q16ToF32(sinQ16(f32ToQ16(x) + HalfPiQ)))

  private def tanBits(x: UInt): UInt = {
    val q = f32ToQ16(x)
    Mux(isNan(x) || isInf(x), QNaN, q16ToF32(divQ16(sinQ16(q), sinQ16(q + HalfPiQ))))
  }

  private def atanBits(x: UInt): UInt = {
    Mux(
      isNan(x),
      QNaN,
      Mux(isInf(x), q16ToF32(Mux(x(31), -HalfPiQ, HalfPiQ)), q16ToF32(atanQ16(f32ToQ16(x))))
    )
  }

  // https://ieeexplore.ieee.org/document/1628884 - arctan approx
  private def atan2Bits(y: UInt, x: UInt): UInt = {
    val yq    = f32ToQ16(y)
    val xq    = f32ToQ16(x)
    val ratio = divQ16(yq, xq)
    val base  = atanQ16(ratio)
    val out = Mux(
      xq > 0.S,
      base,
      Mux(
        xq < 0.S && yq >= 0.S,
        base + PiQ,
        Mux(xq < 0.S && yq < 0.S, base - PiQ, Mux(yq > 0.S, HalfPiQ, Mux(yq < 0.S, -HalfPiQ, 0.S(32.W))))
      )
    )

    Mux(isNan(y) || isNan(x), QNaN, q16ToF32(out))
  }

  private def powBits(a: UInt, b: UInt): UInt = {
    val prod = mulQ16(f32ToQ16(b), log2Q16(a))
    Mux(isNan(a) || isNan(b) || a(31) || isZero(a), QNaN, exp2Q16(prod))
  }
  // TODO: rewrite the pipeline
  private def laneResult(a: UInt, b: UInt, funct: UInt): UInt = {
    val out = WireDefault(0.U(32.W))

    switch(funct) {
      is(SfuFn.RCP)   { out := rcpBits(a) }
      is(SfuFn.RSQRT) { out := rsqrtBits(a) }
      is(SfuFn.SQRT)  { out := sqrtBits(a) }
      is(SfuFn.EXP2)  { out := exp2Bits(a) }
      is(SfuFn.LOG2)  { out := log2Bits(a) }
      is(SfuFn.SIN)   { out := sinBits(a) }
      is(SfuFn.COS)   { out := cosBits(a) }
      is(SfuFn.TAN)   { out := tanBits(a) }
      is(SfuFn.ATAN)  { out := atanBits(a) }
      is(SfuFn.ATAN2) { out := atan2Bits(a, b) }
      is(SfuFn.POW)   { out := powBits(a, b) }
    }

    out
  }

  val comb = Wire(Vec(lanes, UInt(32.W))) // per lane results
  for (i <- 0 until lanes) {
    comb(i) := Mux(io.req.bits.pred(i), laneResult(io.req.bits.a(i), io.req.bits.b(i), io.req.bits.funct11), 0.U)
  }

  // length-latency shift reg
  val validPipe = RegInit(VecInit(Seq.fill(latency)(false.B)))
  val bitsPipe  = Reg(Vec(latency, Vec(lanes, UInt(32.W))))
  
  // equal 4 cycle pipeline
  validPipe(0) := io.req.valid
  bitsPipe(0)  := comb
  for (stage <- 1 until latency) {
    validPipe(stage) := validPipe(stage - 1)
    bitsPipe(stage)  := bitsPipe(stage - 1)
  }

  io.vd.valid := validPipe(latency - 1)
  io.vd.bits  := bitsPipe(latency - 1)
}
