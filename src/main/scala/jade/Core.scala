package jade

import chisel3._
import chisel3.util._

class Core() extends Module {
  // Schedule N harts

  // Fetch instruction at PC (handle divergence with a stack of max N PCs)

  // Decode instruction, send control signals to harts
}
