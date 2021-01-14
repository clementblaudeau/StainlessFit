package fit
package core
package codegen.llvm

import core.util.RunContext

import core.codegen.llvm.IR._
import core.codegen.utils._

import scala.language.implicitConversions
import scala.collection.mutable.ArrayBuffer

case class Module(name: String, main: Function, lambdas: List[Function]) {
  import java.io.{File, FileWriter}

  def printToFile(filename: String)(implicit rc: RunContext) = {
    val writer = new FileWriter(new File(filename))
    writer.write(ModulePrinter.run(this))
    writer.flush()
  }
}
