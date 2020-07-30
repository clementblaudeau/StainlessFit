package stainlessfit
package core

import core.trees._
import core.interpreter._
import core.typechecker._
import core.typechecker.Derivation._

import parser.FitParser
import parser.FitLexer

import extraction._

import java.io.File

import core.util.RunContext

import scala.sys.process._

object Core {

  def parseString(s: String)(implicit rc: RunContext): Either[String, Tree] = {
    val it = s.iterator

    rc.parser(FitLexer(it)) match {
      case rc.parser.LL1.Parsed(value, _) =>
        Right(value)
      case rc.parser.LL1.UnexpectedEnd(rest) =>
        Left(
          s"""|Error during parsing, unexpected end of input.
              |Expected token: ${rest.first.mkString("   ")}""".stripMargin
        )
      case rc.parser.LL1.UnexpectedToken(t, rest) =>
        Left(
          s"""|Error during parsing, unexpected token at position ${t.pos}: $t
              |Expected token: ${rest.first.mkString("   ")}""".stripMargin
        )
    }
  }

  def parseFile(f: File)(implicit rc: RunContext): Either[String, Tree] = {
    val s = scala.io.Source.fromFile(f).getLines.mkString("\n")
    val regex = """Include\("(.*)"\)""".r
    val completeString = regex.replaceAllIn(s, m =>
      scala.io.Source.fromFile(new File(f.getAbsoluteFile().getParentFile().getCanonicalPath(), m.group(1))).getLines.mkString("\n") + "\n"
    )
    rc.bench.time("Scallion parsing") { parseString(completeString) }
  }

  def evalFile(f: File)(implicit rc: RunContext): Either[String, Tree] =
    parseFile(f) flatMap { src =>

      val pipeline =
        DebugPhase(new DefFunctionElimination(), "DefFunctionElimination") andThen
        DebugPhase(new Namer(), "Namer") andThen
        DebugPhase(new BuiltInIdentifiers(), "BuiltInIdentifiers") andThen
        DebugPhase(new Erasure(), "Erasure")

      val (t, _) = pipeline.transform(src)

      rc.bench.time("Evaluation") { Interpreter.evaluate(t) } match {
        case Error(error, _) => Left(error)
        case v => Right(v)
      }
    }

  def hasZ3(): Boolean = {
    try {
      "z3 --version".!(ProcessLogger(_ => ()))
      true
    } catch {
      case _: Throwable => false
    }
  }

  def typeCheckFile(f: File)(implicit rc: RunContext): Either[String, (Boolean, NodeTree[Judgment])] = {
    if (hasZ3()) {
      parseFile(f) flatMap { src =>

        val pipeline =
          DebugPhase(new DefFunctionElimination(), "DefFunctionElimination") andThen
          DebugPhase(new FixIndexing(), "FixIndexing") andThen
          DebugPhase(new Namer(), "Namer") andThen
          DebugPhase(new BuiltInIdentifiers(), "BuiltInIdentifiers")

        val (t, _) = pipeline.transform(src)

        rc.bench.time("Type Checking") { new TypeChecker().infer(t) } match {
          case None => Left(s"Could not typecheck: $f")
          case Some((success, tree)) =>
            if (rc.config.html)
              rc.bench.time("makeHTMLFile") {
                util.HTMLOutput.makeHTMLFile(f, List(tree), success)
              }

            Right((success, tree))
        }
      }
    } else {
      Left("The z3 solver is required for the typecheck command")
    }
  }

  def sDepFile(f: File)(implicit rc: RunContext): Either[String, (Boolean, NodeTree[Judgment])] = {
    parseFile(f) flatMap { src =>

      val pipeline =
        DebugPhase(new DefFunctionElimination(), "DefFunctionElimination") andThen
        DebugPhase(new FixIndexing(), "FixIndexing") andThen
        DebugPhase(new Namer(), "Namer") andThen
        DebugPhase(new BuiltInIdentifiers(), "BuiltInIdentifiers") andThen
        DebugPhase(new ChooseEncoding(), "ChooseEncoding")

      val (t, _) = pipeline.transform(src)

      rc.bench.time("SDep") { new SDep().infer(t) } match {
        case None => Left(s"Could not typecheck: $f")
        case Some((success, tree)) =>
          if (rc.config.html)
            rc.bench.time("makeHTMLFile") {
              util.HTMLOutput.makeHTMLFile(f, List(tree), success)
            }
          if (rc.config.coq)
            rc.bench.time("makeCoqFile") {
              util.CoqOutput.makeCoqFile(f, List(tree), success)
            }


          Right((success, tree))
      }
    }
  }

}
