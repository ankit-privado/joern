package io.joern.gosrc2cpg.astcreation

import io.joern.gosrc2cpg.parser.ParserAst.*
import io.joern.gosrc2cpg.parser.{ParserKeys, ParserNodeInfo}
import io.joern.gosrc2cpg.utils.Operator
import io.joern.x2cpg.utils.NodeBuilders.*
import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.nodes.{NewCall, NewFieldIdentifier}
import io.shiftleft.codepropertygraph.generated.{DispatchTypes, Operators}

import scala.collection.immutable.Seq

trait AstForExpressionCreator(implicit withSchemaValidation: ValidationMode) { this: AstCreator =>
  def astsForExpression(expr: ParserNodeInfo): Seq[Ast] = {
    expr.node match {
      case BinaryExpr     => astForBinaryExpr(expr)
      case StarExpr       => astForStarExpr(expr)
      case UnaryExpr      => astForUnaryExpr(expr)
      case ParenExpr      => astsForExpression(createParserNodeInfo(expr.json(ParserKeys.X)))
      case StructType     => astForStructType(expr)
      case TypeAssertExpr => astForNode(expr.json(ParserKeys.X))
      case CallExpr       => astForCallExpression(expr)
      case SelectorExpr   => astForFieldAccess(expr)
      case _              => Seq(Ast())
    }
  }

  private def astForFieldAccess(info: ParserNodeInfo): Seq[Ast] = {
    // TODO: Need to identify a way to find the typeFullName of the field getting accessed
    val fieldTypeFullName = Defines.anyTypeName
    val callNode =
      newOperatorCallNode(Operators.fieldAccess, info.code, Some(fieldTypeFullName), line(info), column(info))
    val identifierAsts  = astForNode(info.json(ParserKeys.X))
    val fieldIdentifier = info.json(ParserKeys.Sel)(ParserKeys.Name).str
    val fieldIdentifierNode = NewFieldIdentifier()
      .canonicalName(fieldIdentifier)
      .lineNumber(line(info))
      .columnNumber(column(info))
      .code(fieldIdentifier)
    val fieldIdAst = Ast(fieldIdentifierNode)
    Seq(callAst(callNode, identifierAsts ++ Seq(fieldIdAst)))
  }

  private def astForBinaryExpr(binaryExpr: ParserNodeInfo): Seq[Ast] = {
    val op = binaryExpr.json(ParserKeys.Op).value match {
      case "*"  => Operators.multiplication
      case "/"  => Operators.division
      case "%"  => Operators.modulo
      case "+"  => Operators.addition
      case "-"  => Operators.subtraction
      case "<<" => Operators.shiftLeft
      case ">>" => Operators.arithmeticShiftRight
      case "<"  => Operators.lessThan
      case ">"  => Operators.greaterThan
      case "<=" => Operators.lessEqualsThan
      case ">=" => Operators.greaterEqualsThan
      case "&"  => Operators.and
      case "^"  => Operators.xor
      case "|"  => Operators.or
      case "&&" => Operators.logicalAnd
      case "||" => Operators.logicalOr
      case "==" => Operators.equals
      case "!=" => Operators.notEquals
      case _    => Operator.unknown
    }
    val cNode     = createCallNodeForOperator(binaryExpr, op)
    val arguments = astForNode(binaryExpr.json(ParserKeys.X)) ++: astForNode(binaryExpr.json(ParserKeys.Y))
    Seq(callAst(cNode, arguments))
  }

  private def astForStarExpr(starExpr: ParserNodeInfo): Seq[Ast] = {
    val cNode   = createCallNodeForOperator(starExpr, Operators.indirection)
    val operand = astForNode(starExpr.json(ParserKeys.X))
    Seq(callAst(cNode, operand))
  }

  private def astForUnaryExpr(unaryExpr: ParserNodeInfo): Seq[Ast] = {
    val operatorMethod = unaryExpr.json(ParserKeys.Op).value match {
      case "+"     => Operators.plus
      case "-"     => Operators.minus
      case "*"     => Operators.indirection
      case "&"     => Operators.addressOf
      case "!"     => Operators.logicalNot
      case "range" => Operators.range
      case _       => Operator.unknown
    }

    val cNode   = createCallNodeForOperator(unaryExpr, operatorMethod)
    val operand = astForNode(unaryExpr.json(ParserKeys.X))
    Seq(callAst(cNode, operand))
  }

  private def createCallNodeForOperator(
    node: ParserNodeInfo,
    operatorMethod: String,
    DispatchType: String = DispatchTypes.STATIC_DISPATCH
  ): NewCall = {
    callNode(node, node.code, operatorMethod, operatorMethod, DispatchType)
  }
}
