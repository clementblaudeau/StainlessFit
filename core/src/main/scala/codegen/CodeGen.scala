package stainlessfit
package core
package codegen

import trees._
import util.RunContext
import extraction._
import codegen.llvm.IR.{And => IRAnd, Or => IROr, Not => IRNot, Neq => IRNeq,
  Eq => IREq, Lt => IRLt, Gt => IRGt, Leq => IRLeq, Geq => IRGeq, Nop => IRNop,
  Plus => IRPlus, Minus => IRMinus, Mul => IRMul, Div => IRDiv,
  BooleanLiteral => IRBooleanLiteral, UnitLiteral => IRUnitLiteral,
  NatType => IRNatType, UnitType => IRUnitType, _}

import codegen.llvm._
import codegen.utils.{Identifier => _, _}

// General stuff
import org.bytedeco.javacpp._;

// Headers required by LLVM
import org.bytedeco.llvm.LLVM._;
import org.bytedeco.llvm.global.LLVM._;

class CodeGen(implicit val rc: RunContext) extends Phase[Module] {
  def transform(t: Tree): (Tree, Module) = (t, CodeGen.genLLVM(t, true))
}

object CodeGen {
    def genLLVM(tree: Tree, isMain: Boolean)(implicit rc: RunContext): Module = {

        def cgDefFunction(defFun: DefFunction)(implicit rc: RunContext): Function = {
          val DefFunction(args, returnType, _, bind, _) = defFun

          val params = args.map{
            case TypedArgument(id, tpe) => ParamDef(translateType(tpe), new Local(id.toString))
          }

          val (fname, body) = extractBody(bind)

          cgFunction(new Global(fname.toString), params.toList, body)
        }

        def cgFunction(name: Global, params: List[ParamDef], body: Tree): Function = {
          val lh = new LocalHandler(rc)
          val function = Function(resultType(body), name, params)

          val initBlock = lh.newBlock("entry")

          val end = lh.freshLabel("End")
          val result = lh.freshLocal("result")

          val (entryBlock, phi) = codegen(body, initBlock, Some(end), Some(result))(lh, function)
          function.add(entryBlock)

          val endBlock = lh.newBlock(end)
          function.add(endBlock <:> phi <:> Return(Value(result), resultType(tree)))
          function
        }

        def extractBody(bind: Tree): (Identifier, Tree) = bind match {
          case Bind(id, rec: Bind) => extractBody(rec)
          case Bind(id, body) => (id, body)
          case _ => rc.reporter.fatalError(s"Couldn't find the body in $bind")
        }

        def extractDefFun(t: Tree): (List[DefFunction], Tree) = t match {
          case defFun @ DefFunction(_, _, _, _, otherDefs: DefFunction) => {
            val (defs, rest) = extractDefFun(otherDefs)
            (defFun +: defs, rest)
          }
          case DefFunction(_, _, _, _, rest) => (Nil, rest)
          case _ => rc.reporter.fatalError(s"Couldn't find the body in $t")
        }

        def cgModule(inputTree: Tree)(implicit rc: RunContext): Module = {
          val lh = new LocalHandler(rc)

          val (functions, bind) = extractDefFun(inputTree)
          val (id, body) = extractBody(bind)

          val main = cgFunction(new Global("main"), Nil, body)  //Might be a function call

          val module = Module(
            rc.config.file.getName(),
            main,
            functions map cgDefFunction
          )

          module
          // val initBlock = lh.newBlock("entry")
          //
          // val end = lh.freshLabel("End")
          // val result = lh.freshLocal("result")
          //
          // val (entryBlock, phi) = codegen(tree, initBlock, Some(end), Some(result))(lh, module)
          // module.add(entryBlock)
          //
          // val endBlock = lh.newBlock(end)
          // module.add(endBlock <:> phi <:> Return(Value(result), resultType(tree)))
          // module
        }

        def filterErasable(t: Tree): Tree = t match {
          case LetIn(_, _, _) |
            MacroTypeDecl(_, _) |
            MacroTypeInst(_, _) |
            ErasableApp(_, _) |
            Refl(_, _) |
            Fold(_, _) |
            Unfold(_, _) |
            UnfoldPositive(_, _) |
            DefFunction(_, _, _, _, _) |
            ErasableLambda(_, _) |
            Abs(_) |
            TypeApp(_, _) |
            Because(_, _) => rc.reporter.fatalError(s"This tree should have been erased: $t")

          case _ => t
        }

        def translateOp(op: Operator): Op = op match {
          case Not => IRNot
          case And => IRAnd
          case Or => IROr
          case Neq => IRNeq
          case Eq => IREq
          case Lt => IRLt
          case Gt => IRGt
          case Leq => IRLeq
          case Geq => IRGeq
          case Nop => IRNop
          case Plus => IRPlus
          case Minus => IRMinus
          case Mul => IRMul
          case Div => IRDiv

          case _ => rc.reporter.fatalError("Not yet implemented")
        }

        def cgLiteral(t: Tree): Literal = t match {
          case BooleanLiteral(b) => IRBooleanLiteral(b)
          case NatLiteral(n) => Nat(n)
          case UnitLiteral => Nat(0)
          case _ => rc.reporter.fatalError(s"This tree isn't a literal: $t")
        }

        def flattenArgs(t: Tree): List[Tree] = t match {
          case Primitive(op, args) => args.flatMap{
            case Primitive(op2, args2) if op2 == op => flattenArgs(Primitive(op2, args2))
            case other => List(other)
          }

          case _ => rc.reporter.fatalError(s"flatten is not defined for $t")
        }

        def translateType(tpe: Tree) = tpe match {
          case BoolType => BooleanType
          case NatType => IRNatType
          case UnitType => IRUnitType
          case _ => rc.reporter.fatalError(s"Unkown type $tpe")
        }

        def resultType(t: Tree): Type = t match {
          case BooleanLiteral(_) => BooleanType
          case NatLiteral(_) => IRNatType
          case UnitLiteral => IRUnitType

          case Primitive(op, _) => translateOp(op).returnType
          case IfThenElse(_, thenn, _) => resultType(thenn)
          case _ => rc.reporter.fatalError(s"Result type not yet implemented for $t")
        }

        def codegen(inputTree: Tree, block: Block, next: Option[Label], toAssign: Option[Local])
          (implicit lh: LocalHandler, f: Function): (Block, List[Instruction]) =
          filterErasable(inputTree) match {
            case IfThenElse(cond, thenn, elze) => {

              val condLocal = lh.freshLocal()

              val trueLocal = lh.freshLocal()
              val tBlock = lh.newBlock("then")

              val falseLocal = lh.freshLocal()
              val fBlock = lh.newBlock("else")

              val afterLocal = lh.freshLocal()
              val afterBlock = lh.newBlock("after")

              val (condPrep, condPhi) = codegen(cond, block, None, Some(condLocal))

              val (trueBlock, truePhi) = codegen(thenn, tBlock, Some(afterBlock.label), Some(trueLocal))
              f.add(trueBlock)

              val (falseBlock, falsePhi) = codegen(elze, fBlock, Some(afterBlock.label), Some(falseLocal))
              f.add(falseBlock)

              val parentBlock =
                condPrep <:>
                condPhi <:>
                Branch(Value(condLocal), tBlock.label, fBlock.label)

              f.add(parentBlock)

              val nextPhi =
                truePhi ++
                falsePhi ++
                toAssign.toList.map{
                  case local => Phi(local, resultType(thenn), List((trueLocal, trueBlock.label), (falseLocal, falseBlock.label)))
                }

              (afterBlock <:> nextPhi <:> Jump(next.get), Nil)
            }

            case BooleanLiteral(b) => {
              val assign = toAssign.toList.map(local => Assign(local, BooleanType, Value(IRBooleanLiteral(b))))
              val jump = next.toList.map(label => Jump(label))

              if(toAssign.isEmpty && jump.isEmpty) rc.reporter.fatalError("Unexpected control flow during codegen")

              (block <:> assign <:> jump, Nil)
            }

            case NatLiteral(n) => {
              val assign = toAssign.toList.map(local => Assign(local, IRNatType, Value(Nat(n))))
              val jump = next.toList.map(label => Jump(label))

              if(toAssign.isEmpty && jump.isEmpty) rc.reporter.fatalError("Unexpected control flow during codegen")

              (block <:> assign <:> jump, Nil)
            }

            case Primitive(op, args) => {

              val flatArgs = flattenArgs(inputTree)

              val argLocals = flatArgs.map{
                case BooleanLiteral(_) | NatLiteral(_) | UnitLiteral => None  //Todo replace by isLiteral
                case arg => Some(lh.freshLocal())
              }

              val init: (Block, List[Value]) = (block, Nil)

              val (cB, valueList: List[Value]) = argLocals.zip(flatArgs).foldLeft(init) {
                case ((currentBlock, values), (None, arg)) => {
                  (currentBlock, values :+ Value(cgLiteral(arg)))
                }

                case ((currentBlock, values), (Some(local), arg)) => {
                  //TODO check if an intermediate block is necessary
                  val tempLabel = lh.freshLabel("tempBlock")
                  val tempBlock = lh.newBlock(tempLabel)

                  val afterLabel = lh.freshLabel("afterBlock")
                  val afterBlock = lh.newBlock(afterLabel)

                  f.add(currentBlock <:> Jump(tempLabel)) //Todo can I do this?

                  val (otherBlock, phi) = codegen(arg, tempBlock, Some(afterLabel), Some(local))
                  f.add(otherBlock)
                  (afterBlock <:> phi, values :+ Value(local))
                }
              }

              val last = valueList.size - 1
              val (resultBlock, result) = valueList.zipWithIndex.tail.foldLeft((cB, valueList.head)){
                case ((cBlock, lhs), (rhs, index)) => {
                  val temp = if(index == last && toAssign.isDefined){
                    toAssign.get
                  } else {
                    lh.freshLocal("temp")
                  }
                  (cBlock <:> BinaryOp(translateOp(op), temp, lhs, rhs), Value(temp))
                }
              }

              val jump = next.toList.map(label => Jump(label))
              (resultBlock <:> jump, Nil)
              // val assign = if(toAssign.isDefined){
              //   //List(Assign(toAssign.get, result))
              // } else {
              //   Nil
              // }
            }

            // case DefFunction(args, optRet, _, body, rest) => {
            //   if(optFun.isDefined){
            //
            //   } else {
            //
            //   }
            // }
            case _ => rc.reporter.fatalError(s"codegen not implemented for $inputTree")
          }

        cgModule(tree)
    }
}
