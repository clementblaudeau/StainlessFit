package parser

import scallion.input._
import scallion.lexing._
import scallion.parsing._

import trees._
import interpreter._

import scallion.parsing.visualization.Graphs
import scallion.parsing.visualization.Grammars

import stainless.annotation._
import stainless.collection._

sealed abstract class Token {
  val range: (Int, Int)
}


case class AssignationToken(range: (Int, Int)) extends Token

case class KeyWordToken(value: String, range: (Int, Int)) extends Token
case class SeparatorToken(value: String, range: (Int, Int)) extends Token
case class BooleanToken(value: Boolean, range: (Int, Int)) extends Token
case class NumberToken(value: BigInt, range: (Int, Int)) extends Token
case class StringToken(value: String, range: (Int, Int)) extends Token
case class NullToken(range: (Int, Int)) extends Token
case class SpaceToken(range: (Int, Int)) extends Token
case class UnknownToken(content: String, range: (Int, Int)) extends Token

case class UnitToken(range: (Int, Int)) extends Token

case class VarToken(content: String, range: (Int, Int)) extends Token

case class OperatorToken(op: Operator, range: (Int, Int)) extends Token
case class TypeToken(content: String, range: (Int, Int)) extends Token

/*sealed abstract class Value {
  val range: (Int, Int)
}

case class BooleanValue(value: Boolean, range: (Int, Int)) extends Value
case class NumberValue(value: BigInt, range: (Int, Int)) extends Value
case class StringValue(value: String, range: (Int, Int)) extends Value
case class NullValue(range: (Int, Int)) extends Value
*/
object ScalaLexer extends Lexers[Token, Char, Int] with CharRegExps {

  private def stringToOperator(str: String): Operator = str match {
    case "!" => Not
    case "&&" => And
    case "||" => Or
    case "+" => Plus
    case "-" => Minus
    case "*" => Mul
    case "/" => Div
    case "==" => Eq
    case "!=" => Neq
    case "<=" => Lteq
    case ">=" => Gteq
    case "<" => Lt
    case ">" => Gt
    case _ => Nop
  }

  val lexer = Lexer(
    // Operators
    oneOf("-+/*!<>") | word("&&") |  word("||") |
    word("==") | word("!=") |word("<=") | word(">=")
    |> { (cs, r) => OperatorToken(stringToOperator(cs.mkString), r) },

    //Assignation
    oneOf("=")
      |> { (cs, r) => AssignationToken(r) },

    /*word("")
      |> { (cs, r) => UnitToken(r) },*/


    // Separator
    oneOf("{},().:[]") | word("=>")
      |> { (cs, r) => SeparatorToken(cs.mkString, r) },

    // Space
    many1(whiteSpace)
      |> { (_, r) => SpaceToken(r) },

    // Booleans
    word("true")
      |> { (_, r) => BooleanToken(true, r) },
    word("false")
      |> { (_, r) => BooleanToken(false, r) },

      // Keywords
    word("if") | word("else") | word("case") | word("in") | word("match") |
    word("fix") | word("fun") | word("Right") | word("Left") | word("val") |
    word("def")
      |> { (cs, r) => KeyWordToken(cs.mkString, r) },

    word("Bool") | word("Unit") | word("Int")
      |>  { (cs, r) => TypeToken(cs.mkString, r) },

    // Var
    elem(c => c.isLetter) ~
    many { elem(c => c.isLetterOrDigit || c == '_' || c == '$') }
      |> { (cs, r) => VarToken(cs.mkString, r) },

    // Numbers
    elem('0') | (nonZero ~ many(digit))
      |> { (cs, r) => NumberToken(cs.mkString.toInt, r) }

  ) onError {
    (cs, r) => UnknownToken(cs.mkString, r)
  }

  def apply(it: Iterator[Char]): Iterator[Token] = {
    val source = Source.fromIterator(it, IndexPositioner)
    val tokens = lexer.spawn(source)
    tokens.filter(!_.isInstanceOf[SpaceToken])
  }
}

sealed abstract class TokenClass(repr: String) {
  override def toString = repr
}

case object VarClass extends TokenClass("<var>")
case class OperatorClass(op: Operator) extends TokenClass(s"<operator>")
case class SeparatorClass(value: String) extends TokenClass(value)
case object BooleanClass extends TokenClass("<boolean>")
case object NumberClass extends TokenClass("<number>")
case object StringClass extends TokenClass("<string>")
case object NoClass extends TokenClass("<error>")
case object UnitClass extends TokenClass("<unit>")
case object AssignationClass extends TokenClass("<assignation>")
case class KeyWordClass(value: String) extends TokenClass(value)
case class TypeClass(value: String) extends TokenClass("<type>")

object ScalaParser extends Parsers[Token, TokenClass]
    with Graphs[TokenClass] with Grammars[TokenClass]
    with Operators {


  def appearFreeIn(v: Var, e: Tree): Boolean = {
    e match {
      case yvar: Var if e == v => true
      case IfThenElse(cond, t1, t2) =>
        appearFreeIn(v, t1) || appearFreeIn(v, t2) ||
        appearFreeIn(v, cond)
      case App(t1, t2) => appearFreeIn(v, t1) || appearFreeIn(v, t2)
      case Pair(t1, t2) => appearFreeIn(v, t1) || appearFreeIn(v, t2)
      case First(t) => appearFreeIn(v, t)
      case Second(t) => appearFreeIn(v, t)
      case LeftTree(t) => appearFreeIn(v, t)
      case RightTree(t) => appearFreeIn(v, t)
      case Bind(stainless.lang.Some(yvar), e) if (v == yvar) => false
      case Bind(_, t) => appearFreeIn(v, t)

      case Lambda(tp, bind) => appearFreeIn(v, bind)
      case Fix(tp, Bind(n, bind)) => appearFreeIn(v, bind)
      case LetIn(tp, v1, bind) => appearFreeIn(v, bind)
      case Match(t, t0, bind) =>
        appearFreeIn(v, t) || appearFreeIn(v, t0) ||
        appearFreeIn(v, bind)
      case EitherMatch(t, bind1, bind2) =>
        appearFreeIn(v, bind1) || appearFreeIn(v, bind2) ||
        appearFreeIn(v, t)
      case Primitive(op, args) =>
        args.exists(appearFreeIn(v, _))
      case _ => false
    }
  }

  override def getKind(token: Token): TokenClass = token match {
    case SeparatorToken(value, range) => SeparatorClass(value)
    case BooleanToken(value, range) => BooleanClass
    case NumberToken(value, range) => NumberClass
    case VarToken(content, range) => VarClass
    case OperatorToken(op, range) => OperatorClass(op)
    case AssignationToken(range) => AssignationClass
    case KeyWordToken(value, range) => KeyWordClass(value)
    case UnitToken(range) => UnitClass
    case TypeToken(content, range) => TypeClass(content)
    case _ => NoClass
  }

  val assignation = elem(AssignationClass)
  val lpar = elem(SeparatorClass("("))
  val rpar = elem(SeparatorClass(")"))
  val lbra = elem(SeparatorClass("{"))
  val rbra = elem(SeparatorClass("}"))
  val lsbra = elem(SeparatorClass("{"))
  val rsbra = elem(SeparatorClass("}"))
  val comma = elem(SeparatorClass(","))
  val colon = elem(SeparatorClass(":"))
  val appK = elem(SeparatorClass("\\"))
  val tupleK = elem(SeparatorClass("\\("))
  val dot = elem(SeparatorClass("."))
  val arrow = elem(SeparatorClass("=>"))
  val inK = elem(KeyWordClass("in"))
  val ifK = elem(KeyWordClass("if"))
  val elseK = elem(KeyWordClass("else"))
  val fixK = elem(KeyWordClass("fix"))
  val funK = elem(KeyWordClass("fun"))
  val rightK = elem(KeyWordClass("Right"))
  val leftK = elem(KeyWordClass("Left"))
  val matchK = elem(KeyWordClass("match"))
  val caseK = elem(KeyWordClass("case"))
  val valK = elem(KeyWordClass("val"))
  val defK = elem(KeyWordClass("def"))

  val natType = accept(TypeClass("Int")) { case _ => NatType }
  val boolType = accept(TypeClass("Bool")) { case _ => BoolType }
  val unitType = accept(TypeClass("Unit")) { case _ => UnitType }

  val literalType = natType | boolType | unitType

  lazy val basicType = literalType | parTypeExpr

  lazy val parTypeExpr: Parser[Tree] = {
    (lpar ~ rep1sep(typeExpr, comma) ~ rpar).map {
      case _ ~ l ~ _ =>
        if(l.size == 1) l.head
        else {
          val h = l.reverse.head
          val r = l.reverse.tail.reverse
          r.foldRight(h) { case (e, acc) => SigmaType(e, Bind(stainless.lang.None(), acc)) }
        }
    }
  }

  lazy val sumType = accept(OperatorClass(Plus)) {
    case s =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => SumType(x, y)
      f
  }

  lazy val piType = accept(SeparatorClass("=>")) {
    case s =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => SumType(x, y)
      f
  }

  lazy val operatorType: Parser[Tree] = {
    operators(basicType)(
      piType is RightAssociative,
      sumType is LeftAssociative
    )
  }


  lazy val typeExpr = recursive {
    operatorType
  }



  val boolean = accept(BooleanClass) { case BooleanToken(value, _) => BoolLiteral(value) }
  val number = accept(NumberClass) { case NumberToken(value, _) => NatLiteral(value) }
  val variable = accept(VarClass) { case VarToken(content, _) => Var(stainless.lang.None(), content) }
  val unit = accept(UnitClass) { case _ => UnitLiteral }
  val literal = variable | number | boolean | unit

  val plus = accept(OperatorClass(Plus)) {
    case s =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Plus, List(x, y))
      f
  }

  val minus = accept(OperatorClass(Minus)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Minus, List(x, y))
      f
  }

  val mul = accept(OperatorClass(Mul)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Mul, List(x, y))
      f
  }

  val div = accept(OperatorClass(Div)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Div, List(x, y))
      f
  }

  val and = accept(OperatorClass(And)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(And, List(x, y))
      f
  }

  val or = accept(OperatorClass(Or)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Or, List(x, y))
      f
  }

  val eq = accept(OperatorClass(Eq)) {
    case _ =>
      val f: (Tree, Tree) => Tree = (x: Tree, y: Tree) => Primitive(Eq, List(x, y))
      f
  }

  val not = accept(OperatorClass(Not)) {
    case _ => val f: Tree => Tree = (x: Tree) => Primitive(Not, List(x))
    f
  }


  lazy val function: Parser[Tree] = recursive {
    (funK ~ lpar ~ variable ~ colon ~ typeExpr ~ rpar ~ arrow ~ lbra ~ expression ~ rbra).map {
      case _ ~ _ ~ x ~ _ ~ tp ~ _ ~ _ ~ _ ~ e ~ _ => Lambda(stainless.lang.Some(tp), Bind(stainless.lang.Some(x), e))
    }
  }

  def buildFix(name: Var, body: Tree, bodyType: Tree) = {
    Fix(
      stainless.lang.None(),
      Bind(
        stainless.lang.None(),
        Bind(
          stainless.lang.Some(name),
          Interpreter.replace(name, App(name, UnitLiteral), body)
        )
      )
    )
  }

  def foldArgs(args: Seq[(Var, Tree)], body: Tree, bodyType: Tree): (Tree, Tree) = {
    args.foldRight((body, bodyType)) {
      case ((x, ty1), (acc, ty2))  =>
        val lType = PiType(ty1, Bind(stainless.lang.Some(x), ty2))
        (Lambda(stainless.lang.Some(ty1), Bind(stainless.lang.Some(x), acc)), lType)
    }
  }

  lazy val defFunction: Parser[Tree] = recursive {
    (defK ~ variable ~ lpar ~ repsep(variable ~ colon ~ typeExpr, comma) ~ rpar ~
    colon ~ typeExpr ~ assignation ~ lbra ~ expression ~ rbra ~ opt(expression)).map {
      case _ ~ f ~ _ ~ argsT ~ _ ~ _ ~ rt ~ _ ~ _ ~ e1 ~ _ ~ e2 =>
        val args = argsT.map { case (x ~ _ ~ t) => (x, t) }
        val (body, bType) = if(args.isEmpty) (e1, rt) else foldArgs(args, e1, rt)
        val funExpr = if(appearFreeIn(f, body)) buildFix(f, body, bType) else body
        e2 match {
          case None => LetIn(stainless.lang.None(), funExpr, Bind(stainless.lang.Some(f), f))
          case Some(e) => LetIn(stainless.lang.None(), funExpr, Bind(stainless.lang.Some(f), e))
        }
    }
  }

  lazy val fixpoint: Parser[Tree] = recursive {
    (fixK ~ opt(lsbra ~ expression ~ rsbra) ~ lpar ~ variable ~ arrow ~ expression ~ rpar).map {
      case _ ~ x ~ _ ~ e ~ _ => Fix(stainless.lang.None(), Bind(stainless.lang.None(), Bind(stainless.lang.Some(x), e)))
    }
  }

  lazy val letIn: Parser[Tree] = recursive {
    (valK ~ variable ~ assignation ~ expression ~ inK ~ expression).map {
      case _ ~ x ~ _ ~ e ~ _ ~ e2 => LetIn(stainless.lang.None(), e, Bind(stainless.lang.Some(x), e2))
    }
  }

  lazy val parExpr: Parser[Tree] = {
    (lpar ~ repsep(expression, comma) ~ rpar).map {
      case _ ~ l ~ _ =>
        if(l.isEmpty) UnitLiteral
        else if(l.size == 1) l.head
        else {
          val h = l.reverse.head
          val r = l.reverse.tail.reverse
          r.foldRight(h) { case (e, acc) => Pair(e, acc) }
        }
    }
  }

  lazy val application: Parser[Tree] = recursive {
    (simpleExpr ~ many(simpleExpr)).map {
      case f ~ args => args.reverse.foldRight(f) {case (e, acc) => App(acc, e) }
    }
  }

  lazy val eitherMatch: Parser[Tree] = recursive {
    (matchK ~ expression ~ lbra ~
    caseK ~ leftK ~ lpar ~ variable ~ rpar ~ arrow ~ expression ~
    caseK ~ rightK ~ lpar ~ variable ~ rpar ~ arrow ~ expression ~
    rbra).map {
      case (_ ~ e ~ _ ~ _ ~ _ ~ _ ~ v1 ~ _ ~ _ ~ e1 ~
      _ ~ _ ~ _ ~ v2 ~ _ ~ _ ~ e2 ~ _) =>
      EitherMatch(e, Bind(stainless.lang.Some(v1), e1), Bind(stainless.lang.Some(v2), e2))
    }
  }

  lazy val left: Parser[Tree] = recursive {
    (leftK ~ lpar ~ expression ~ rpar).map {
      case _ ~ _ ~ e ~ _ => LeftTree(e)
    }
  }

  lazy val right: Parser[Tree] = recursive {
    (rightK ~ lpar ~ expression ~ rpar).map {
      case  _ ~ _ ~ e ~ _ => RightTree(e)
    }
  }

  val operator: Parser[Tree] = {
    operators(prefixes(not, application))(
      mul | div | and is LeftAssociative,
      plus | minus | or is LeftAssociative,
      eq is LeftAssociative)
  }

  lazy val condition: Parser[Tree] = {
    (ifK ~ lpar ~ expression ~ rpar ~ lbra ~ expression ~ rbra ~ elseK ~ lbra ~ expression ~ rbra).map {
      case _ ~ _ ~ cond ~ _ ~ _ ~ vTrue ~ _ ~ _ ~ _ ~ vFalse ~ _ => IfThenElse(cond, vTrue, vFalse)
    }
  }

  lazy val simpleExpr: Parser[Tree] = literal | parExpr | fixpoint | function | left | right

  lazy val expression: Parser[Tree] = recursive {
    condition | eitherMatch | letIn | defFunction | operator
  }



  def apply(it: Iterator[Token]): ParseResult[Tree] = expression(it)
}