package io.joern.rubysrc2cpg.astcreation
import io.joern.rubysrc2cpg.parser.RubyParser
import io.joern.rubysrc2cpg.parser.RubyParser.*
import io.joern.rubysrc2cpg.passes.Defines
import io.joern.rubysrc2cpg.utils.PackageContext
import io.joern.x2cpg.Ast.storeInDiffGraph
import io.joern.x2cpg.Defines.DynamicCallUnknownFullName
import io.joern.x2cpg.datastructures.{Global, Scope, ScopeElement}
import io.joern.x2cpg.{Ast, AstCreatorBase, AstNodeBuilder, ValidationMode, Defines as XDefines}
import io.shiftleft.codepropertygraph.generated.*
import io.shiftleft.codepropertygraph.generated.nodes.*
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.TerminalNode
import org.antlr.v4.runtime.{ParserRuleContext, Token}
import org.slf4j.LoggerFactory
import overflowdb.BatchedUpdate

import java.io.File as JFile
import scala.collection.immutable.Seq
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

class AstCreator(
  protected val filename: String,
  global: Global,
  parser: ResourceManagedParser,
  packageContext: PackageContext,
  projectRoot: Option[String] = None
)(implicit withSchemaValidation: ValidationMode)
    extends AstCreatorBase(filename)
    with AstNodeBuilder[ParserRuleContext, AstCreator]
    with AstForPrimitivesCreator
    with AstForStatementsCreator
    with AstForExpressionsCreator
    with AstForDeclarationsCreator
    with AstForTypesCreator
    with AstCreatorHelper
    with AstForHereDocsCreator {

  protected val scope: RubyScope = new RubyScope()

  private val logger = LoggerFactory.getLogger(this.getClass)

  protected val classStack = mutable.Stack[String]()

  protected val packageStack = mutable.Stack[String]()

  /*
   * Stack of variable identifiers incorrectly identified as method identifiers
   * Each AST contains exactly one call or identifier node
   */
  protected val methodNameAsIdentifierStack = mutable.Stack[Ast]()

  protected val methodAliases       = mutable.HashMap[String, String]()
  protected val methodNameToMethod  = mutable.HashMap[String, nodes.NewMethod]()
  protected val methodDefInArgument = mutable.ListBuffer[Ast]()

  protected val typeDeclNameToTypeDecl = mutable.HashMap[String, nodes.NewTypeDecl]()

  protected val methodNamesWithYield = mutable.HashSet[String]()

  /*
   *Fake methods created from yield blocks and their yield calls will have this suffix in their names
   */
  protected val YIELD_SUFFIX = "_yield"

  /*
   * This is used to mark call nodes created due to yield calls. This is set in their names at creation.
   * The appropriate name wrt the names of their actual methods is set later in them.
   */
  protected val UNRESOLVED_YIELD = "unresolved_yield"

  protected val pathSep = "."

  protected val blockMethods = ListBuffer[Ast]()

  protected val relativeFilename: String =
    projectRoot.map(filename.stripPrefix).map(_.stripPrefix(JFile.separator)).getOrElse(filename)

  // The below are for adding implicit return nodes to methods

  // This is true if the last statement of a method is being processed. The last statement could be a if-else as well
  protected var processingLastMethodStatement = false
  // a monotonically increasing block id unique within this file
  protected var blockIdCounter = 1
  // block id of the block currently being processed
  protected var currentBlockId = 0
  /*
   * This is a hash of parent block id ---> child block id. If there are multiple children, any one child can be present.
   * The value of this entry for a block is read AFTER its last statement has been processed. Absence of the the block
   * in this hash implies this is a leaf block.
   */
  protected val blockChildHash: mutable.Map[Int, Int] = mutable.HashMap[Int, Int]()

  protected val builtInCallNames = mutable.HashSet[String]()
  // Hashmap to store used variable names, to avoid duplicates in case of un-named variables
  protected val usedVariableNames = mutable.HashMap.empty[String, Int]

  protected def createIdentifierWithScope(
    ctx: ParserRuleContext,
    name: String,
    code: String,
    typeFullName: String,
    dynamicTypeHints: Seq[String] = Seq()
  ): NewIdentifier = {
    val newNode = identifierNode(ctx, name, code, typeFullName, dynamicTypeHints)
    scope.addToScope(name, newNode)
    newNode
  }

  protected def getActualMethodName(name: String): String = {
    methodAliases.getOrElse(name, name)
  }

  override def createAst(): BatchedUpdate.DiffGraphBuilder = {
    parser.parse(filename) match {
      case Success(programCtx) =>
        createAstForProgramCtx(programCtx)
      case Failure(exc) =>
        logger.warn(s"Could not parse file: $filename, skipping")
        logger.warn(exc.getMessage)
        diffGraph
    }
  }

  private def createAstForProgramCtx(programCtx: RubyParser.ProgramContext) = {
    val name     = ":program"
    val fullName = s"$relativeFilename:$name"
    val programMethod =
      methodNode(
        programCtx,
        name,
        name,
        fullName,
        None,
        relativeFilename,
        Option(NodeTypes.TYPE_DECL),
        Option(fullName)
      )

    classStack.push(fullName)

    scope.pushNewScope(programMethod)

    val statementAsts =
      if (
        programCtx.compoundStatement() != null &&
        programCtx.compoundStatement().statements() != null
      ) {
        astForStatements(programCtx.compoundStatement().statements(), false, false) ++ blockMethods
      } else {
        logger.error(s"File $filename has no compound statement. Needs to be examined")
        List[Ast](Ast())
      }

    val methodRetNode = methodReturnNode(programCtx, Defines.Any)

    // For all the builtIn's encountered create assignment ast, minus user-defined methods with the same name
    val lineColNum = 1
    val builtInMethodAst = builtInCallNames
      .filterNot(methodNameToMethod.contains)
      .map { builtInCallName =>
        val identifierNode = NewIdentifier()
          .code(builtInCallName)
          .name(builtInCallName)
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)
          .typeFullName(Defines.Any)
        scope.addToScope(builtInCallName, identifierNode)
        val typeRefNode = NewTypeRef()
          .code(prefixAsBuiltin(builtInCallName))
          .typeFullName(prefixAsBuiltin(builtInCallName))
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)
        astForAssignment(identifierNode, typeRefNode, Some(lineColNum), Some(lineColNum))
      }
      .toList

    val methodRefAssignmentAsts = methodNameToMethod.values
      .filterNot(_.astParentType == NodeTypes.TYPE_DECL)
      .map { methodNode =>
        // Create a methodRefNode and assign it to the identifier version of the method, which will help in type propagation to resolve calls
        val methodRefNode = NewMethodRef()
          .code("def " + methodNode.name + "(...)")
          .methodFullName(methodNode.fullName)
          .typeFullName(methodNode.fullName)
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)

        val methodNameIdentifier = NewIdentifier()
          .code(methodNode.name)
          .name(methodNode.name)
          .typeFullName(Defines.Any)
          .lineNumber(lineColNum)
          .columnNumber(lineColNum)
        scope.addToScope(methodNode.name, methodNameIdentifier)
        val methodRefAssignmentAst =
          astForAssignment(methodNameIdentifier, methodRefNode, methodNode.lineNumber, methodNode.columnNumber)
        methodRefAssignmentAst
      }
      .toList

    val typeRefAssignmentAst = typeDeclNameToTypeDecl.values.map { typeDeclNode =>

      val typeRefNode = NewTypeRef()
        .code("class " + typeDeclNode.name + "(...)")
        .typeFullName(typeDeclNode.fullName)
        .lineNumber(typeDeclNode.lineNumber)
        .columnNumber(typeDeclNode.columnNumber)

      val typeDeclNameIdentifier = NewIdentifier()
        .code(typeDeclNode.name)
        .name(typeDeclNode.name)
        .typeFullName(Defines.Any)
        .lineNumber(lineColNum)
        .columnNumber(lineColNum)
      scope.addToScope(typeDeclNode.name, typeDeclNameIdentifier)
      val typeRefAssignmentAst =
        astForAssignment(typeDeclNameIdentifier, typeRefNode, typeDeclNode.lineNumber, typeDeclNode.columnNumber)
      typeRefAssignmentAst
    }

    val methodDefInArgumentAsts = methodDefInArgument.toList
    val locals                  = scope.createAndLinkLocalNodes(diffGraph).map(Ast.apply)
    val programAst =
      methodAst(
        programMethod,
        Seq.empty[Ast],
        blockAst(
          blockNode(programCtx),
          locals ++ builtInMethodAst ++ methodRefAssignmentAsts ++ typeRefAssignmentAst ++ methodDefInArgumentAsts ++ statementAsts.toList
        ),
        methodRetNode
      )

    scope.popScope()

    val fileNode       = NewFile().name(relativeFilename).order(1)
    val namespaceBlock = globalNamespaceBlock()
    val ast            = Ast(fileNode).withChild(Ast(namespaceBlock).withChild(programAst))

    classStack.popAll()

    storeInDiffGraph(ast, diffGraph)
    diffGraph
  }

  object RubyOperators {
    val none                    = "<operator>.none"
    val patternMatch            = "<operator>.patternMatch"
    val notPatternMatch         = "<operator>.notPatternMatch"
    val scopeResolution         = "<operator>.scopeResolution"
    val defined                 = "<operator>.defined"
    val keyValueAssociation     = "<operator>.keyValueAssociation"
    val activeRecordAssociation = "<operator>.activeRecordAssociation"
    val undef                   = "<operator>.undef"
    val superKeyword            = "<operator>.super"
    val stringConcatenation     = "<operator>.stringConcatenation"
    val formattedString         = "<operator>.formatString"
    val formattedValue          = "<operator>.formatValue"
  }
  private def getOperatorName(token: Token): String = token.getType match {
    case ASSIGNMENT_OPERATOR => Operators.assignment
    case DOT2                => Operators.range
    case DOT3                => Operators.range
    case EMARK               => Operators.not
    case EQ                  => Operators.assignment
    case COLON2              => RubyOperators.scopeResolution
    case DOT                 => Operators.fieldAccess
    case EQGT                => RubyOperators.keyValueAssociation
    case COLON               => RubyOperators.activeRecordAssociation
    case _                   => RubyOperators.none
  }

  protected def line(ctx: ParserRuleContext): Option[Integer]      = Option(ctx.getStart.getLine)
  protected def column(ctx: ParserRuleContext): Option[Integer]    = Option(ctx.getStart.getCharPositionInLine)
  protected def lineEnd(ctx: ParserRuleContext): Option[Integer]   = Option(ctx.getStop.getLine)
  protected def columnEnd(ctx: ParserRuleContext): Option[Integer] = Option(ctx.getStop.getCharPositionInLine)
  protected def text(ctx: ParserRuleContext): String = {
    val a     = ctx.getStart.getStartIndex
    val b     = ctx.getStop.getStopIndex
    val intv  = new Interval(a, b)
    val input = ctx.getStart.getInputStream
    input.getText(intv)
  }

  def astForSingleLeftHandSideContext(ctx: SingleLeftHandSideContext): Seq[Ast] = ctx match {
    case ctx: VariableIdentifierOnlySingleLeftHandSideContext =>
      Seq(astForVariableIdentifierHelper(ctx.variableIdentifier(), true))
    case ctx: PrimaryInsideBracketsSingleLeftHandSideContext =>
      val primaryAsts = astForPrimaryContext(ctx.primary())
      val argsAsts    = astForArguments(ctx.arguments())
      val callNode = NewCall()
        .name(Operators.indexAccess)
        .code(text(ctx))
        .methodFullName(Operators.indexAccess)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(ctx.LBRACK().getSymbol.getLine)
        .columnNumber(ctx.LBRACK().getSymbol.getCharPositionInLine())
      Seq(callAst(callNode, primaryAsts ++ argsAsts))
    case ctx: XdotySingleLeftHandSideContext =>
      // TODO handle obj.foo=arg being interpreted as obj.foo(arg) here.
      val xAsts = astForPrimaryContext(ctx.primary())
      val localVar = {
        if (ctx.LOCAL_VARIABLE_IDENTIFIER() != null) {
          ctx.LOCAL_VARIABLE_IDENTIFIER()
        } else if (ctx.CONSTANT_IDENTIFIER() != null) {
          ctx.CONSTANT_IDENTIFIER()
        } else {
          null
        }
      }
      val varSymbol = localVar.getSymbol
      val node =
        createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))
      val yAst = Ast(node)

      val callNode = NewCall()
        .name(Operators.fieldAccess)
        .code(Operators.fieldAccess)
        .methodFullName(Operators.fieldAccess)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(localVar.getSymbol.getLine)
        .columnNumber(localVar.getSymbol.getCharPositionInLine)
      Seq(callAst(callNode, xAsts ++ Seq(yAst)))
    case ctx: ScopedConstantAccessSingleLeftHandSideContext =>
      val localVar  = ctx.CONSTANT_IDENTIFIER()
      val varSymbol = localVar.getSymbol
      val node = createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))
      Seq(Ast(node))
    case _ =>
      logger.error(s"astForSingleLeftHandSideContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())

  }

  def astForMultipleRightHandSideContext(ctx: MultipleRightHandSideContext): Seq[Ast] = {
    if (ctx == null) return Seq(Ast())

    val expCmd = ctx.expressionOrCommands()
    val exprAsts = Option(expCmd) match
      case Some(expCmd) =>
        expCmd.expressionOrCommand().asScala.flatMap(astForExpressionOrCommand).toSeq
      case None =>
        Seq()

    val paramAsts = if (ctx.splattingArgument() != null) {
      val splattingAsts = astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
      exprAsts ++ splattingAsts
    } else {
      exprAsts
    }

    paramAsts
  }

  def astForSingleAssignmentExpressionContext(ctx: SingleAssignmentExpressionContext): Seq[Ast] = {
    val rightAst = astForMultipleRightHandSideContext(ctx.multipleRightHandSide())
    val leftAst  = astForSingleLeftHandSideContext(ctx.singleLeftHandSide())

    val operatorName = getOperatorName(ctx.op)

    if (leftAst.size == 1 && rightAst.size > 1) {
      /*
       * This is multiple RHS packed into a single LHS. That is, packing left hand side.
       * This is as good as multiple RHS packed into an array and put into a single LHS
       */
      val callNode = NewCall()
        .name(operatorName)
        .code(text(ctx))
        .methodFullName(operatorName)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(ctx.op.getLine)
        .columnNumber(ctx.op.getCharPositionInLine)

      val packedRHS = getPackedRHS(rightAst, wrapInBrackets = true)
      Seq(callAst(callNode, leftAst ++ packedRHS))
    } else {
      val callNode = NewCall()
        .name(operatorName)
        .code(text(ctx))
        .methodFullName(operatorName)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(ctx.op.getLine)
        .columnNumber(ctx.op.getCharPositionInLine)
      Seq(callAst(callNode, leftAst ++ rightAst))
    }
  }

  def astForStringInterpolationContext(ctx: InterpolatedStringExpressionContext): Seq[Ast] = {
    val varAsts = ctx
      .stringInterpolation()
      .interpolatedStringSequence()
      .asScala
      .flatMap(inter => {
        Seq(
          Ast(
            NewCall()
              .code(inter.getText)
              .name(RubyOperators.formattedValue)
              .methodFullName(RubyOperators.formattedValue)
              .lineNumber(line(ctx))
              .columnNumber(column(ctx))
              .typeFullName(Defines.Any)
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
          )
        ) ++
          astForStatements(inter.compoundStatement().statements(), false, false)
      })
      .toSeq

    val nodes = ctx
      .stringInterpolation()
      .DOUBLE_QUOTED_STRING_CHARACTER_SEQUENCE()
      .asScala
      .map { substr =>
        {
          NewLiteral()
            .code(substr.getText)
            .typeFullName(Defines.String)
            .dynamicTypeHintFullName(List(Defines.String))
        }
      }
      .toSeq
    varAsts ++ Seq(Ast(nodes))
  }

  def astForPrimaryContext(ctx: PrimaryContext): Seq[Ast] = ctx match {
    case ctx: ClassDefinitionPrimaryContext if ctx.hasClassDefinition => astForClassDeclaration(ctx)
    case ctx: ClassDefinitionPrimaryContext                           => astForClassExpression(ctx)
    case ctx: ModuleDefinitionPrimaryContext                          => astForModuleDefinitionPrimaryContext(ctx)
    case ctx: MethodDefinitionPrimaryContext => astForMethodDefinitionContext(ctx.methodDefinition())
    case ctx: ProcDefinitionPrimaryContext   => astForProcDefinitionContext(ctx.procDefinition())
    case ctx: YieldWithOptionalArgumentPrimaryContext =>
      Seq(astForYieldCall(ctx, Option(ctx.yieldWithOptionalArgument().arguments())))
    case ctx: IfExpressionPrimaryContext     => Seq(astForIfExpression(ctx.ifExpression()))
    case ctx: UnlessExpressionPrimaryContext => Seq(astForUnlessExpression(ctx.unlessExpression()))
    case ctx: CaseExpressionPrimaryContext   => astForCaseExpressionPrimaryContext(ctx)
    case ctx: WhileExpressionPrimaryContext  => Seq(astForWhileExpression(ctx.whileExpression()))
    case ctx: UntilExpressionPrimaryContext  => Seq(astForUntilExpression(ctx.untilExpression()))
    case ctx: ForExpressionPrimaryContext    => Seq(astForForExpression(ctx.forExpression()))
    case ctx: ReturnWithParenthesesPrimaryContext =>
      Seq(returnAst(returnNode(ctx, text(ctx)), astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())))
    case ctx: JumpExpressionPrimaryContext     => astForJumpExpressionPrimaryContext(ctx)
    case ctx: BeginExpressionPrimaryContext    => astForBeginExpressionPrimaryContext(ctx)
    case ctx: GroupingExpressionPrimaryContext => astForCompoundStatement(ctx.compoundStatement(), false, false)
    case ctx: VariableReferencePrimaryContext  => Seq(astForVariableReference(ctx.variableReference()))
    case ctx: SimpleScopedConstantReferencePrimaryContext =>
      astForSimpleScopedConstantReferencePrimaryContext(ctx)
    case ctx: ChainedScopedConstantReferencePrimaryContext =>
      astForChainedScopedConstantReferencePrimaryContext(ctx)
    case ctx: ArrayConstructorPrimaryContext       => astForArrayLiteral(ctx.arrayConstructor())
    case ctx: HashConstructorPrimaryContext        => astForHashConstructorPrimaryContext(ctx)
    case ctx: LiteralPrimaryContext                => astForLiteralPrimaryExpression(ctx)
    case ctx: StringExpressionPrimaryContext       => astForStringExpression(ctx.stringExpression)
    case ctx: QuotedStringExpressionPrimaryContext => astForQuotedStringExpression(ctx.quotedStringExpression)
    case ctx: RegexInterpolationPrimaryContext =>
      astForRegexInterpolationPrimaryContext(ctx.regexInterpolation)
    case ctx: QuotedRegexInterpolationPrimaryContext  => astForQuotedRegexInterpolation(ctx.quotedRegexInterpolation)
    case ctx: IsDefinedPrimaryContext                 => Seq(astForIsDefinedPrimaryExpression(ctx))
    case ctx: SuperExpressionPrimaryContext           => Seq(astForSuperExpression(ctx))
    case ctx: IndexingExpressionPrimaryContext        => astForIndexingExpressionPrimaryContext(ctx)
    case ctx: MethodOnlyIdentifierPrimaryContext      => astForMethodOnlyIdentifier(ctx.methodOnlyIdentifier())
    case ctx: InvocationWithBlockOnlyPrimaryContext   => astForInvocationWithBlockOnlyPrimaryContext(ctx)
    case ctx: InvocationWithParenthesesPrimaryContext => astForInvocationWithParenthesesPrimaryContext(ctx)
    case ctx: ChainedInvocationPrimaryContext         => astForChainedInvocationPrimaryContext(ctx)
    case ctx: ChainedInvocationWithoutArgumentsPrimaryContext =>
      astForChainedInvocationWithoutArgumentsPrimaryContext(ctx)
    case _ =>
      logger.error(s"astForPrimaryContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForExpressionContext(ctx: ExpressionContext): Seq[Ast] = ctx match {
    case ctx: PrimaryExpressionContext             => astForPrimaryContext(ctx.primary())
    case ctx: UnaryExpressionContext               => Seq(astForUnaryExpression(ctx))
    case ctx: PowerExpressionContext               => Seq(astForPowerExpression(ctx))
    case ctx: UnaryMinusExpressionContext          => Seq(astForUnaryMinusExpression(ctx))
    case ctx: MultiplicativeExpressionContext      => Seq(astForMultiplicativeExpression(ctx))
    case ctx: AdditiveExpressionContext            => Seq(astForAdditiveExpression(ctx))
    case ctx: BitwiseShiftExpressionContext        => Seq(astForBitwiseShiftExpression(ctx))
    case ctx: BitwiseAndExpressionContext          => Seq(astForBitwiseAndExpression(ctx))
    case ctx: BitwiseOrExpressionContext           => Seq(astForBitwiseOrExpression(ctx))
    case ctx: RelationalExpressionContext          => Seq(astForRelationalExpression(ctx))
    case ctx: EqualityExpressionContext            => Seq(astForEqualityExpression(ctx))
    case ctx: OperatorAndExpressionContext         => Seq(astForAndExpression(ctx))
    case ctx: OperatorOrExpressionContext          => Seq(astForOrExpression(ctx))
    case ctx: RangeExpressionContext               => astForRangeExpressionContext(ctx)
    case ctx: ConditionalOperatorExpressionContext => Seq(astForTernaryConditionalOperator(ctx))
    case ctx: SingleAssignmentExpressionContext    => astForSingleAssignmentExpressionContext(ctx)
    case ctx: MultipleAssignmentExpressionContext  => astForMultipleAssignmentExpressionContext(ctx)
    case ctx: IsDefinedExpressionContext           => Seq(astForIsDefinedExpression(ctx))
    case _ =>
      logger.error(s"astForExpressionContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForProcDefinitionContext(ctx: ProcDefinitionContext): Seq[Ast] = {
    /*
     * Model a proc as a method
     */
    // Note: For parameters in the Proc definiton, an implicit parameter which goes by the name of `this` is added to the cpg
    val procId = blockIdCounter
    blockIdCounter += 1
    val procMethodName = "proc_" + procId
    val methodFullName = classStack.reverse :+ procMethodName mkString pathSep
    val newMethodNode  = methodNode(ctx, procMethodName, text(ctx), methodFullName, None, relativeFilename)

    scope.pushNewScope(newMethodNode)

    val astMethodParam = astForParametersContext(ctx.parameters())
    val paramNames     = astMethodParam.flatMap(_.nodes).collect { case x: NewMethodParameterIn => x.name }.toSet
    val locals         = scope.createAndLinkLocalNodes(diffGraph, paramNames).map(Ast.apply)
    val astBody        = astForCompoundStatement(ctx.block.compoundStatement, true)

    val methodRetNode = NewMethodReturn()
      .typeFullName(Defines.Any)

    val publicModifier = NewModifier().modifierType(ModifierTypes.PUBLIC)

    val methAst = methodAst(
      newMethodNode,
      astMethodParam,
      blockAst(blockNode(ctx), locals ++ astBody.toList),
      methodRetNode,
      Seq[NewModifier](publicModifier)
    )
    blockMethods.addOne(methAst)

    val callArgs = astMethodParam
      .map(ast => {
        val param = ast.nodes.head.asInstanceOf[NewMethodParameterIn]
        val node  = createIdentifierWithScope(ctx, param.name, param.code, Defines.Any, Seq())
        Ast(node)
      })

    val procCallNode =
      callNode(ctx, text(ctx), procMethodName, methodFullName, DispatchTypes.STATIC_DISPATCH, None, Option(Defines.Any))

    scope.popScope()

    Seq(callAst(procCallNode, callArgs))
  }

  def astForDefinedMethodNameOrSymbolContext(ctx: DefinedMethodNameOrSymbolContext): Seq[Ast] = {
    if (ctx == null) return Seq(Ast())

    if (ctx.definedMethodName() != null) {
      astForDefinedMethodNameContext(ctx.definedMethodName())
    } else {
      Seq(astForSymbolLiteral(ctx.symbol()))
    }
  }

  def astForIndexingArgumentsContext(ctx: IndexingArgumentsContext): Seq[Ast] = ctx match {
    case ctx: RubyParser.CommandOnlyIndexingArgumentsContext =>
      astForCommand(ctx.command())
    case ctx: RubyParser.ExpressionsOnlyIndexingArgumentsContext =>
      ctx
        .expressions()
        .expression()
        .asScala
        .flatMap(exp => {
          astForExpressionContext(exp)
        })
        .toSeq
    case ctx: RubyParser.ExpressionsAndSplattingIndexingArgumentsContext =>
      val expAsts = ctx
        .expressions()
        .expression()
        .asScala
        .flatMap(exp => {
          astForExpressionContext(exp)
        })
        .toSeq
      val splatAsts = astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
      val callNode = NewCall()
        .name(Operators.arrayInitializer)
        .methodFullName(Operators.arrayInitializer)
        .signature("")
        .typeFullName(Defines.Any)
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .code(text(ctx))
        .lineNumber(ctx.COMMA().getSymbol.getLine)
        .columnNumber(ctx.COMMA().getSymbol.getCharPositionInLine)
      Seq(callAst(callNode, expAsts ++ splatAsts))
    case ctx: AssociationsOnlyIndexingArgumentsContext =>
      astForAssociationsContext(ctx.associations())
    case ctx: RubyParser.SplattingOnlyIndexingArgumentsContext =>
      astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
    case _ =>
      logger.error(s"astForIndexingArgumentsContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForBeginExpressionPrimaryContext(ctx: BeginExpressionPrimaryContext): Seq[Ast] = {
    astForBodyStatementContext(ctx.beginExpression().bodyStatement())
  }

  def astForWhenArgumentContext(ctx: WhenArgumentContext): Seq[Ast] = {
    val expAsts =
      ctx
        .expressions()
        .expression()
        .asScala
        .flatMap(exp => {
          astForExpressionContext(exp)
        })
        .toList

    if (ctx.splattingArgument() != null) {
      expAsts ++ astForExpressionOrCommand(ctx.splattingArgument().expressionOrCommand())
    } else {
      expAsts
    }
  }

  def astForCaseExpressionPrimaryContext(ctx: CaseExpressionPrimaryContext): Seq[Ast] = {
    val code = s"case ${Option(ctx.caseExpression().expressionOrCommand).map(_.getText).getOrElse("")}".stripTrailing()
    val switchNode = controlStructureNode(ctx, ControlStructureTypes.SWITCH, code)
    val conditionAst = Option(ctx.caseExpression().expressionOrCommand()).toList
      .flatMap(astForExpressionOrCommand)
      .headOption

    val whenThenAstsList = ctx
      .caseExpression()
      .whenClause()
      .asScala
      .flatMap(wh => {
        val whenNode =
          jumpTargetNode(wh, "case", s"case ${wh.getText}", Option(wh.getClass.getSimpleName))

        val whenACondAsts = astForWhenArgumentContext(wh.whenArgument())
        val thenAsts = astForCompoundStatement(
          wh.thenClause().compoundStatement(),
          isMethodBody = true,
          canConsiderAsLeaf = false
        ) ++ Seq(Ast(NewControlStructure().controlStructureType(ControlStructureTypes.BREAK)))
        Seq(Ast(whenNode)) ++ whenACondAsts ++ thenAsts
      })
      .toList

    val stmtAsts = whenThenAstsList ++ (Option(ctx.caseExpression().elseClause()) match
      case Some(elseClause) =>
        Ast(
          // name = "default" for behaviour determined by CfgCreator.cfgForJumpTarget
          jumpTargetNode(elseClause, "default", "else", Option(elseClause.getClass.getSimpleName))
        ) +: astForCompoundStatement(elseClause.compoundStatement(), isMethodBody = true, canConsiderAsLeaf = false)
      case None => Seq.empty[Ast]
    )
    val block = blockNode(ctx.caseExpression())
    Seq(controlStructureAst(switchNode, conditionAst, Seq(Ast(block).withChildren(stmtAsts))))
  }

  def astForChainedInvocationPrimaryContext(ctx: ChainedInvocationPrimaryContext): Seq[Ast] = {
    val hasBlockStmt = ctx.block() != null
    val primaryAst   = astForPrimaryContext(ctx.primary())
    val methodNameAst =
      if (!hasBlockStmt && text(ctx.methodName()) == "new") astForCallToConstructor(ctx.methodName(), primaryAst)
      else astForMethodNameContext(ctx.methodName())

    val terminalNode = if (ctx.COLON2() != null) {
      ctx.COLON2()
    } else if (ctx.DOT() != null) {
      ctx.DOT()
    } else {
      ctx.AMPDOT()
    }

    val argsAst = if (ctx.argumentsWithParentheses() != null) {
      astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())
    } else {
      Seq()
    }

    if (hasBlockStmt) {
      val blockName = methodNameAst.head.nodes.head
        .asInstanceOf[NewCall]
        .name
      val blockMethodName = blockName + terminalNode.getSymbol.getLine
      val blockMethodAsts =
        astForBlockMethod(
          ctxStmt = ctx.block().compoundStatement.statements(),
          ctxParam = ctx.block().blockParameter,
          blockMethodName,
          line(ctx).head,
          column(ctx).head,
          lineEnd(ctx).head,
          columnEnd(ctx).head
        )
      val blockMethodNode =
        blockMethodAsts.head.nodes.head
          .asInstanceOf[NewMethod]

      blockMethods.addOne(blockMethodAsts.head)

      val callNode = NewCall()
        .name(blockName)
        .methodFullName(blockMethodNode.fullName)
        .typeFullName(Defines.Any)
        .code(blockMethodNode.code)
        .lineNumber(blockMethodNode.lineNumber)
        .columnNumber(blockMethodNode.columnNumber)
        .dispatchType(DispatchTypes.STATIC_DISPATCH)

      val methodRefNode = NewMethodRef()
        .methodFullName(blockMethodNode.fullName)
        .typeFullName(Defines.Any)
        .code(blockMethodNode.code)
        .lineNumber(blockMethodNode.lineNumber)
        .columnNumber(blockMethodNode.columnNumber)

      Seq(callAst(callNode, argsAst ++ Seq(Ast(methodRefNode)), primaryAst.headOption))
    } else {
      val callNode = methodNameAst.head.nodes
        .filter(node => node.isInstanceOf[NewCall])
        .head
        .asInstanceOf[NewCall]

      if (callNode.name == "call" && ctx.primary().isInstanceOf[ProcDefinitionPrimaryContext]) {
        // this is a proc.call
        val baseCallNode = primaryAst.head.nodes.head.asInstanceOf[NewCall]
        Seq(callAst(baseCallNode, argsAst))
      } else {
        callNode
          .code(text(ctx))
          .lineNumber(terminalNode.getSymbol().getLine())
          .columnNumber(terminalNode.getSymbol().getCharPositionInLine())

        primaryAst.headOption match {
          case Some(value) =>
            if (value.root.map(_.isInstanceOf[NewMethod]).getOrElse(false)) {
              val methodNode = value.root.head.asInstanceOf[NewMethod]
              val methodRefNode = NewMethodRef()
                .code("def " + methodNode.name + "(...)")
                .methodFullName(methodNode.fullName)
                .typeFullName(methodNode.fullName)
              blockMethods.addOne(primaryAst.head)
              Seq(callAst(callNode, Seq(Ast(methodRefNode)) ++ argsAst))
            } else {
              Seq(callAst(callNode, argsAst, primaryAst.headOption))
            }
          case None =>
            Seq(callAst(callNode, argsAst, primaryAst.headOption))
        }
      }
    }
  }

  private def astForCallToConstructor(ctx: MethodNameContext, receiverAst: Seq[Ast]): Seq[Ast] = {
    val receiverTypeName = receiverAst.flatMap(_.root).collectFirst { case x: NewIdentifier => x } match
      case Some(receiverNode) if receiverNode.typeFullName != Defines.Any =>
        receiverNode.typeFullName
      case Some(receiverNode) if typeDeclNameToTypeDecl.contains(receiverNode.name) =>
        typeDeclNameToTypeDecl(receiverNode.name).fullName
      case _ => Defines.Any

    val name = XDefines.ConstructorMethodName
    val (methodFullName, typeFullName) =
      if (receiverTypeName != Defines.Any)
        (Seq(receiverTypeName, XDefines.ConstructorMethodName).mkString(pathSep), receiverTypeName)
      else (XDefines.DynamicCallUnknownFullName, Defines.Any)

    val constructorCall =
      callNode(ctx, text(ctx), name, methodFullName, DispatchTypes.STATIC_DISPATCH, None, Option(typeFullName))
    Seq(Ast(constructorCall))
  }

  def astForChainedInvocationWithoutArgumentsPrimaryContext(
    ctx: ChainedInvocationWithoutArgumentsPrimaryContext
  ): Seq[Ast] = {
    val methodNameAst = astForMethodNameContext(ctx.methodName())
    val baseAst       = astForPrimaryContext(ctx.primary())

    val blocksAst = if (ctx.block() != null) {
      Seq(astForBlock(ctx.block()))
    } else {
      Seq()
    }
    val callNode = methodNameAst.head.nodes.filter(node => node.isInstanceOf[NewCall]).head.asInstanceOf[NewCall]
    callNode
      .code(text(ctx))
      .lineNumber(ctx.COLON2().getSymbol().getLine())
      .columnNumber(ctx.COLON2().getSymbol().getCharPositionInLine())
    Seq(callAst(callNode, baseAst ++ blocksAst))
  }

  def astForChainedScopedConstantReferencePrimaryContext(
    ctx: ChainedScopedConstantReferencePrimaryContext
  ): Seq[Ast] = {
    val primaryAst = astForPrimaryContext(ctx.primary())
    val localVar   = ctx.CONSTANT_IDENTIFIER()
    val varSymbol  = localVar.getSymbol()
    val node     = createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))
    val constAst = Ast(node)

    val operatorName = getOperatorName(ctx.COLON2().getSymbol)
    val callNode = NewCall()
      .name(operatorName)
      .code(text(ctx))
      .methodFullName(operatorName)
      .signature("")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(Defines.Any)
      .lineNumber(ctx.COLON2().getSymbol().getLine())
      .columnNumber(ctx.COLON2().getSymbol().getCharPositionInLine())
    Seq(callAst(callNode, primaryAst ++ Seq(constAst)))
  }

  def astForGroupedLeftHandSideContext(ctx: GroupedLeftHandSideContext): Seq[Ast] = {
    astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide())
  }

  def astForPackingLeftHandSideContext(ctx: PackingLeftHandSideContext): Seq[Ast] = {
    astForSingleLeftHandSideContext(ctx.singleLeftHandSide())
  }

  def astForMultipleLeftHandSideContext(ctx: MultipleLeftHandSideContext): Seq[Ast] = ctx match {
    case ctx: MultipleLeftHandSideAndpackingLeftHandSideMultipleLeftHandSideContext =>
      val multipleLHSAsts = ctx
        .multipleLeftHandSideItem()
        .asScala
        .flatMap(item => {
          if (item.singleLeftHandSide() != null) {
            astForSingleLeftHandSideContext(item.singleLeftHandSide())
          } else {
            astForGroupedLeftHandSideContext(item.groupedLeftHandSide())
          }
        })
        .toList

      val paramAsts =
        if (ctx.packingLeftHandSide() != null) {
          val packingLHSAst = astForPackingLeftHandSideContext(ctx.packingLeftHandSide())
          multipleLHSAsts ++ packingLHSAst
        } else {
          multipleLHSAsts
        }

      paramAsts

    case ctx: PackingLeftHandSideOnlyMultipleLeftHandSideContext =>
      astForPackingLeftHandSideContext(ctx.packingLeftHandSide())
    case ctx: GroupedLeftHandSideOnlyMultipleLeftHandSideContext =>
      astForGroupedLeftHandSideContext(ctx.groupedLeftHandSide())
    case _ =>
      logger.error(s"astForMultipleLeftHandSideContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForForVariableContext(ctx: ForVariableContext): Seq[Ast] = {
    if (ctx.singleLeftHandSide() != null) {
      astForSingleLeftHandSideContext(ctx.singleLeftHandSide())
    } else if (ctx.multipleLeftHandSide() != null) {
      astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide())
    } else {
      Seq(Ast())
    }
  }

  // TODO: Clean-up and take into account other hash elements
  def astForHashConstructorPrimaryContext(ctx: HashConstructorPrimaryContext): Seq[Ast] = {
    if (ctx.hashConstructor().hashConstructorElements() == null) return Seq(Ast())
    val hashCtorElemCtxs = ctx.hashConstructor().hashConstructorElements().hashConstructorElement().asScala
    val associationCtxs  = hashCtorElemCtxs.filter(_.association() != null).map(_.association()).toSeq
    val expressionCtxs   = hashCtorElemCtxs.filter(_.expression() != null).map(_.expression()).toSeq
    expressionCtxs.flatMap(astForExpressionContext) ++ associationCtxs.flatMap(astForAssociationContext)
  }

  def astForIndexingExpressionPrimaryContext(ctx: IndexingExpressionPrimaryContext): Seq[Ast] = {
    val lhsExpressionAst = astForPrimaryContext(ctx.primary())
    val rhsExpressionAst = Option(ctx.indexingArguments).map(astForIndexingArgumentsContext).getOrElse(Seq())

    val operator = lhsExpressionAst.flatMap(_.nodes).collectFirst { case x: NewIdentifier => x } match
      case Some(node) if node.name == "Array" => Operators.arrayInitializer
      case _                                  => Operators.indexAccess

    val callNode = NewCall()
      .name(operator)
      .code(text(ctx))
      .methodFullName(operator)
      .signature("")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(Defines.Any)
      .lineNumber(ctx.LBRACK().getSymbol.getLine)
      .columnNumber(ctx.LBRACK().getSymbol.getCharPositionInLine)
    Seq(callAst(callNode, lhsExpressionAst ++ rhsExpressionAst))

  }

  def astForInvocationExpressionOrCommandContext(ctx: InvocationExpressionOrCommandContext): Seq[Ast] = {
    if (ctx.EMARK() != null) {
      val invocWOParenAsts = astForInvocationWithoutParenthesesContext(ctx.invocationWithoutParentheses())
      val operatorName     = getOperatorName(ctx.EMARK().getSymbol)
      val callNode = NewCall()
        .name(operatorName)
        .code(text(ctx))
        .methodFullName(operatorName)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(ctx.EMARK().getSymbol().getLine())
        .columnNumber(ctx.EMARK().getSymbol().getCharPositionInLine())
      Seq(callAst(callNode, invocWOParenAsts))
    } else {
      astForInvocationWithoutParenthesesContext(ctx.invocationWithoutParentheses())
    }
  }

  def astForInvocationWithoutParenthesesContext(ctx: InvocationWithoutParenthesesContext): Seq[Ast] = ctx match {
    case ctx: SingleCommandOnlyInvocationWithoutParenthesesContext => astForCommand(ctx.command())
    case ctx: ChainedCommandDoBlockInvocationWithoutParenthesesContext =>
      astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock())
    case ctx: ReturnArgsInvocationWithoutParenthesesContext =>
      val retNode = NewReturn()
        .code(text(ctx))
        .lineNumber(ctx.RETURN().getSymbol().getLine)
        .columnNumber(ctx.RETURN().getSymbol().getCharPositionInLine)
      val argAst = Option(ctx.arguments).map(astForArguments).getOrElse(Seq())
      Seq(returnAst(retNode, argAst))
    case ctx: BreakArgsInvocationWithoutParenthesesContext =>
      val args = ctx.arguments()
      Option(args) match {
        case Some(args) =>
          /*
           * This is break with args inside a block. The argument passed to break will be returned by the bloc
           * Model this as a return since this is effectively a  return
           */
          val retNode = NewReturn()
            .code(text(ctx))
            .lineNumber(ctx.BREAK().getSymbol().getLine)
            .columnNumber(ctx.BREAK().getSymbol().getCharPositionInLine)
          val argAst = astForArguments(args)
          Seq(returnAst(retNode, argAst))
        case None =>
          val node = NewControlStructure()
            .controlStructureType(ControlStructureTypes.BREAK)
            .lineNumber(ctx.BREAK().getSymbol.getLine)
            .columnNumber(ctx.BREAK().getSymbol.getCharPositionInLine)
            .code(text(ctx))
          Seq(
            Ast(node)
              .withChildren(astForArguments(ctx.arguments()))
          )
      }
    case ctx: NextArgsInvocationWithoutParenthesesContext =>
      /*
       * While this is a `CONTINUE` for now, if we detect that this is the LHS of an `IF` then this becomes a `RETURN`
       */
      val node = NewControlStructure()
        .controlStructureType(ControlStructureTypes.CONTINUE)
        .lineNumber(ctx.NEXT().getSymbol.getLine)
        .columnNumber(ctx.NEXT().getSymbol.getCharPositionInLine)
        .code(Defines.ModifierNext)
      Seq(
        Ast(node)
          .withChildren(astForArguments(ctx.arguments()))
      )
    case _ =>
      logger.error(
        s"astForInvocationWithoutParenthesesContext() $relativeFilename, ${text(ctx)} All contexts mismatched."
      )
      Seq(Ast())
  }

  def astForInvocationWithBlockOnlyPrimaryContext(ctx: InvocationWithBlockOnlyPrimaryContext): Seq[Ast] = {
    val methodIdAst = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    val blockName = methodIdAst.head.nodes.head
      .asInstanceOf[NewCall]
      .name

    val isYieldMethod = if (blockName.endsWith(YIELD_SUFFIX)) {
      val lookupMethodName = blockName.take(blockName.length - YIELD_SUFFIX.length)
      methodNamesWithYield.contains(lookupMethodName)
    } else {
      false
    }

    if (isYieldMethod) {
      /*
       * This is a yield block. Create a fake method out of it. The yield call will be a call to the yield block
       */
      astForBlockMethod(
        ctx.block().compoundStatement.statements(),
        ctx.block().blockParameter,
        blockName,
        line(ctx).head,
        lineEnd(ctx).head,
        column(ctx).head,
        columnEnd(ctx).head
      )
    } else {
      val blockAst = Seq(astForBlock(ctx.block()))
      // this is expected to be a call node
      val callNode = methodIdAst.head.nodes.head.asInstanceOf[NewCall]
      Seq(callAst(callNode, blockAst))
    }
  }

  def astForInvocationWithParenthesesPrimaryContext(ctx: InvocationWithParenthesesPrimaryContext): Seq[Ast] = {
    val methodIdAst = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    val parenAst    = astForArgumentsWithParenthesesContext(ctx.argumentsWithParentheses())
    val callNode    = methodIdAst.head.nodes.filter(_.isInstanceOf[NewCall]).head.asInstanceOf[NewCall]
    callNode.name(getActualMethodName(callNode.name))

    if (ctx.block() != null) {
      val isYieldMethod = if (callNode.name.endsWith(YIELD_SUFFIX)) {
        val lookupMethodName = callNode.name.take(callNode.name.length - YIELD_SUFFIX.length)
        methodNamesWithYield.contains(lookupMethodName)
      } else {
        false
      }
      if (isYieldMethod) {
        val methAst = astForBlock(ctx.block(), Some(callNode.name))
        blockMethods.addOne(methAst)
        Seq(callAst(callNode, parenAst))
      } else {
        val blockAst = Seq(astForBlock(ctx.block()))
        Seq(callAst(callNode, parenAst ++ blockAst))
      }
    } else
      Seq(callAst(callNode, parenAst))
  }

  def astForJumpExpressionPrimaryContext(ctx: JumpExpressionPrimaryContext): Seq[Ast] = {
    if (ctx.jumpExpression().BREAK() != null) {
      val node = NewControlStructure()
        .controlStructureType(ControlStructureTypes.BREAK)
        .lineNumber(ctx.jumpExpression().BREAK().getSymbol.getLine)
        .columnNumber(ctx.jumpExpression().BREAK().getSymbol.getCharPositionInLine)
        .code(text(ctx))
      Seq(Ast(node))
    } else if (ctx.jumpExpression().NEXT() != null) {
      val node = NewControlStructure()
        .controlStructureType(ControlStructureTypes.CONTINUE)
        .lineNumber(ctx.jumpExpression().NEXT().getSymbol.getLine)
        .columnNumber(ctx.jumpExpression().NEXT().getSymbol.getCharPositionInLine)
        .code(Defines.ModifierNext)
      Seq(Ast(node))
    } else if (ctx.jumpExpression().REDO() != null) {
      val node = NewControlStructure()
        .controlStructureType(ControlStructureTypes.CONTINUE)
        .lineNumber(ctx.jumpExpression().REDO().getSymbol.getLine)
        .columnNumber(ctx.jumpExpression().REDO().getSymbol.getCharPositionInLine)
        .code(Defines.ModifierRedo)
      Seq(Ast(node))
    } else if (ctx.jumpExpression().RETRY() != null) {
      val node = NewControlStructure()
        .controlStructureType(ControlStructureTypes.CONTINUE)
        .lineNumber(ctx.jumpExpression().RETRY().getSymbol.getLine)
        .columnNumber(ctx.jumpExpression().RETRY().getSymbol.getCharPositionInLine)
        .code(Defines.ModifierRetry)
      Seq(Ast(node))
    } else {
      Seq(Ast())
    }
  }

  def astForSimpleMethodNamePartContext(ctx: SimpleMethodNamePartContext): Seq[Ast] = {
    astForDefinedMethodNameContext(ctx.definedMethodName())
  }

  def astForCallNode(ctx: ParserRuleContext, code: String, isYieldBlock: Boolean = false): Ast = {
    val name = if (isYieldBlock) {
      s"${getActualMethodName(text(ctx))}$YIELD_SUFFIX"
    } else {
      val calleeName = getActualMethodName(text(ctx))
      // Add the call name to the global builtIn callNames set
      if (isBuiltin(calleeName)) builtInCallNames.add(calleeName)
      calleeName
    }

    callAst(callNode(ctx, code, name, DynamicCallUnknownFullName, DispatchTypes.STATIC_DISPATCH))
  }

  def astForMethodOnlyIdentifier(ctx: MethodOnlyIdentifierContext): Seq[Ast] = {
    if (ctx.LOCAL_VARIABLE_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, text(ctx)))
    } else if (ctx.CONSTANT_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, text(ctx)))
    } else if (ctx.keyword() != null) {
      Seq(astForCallNode(ctx, ctx.keyword().getText))
    } else {
      Seq(Ast())
    }
  }

  def astForMethodIdentifierContext(ctx: MethodIdentifierContext, code: String): Seq[Ast] = {
    // the local/const identifiers are definitely method names
    if (ctx.methodOnlyIdentifier() != null) {
      astForMethodOnlyIdentifier(ctx.methodOnlyIdentifier())
    } else if (ctx.LOCAL_VARIABLE_IDENTIFIER() != null) {
      val localVar  = ctx.LOCAL_VARIABLE_IDENTIFIER()
      val varSymbol = localVar.getSymbol
      Seq(astForCallNode(ctx, code, methodNamesWithYield.contains(varSymbol.getText)))
    } else if (ctx.CONSTANT_IDENTIFIER() != null) {
      Seq(astForCallNode(ctx, code))
    } else {
      Seq(Ast())
    }
  }

  def astForOperatorMethodNameContext(ctx: OperatorMethodNameContext): Seq[Ast] = {

    /*
     * This is for operator overloading for the class
     */
    val terminalNode = ctx.children.asScala.head
      .asInstanceOf[TerminalNode]

    val name           = text(ctx)
    val methodFullName = classStack.reverse :+ name mkString pathSep

    val callNode = NewCall()
      .name(name)
      .code(text(ctx))
      .methodFullName(methodFullName)
      .signature("")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(Defines.Any)
      .lineNumber(terminalNode.getSymbol().getLine())
      .columnNumber(terminalNode.getSymbol().getCharPositionInLine())
    Seq(callAst(callNode))
  }

  def astForMethodNameContext(ctx: MethodNameContext): Seq[Ast] = {
    if (ctx.methodIdentifier() != null) {
      astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    } else if (ctx.operatorMethodName() != null) {
      astForOperatorMethodNameContext(ctx.operatorMethodName())
    } else if (ctx.keyword() != null) {
      val terminalNode = ctx
        .keyword()
        .children
        .asScala
        .head
        .asInstanceOf[TerminalNode]
      val callNode = NewCall()
        .name(terminalNode.getText)
        .code(text(ctx))
        .methodFullName(terminalNode.getText)
        .signature("")
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(terminalNode.getSymbol().getLine())
        .columnNumber(terminalNode.getSymbol().getCharPositionInLine())
      Seq(callAst(callNode))
    } else {
      Seq(Ast())
    }
  }
  def astForAssignmentLikeMethodIdentifierContext(ctx: AssignmentLikeMethodIdentifierContext): Seq[Ast] = {
    Seq(
      callAst(
        callNode(ctx, text(ctx), text(ctx), text(ctx), DispatchTypes.STATIC_DISPATCH, Some(""), Some(Defines.Any))
      )
    )
  }

  def astForDefinedMethodNameContext(ctx: DefinedMethodNameContext): Seq[Ast] = {
    Option(ctx.methodName()) match
      case Some(methodNameCtx) => astForMethodNameContext(methodNameCtx)
      case None                => astForAssignmentLikeMethodIdentifierContext(ctx.assignmentLikeMethodIdentifier())
  }

  def astForSingletonObjectContext(ctx: SingletonObjectContext): Seq[Ast] = {
    if (ctx.variableIdentifier() != null) {
      Seq(astForVariableIdentifierHelper(ctx.variableIdentifier(), true))
    } else if (ctx.pseudoVariableIdentifier() != null) {
      Seq(Ast())
    } else if (ctx.expressionOrCommand() != null) {
      astForExpressionOrCommand(ctx.expressionOrCommand())
    } else {
      Seq(Ast())
    }
  }

  def astForSingletonMethodNamePartContext(ctx: SingletonMethodNamePartContext): Seq[Ast] = {
    val definedMethodNameAst = astForDefinedMethodNameContext(ctx.definedMethodName())
    val singletonObjAst      = astForSingletonObjectContext(ctx.singletonObject())
    definedMethodNameAst ++ singletonObjAst
  }

  def astForMethodNamePartContext(ctx: MethodNamePartContext): Seq[Ast] = ctx match {
    case ctx: SimpleMethodNamePartContext    => astForSimpleMethodNamePartContext(ctx)
    case ctx: SingletonMethodNamePartContext => astForSingletonMethodNamePartContext(ctx)
    case _ =>
      logger.error(s"astForMethodNamePartContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  private def astForParametersContext(ctx: ParametersContext): Seq[Ast] = {
    if (ctx == null) return Seq()

    // the parameterTupleList holds the parameter terminal node and is the parameter a variadic parameter
    val parameterTupleList = ctx.parameter().asScala.map {
      case procCtx if procCtx.procParameter() != null =>
        (Option(procCtx.procParameter().LOCAL_VARIABLE_IDENTIFIER()), false)
      case optCtx if optCtx.optionalParameter() != null =>
        (Option(optCtx.optionalParameter().LOCAL_VARIABLE_IDENTIFIER()), false)
      case manCtx if manCtx.mandatoryParameter() != null =>
        (Option(manCtx.mandatoryParameter().LOCAL_VARIABLE_IDENTIFIER()), false)
      case arrCtx if arrCtx.arrayParameter() != null =>
        (Option(arrCtx.arrayParameter().LOCAL_VARIABLE_IDENTIFIER()), arrCtx.arrayParameter().STAR() != null)
      case keywordCtx if keywordCtx.keywordParameter() != null =>
        (Option(keywordCtx.keywordParameter().LOCAL_VARIABLE_IDENTIFIER()), false)
      case _ => (None, false)
    }

    parameterTupleList.zipWithIndex.map { case (paraTuple, paraIndex) =>
      paraTuple match
        case (Some(paraValue), isVariadic) =>
          val varSymbol = paraValue.getSymbol
          createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, Seq[String](Defines.Any))
          Ast(
            createMethodParameterIn(
              varSymbol.getText,
              lineNumber = Some(varSymbol.getLine),
              colNumber = Some(varSymbol.getCharPositionInLine),
              order = paraIndex + 1,
              index = paraIndex + 1
            ).isVariadic(isVariadic)
          )
        case _ =>
          Ast(
            createMethodParameterIn(
              getUnusedVariableNames(usedVariableNames, Defines.TempParameter),
              order = paraIndex + 1,
              index = paraIndex + 1
            )
          )
    }.toList
  }

  // TODO: Rewrite for simplicity and take into account more than parameter names.
  def astForMethodParameterPartContext(ctx: MethodParameterPartContext): Seq[Ast] = {
    if (ctx == null || ctx.parameters() == null) return Seq()
    astForParametersContext(ctx.parameters())
  }

  def astForRescueClauseContext(ctx: RescueClauseContext): Ast = {
    val asts = ListBuffer[Ast]()

    if (ctx.exceptionClass() != null) {
      val exceptionClass = ctx.exceptionClass()

      if (exceptionClass.expression() != null) {
        asts.addAll(astForExpressionContext(exceptionClass.expression()))
      } else {
        asts.addAll(astForMultipleRightHandSideContext(exceptionClass.multipleRightHandSide()))
      }
    }

    if (ctx.exceptionVariableAssignment() != null) {
      asts.addAll(astForSingleLeftHandSideContext(ctx.exceptionVariableAssignment().singleLeftHandSide()))
    }

    asts.addAll(astForCompoundStatement(ctx.thenClause().compoundStatement(), false))
    blockAst(blockNode(ctx), asts.toList)
  }

  /** Handles body statements differently from [[astForBodyStatementContext]] by noting that method definitions should
    * be on the root level and assignments where the LHS starts with @@ should be treated as fields.
    */
  def astForClassBody(ctx: BodyStatementContext): Seq[Ast] = {
    val rootStatements =
      Option(ctx).map(_.compoundStatement()).map(_.statements()).map(astForStatements(_)).getOrElse(Seq())
    retrieveAndGenerateClassChildren(ctx, rootStatements)
  }

  /** As class bodies are not treated much differently to other procedure bodies, we need to retrieve certain components
    * that would result in the creation of interprocedural constructs.
    *
    * TODO: This is pretty hacky and the parser could benefit from more specific tokens
    */
  private def retrieveAndGenerateClassChildren(classCtx: BodyStatementContext, rootStatements: Seq[Ast]): Seq[Ast] = {
    val (memberLikeStmts, blockStmts) = rootStatements
      .flatMap { ast =>
        ast.root match
          case Some(x: NewMethod)                                 => Seq(ast)
          case Some(x: NewCall) if x.name == Operators.assignment => Seq(ast) ++ membersFromStatementAsts(ast)
          case _                                                  => Seq(ast)
      }
      .partition(_.root match
        case Some(_: NewMethod) => true
        case Some(_: NewMember) => true
        case _                  => false
      )

    val methodStmts = memberLikeStmts.filter(_.root.exists(_.isInstanceOf[NewMethod]))
    val memberNodes = memberLikeStmts.flatMap(_.root).collect { case m: NewMember => m }

    val uniqueMemberReferences =
      (memberNodes ++ fieldReferences.getOrElse(classStack.top, Set.empty).groupBy(_.getText).map { case (code, ctxs) =>
        NewMember()
          .name(code.replaceAll("@", ""))
          .code(code)
          .typeFullName(Defines.Any)
      }).toList.distinctBy(_.name).map { m =>
        val modifierType = m.name match
          case x if x.startsWith("@@") => ModifierTypes.STATIC
          case _                       => ModifierTypes.VIRTUAL
        val modifierAst = Ast(NewModifier().modifierType(modifierType))
        Ast(m).withChild(modifierAst)
      }

    // Create class initialization method to host all field initializers
    val classInitMethodAst = if (blockStmts.nonEmpty) {
      val classInitFullName = (classStack.reverse :+ XDefines.StaticInitMethodName).mkString(pathSep)
      val classInitMethod = methodNode(
        classCtx,
        XDefines.StaticInitMethodName,
        XDefines.StaticInitMethodName,
        classInitFullName,
        None,
        relativeFilename,
        Option(NodeTypes.TYPE_DECL),
        Option(classStack.reverse.mkString(pathSep))
      )
      val classInitBody = blockAst(blockNode(classCtx), blockStmts.toList)
      Seq(methodAst(classInitMethod, Seq.empty, classInitBody, methodReturnNode(classCtx, Defines.Any)))
    } else {
      Seq.empty
    }

    classInitMethodAst ++ uniqueMemberReferences ++ methodStmts
  }

  def astForBodyStatementContext(ctx: BodyStatementContext, isMethodBody: Boolean = false): Seq[Ast] = {
    if (ctx.rescueClause().size > 0) {
      val compoundStatementAsts = astForCompoundStatement(ctx.compoundStatement())
      val elseClauseAsts = Option(ctx.elseClause()) match
        case Some(ctx) => astForCompoundStatement(ctx.compoundStatement(), false)
        case None      => Seq()

      /*
       * TODO Conversion of last statement to return AST is needed here
       * This can be done after the data flow engine issue with return from a try block is fixed
       */
      val tryBodyAsts = compoundStatementAsts ++ elseClauseAsts
      val tryBodyAst  = blockAst(blockNode(ctx), tryBodyAsts.toList)

      val finallyAst = Option(ctx.ensureClause()) match
        case Some(ctx) => astForCompoundStatement(ctx.compoundStatement()).headOption
        case None      => None

      val catchAsts = ctx
        .rescueClause()
        .asScala
        .map(astForRescueClauseContext)
        .toSeq

      val tryNode = NewControlStructure()
        .controlStructureType(ControlStructureTypes.TRY)
        .code("try")
        .lineNumber(line(ctx))
        .columnNumber(column(ctx))

      Seq(tryCatchAst(tryNode, tryBodyAst, catchAsts, finallyAst))
    } else {
      astForCompoundStatement(ctx.compoundStatement(), isMethodBody)
    }
  }

  def astForMethodDefinitionContext(ctx: MethodDefinitionContext): Seq[Ast] = {
    val astMethodName = Option(ctx.methodNamePart()) match
      case Some(ctxMethodNamePart) =>
        astForMethodNamePartContext(ctxMethodNamePart)
      case None =>
        astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
    val callNode = astMethodName.head.nodes.filter(node => node.isInstanceOf[NewCall]).head.asInstanceOf[NewCall]

    // Create thisParameter if this is an instance method
    // TODO may need to revisit to make this more robust

    val (methodName, methodFullName) = if (callNode.name == "initialize") {
      (XDefines.ConstructorMethodName, classStack.reverse :+ XDefines.ConstructorMethodName mkString pathSep)
    } else {
      (callNode.name, classStack.reverse :+ callNode.name mkString pathSep)
    }
    val newMethodNode = methodNode(ctx, methodName, text(ctx), methodFullName, None, relativeFilename)
      .columnNumber(callNode.columnNumber)
      .lineNumber(callNode.lineNumber)

    scope.pushNewScope(newMethodNode)

    val astMethodParamSeq = ctx.methodNamePart() match {
      case _: SimpleMethodNamePartContext if !classStack.top.endsWith(":program") =>
        val thisParameterNode = createMethodParameterIn(
          "this",
          typeFullName = callNode.methodFullName,
          lineNumber = callNode.lineNumber,
          colNumber = callNode.columnNumber,
          index = 0,
          order = 0
        )
        Seq(Ast(thisParameterNode)) ++ astForMethodParameterPartContext(ctx.methodParameterPart())
      case _ => astForMethodParameterPartContext(ctx.methodParameterPart())
    }

    Option(ctx.END()).foreach(endNode => newMethodNode.lineNumberEnd(endNode.getSymbol.getLine))

    callNode.methodFullName(methodFullName)

    val classType = if (classStack.isEmpty) "Standalone" else classStack.top
    val classPath = classStack.reverse.toList.mkString(pathSep)
    packageContext.packageTable.addPackageMethod(packageContext.moduleName, callNode.name, classPath, classType)

    val astBody = Option(ctx.bodyStatement()) match {
      case Some(ctxBodyStmt) => astForBodyStatementContext(ctxBodyStmt, true)
      case None =>
        val expAst = astForExpressionContext(ctx.expression())
        Seq(lastStmtAsReturn(ctx.expression().getText, expAst.head))
    }

    // process yield calls.
    astBody
      .flatMap(_.nodes.collect { case x: NewCall => x }.filter(_.name == UNRESOLVED_YIELD))
      .foreach { yieldCallNode =>
        val name           = newMethodNode.name
        val methodFullName = classStack.reverse :+ callNode.name mkString pathSep
        yieldCallNode.name(name + YIELD_SUFFIX)
        yieldCallNode.methodFullName(methodFullName + YIELD_SUFFIX)
        methodNamesWithYield.add(newMethodNode.name)
        /*
         * These are calls to the yield block of this method.
         * Add this method to the list of yield blocks.
         * The add() is idempotent and so adding the same method multiple times makes no difference.
         * It just needs to be added at this place so that it gets added iff it has a yield block
         */
      }

    val methodRetNode = NewMethodReturn().typeFullName(Defines.Any)

    val modifierNode = lastModifier match {
      case Some(modifier) => NewModifier().modifierType(modifier).code(modifier)
      case None           => NewModifier().modifierType(ModifierTypes.PUBLIC).code(ModifierTypes.PUBLIC)
    }
    /*
     * public/private/protected modifiers are in a separate statement
     * TODO find out how they should be used. Need to do this iff it adds any value
     */
    if (methodName != XDefines.ConstructorMethodName) {
      methodNameToMethod.put(newMethodNode.name, newMethodNode)
    }

    /* Before creating ast, we traverse the method params and identifiers and link them*/
    val identifiers =
      astBody.flatMap(ast => ast.nodes.filter(_.isInstanceOf[NewIdentifier])).asInstanceOf[Seq[NewIdentifier]]

    val params = astMethodParamSeq
      .flatMap(_.nodes.collect { case x: NewMethodParameterIn => x })
      .toList
    val locals = scope.createAndLinkLocalNodes(diffGraph, params.map(_.name).toSet)

    params.foreach { param =>
      identifiers.filter(_.name == param.name).foreach { identifier =>
        diffGraph.addEdge(identifier, param, EdgeTypes.REF)
      }
    }
    scope.popScope()

    Seq(
      methodAst(
        newMethodNode,
        astMethodParamSeq,
        blockAst(blockNode(ctx), locals.map(Ast.apply) ++ astBody.toList),
        methodRetNode,
        Seq[NewModifier](modifierNode)
      )
    )
  }

  private def getPackedRHS(astsToConcat: Seq[Ast], wrapInBrackets: Boolean = false) = {
    val code = astsToConcat
      .flatMap(_.nodes)
      .collect { case x: AstNodeNew => x.code }
      .mkString(", ")

    val callNode = NewCall()
      .name(Operators.arrayInitializer)
      .methodFullName(Operators.arrayInitializer)
      .signature("")
      .typeFullName(Defines.Any)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .code(if (wrapInBrackets) s"[$code]" else code)
    Seq(callAst(callNode, astsToConcat))
  }

  def astForMultipleAssignmentExpressionContext(ctx: MultipleAssignmentExpressionContext): Seq[Ast] = {
    val rhsAsts      = astForMultipleRightHandSideContext(ctx.multipleRightHandSide())
    val lhsAsts      = astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide())
    val operatorName = getOperatorName(ctx.EQ().getSymbol)

    /*
     * This is multiple LHS and multiple RHS
     *Since we have multiple LHS and RHS elements here, we will now create synthetic assignment
     * call nodes to model how ruby assigns values from RHS elements to LHS elements. We create
     * tuples for each assignment and then pass them to the assignment calls nodes
     */
    val assigns =
      if (lhsAsts.size < rhsAsts.size) {
        /* The rightmost AST in the LHS is a packed variable.
         * Pack the extra ASTs and the rightmost AST in the RHS in one array like the if() part
         */

        val diff        = rhsAsts.size - lhsAsts.size
        val packedRHS   = getPackedRHS(rhsAsts.takeRight(diff + 1)).head
        val alignedAsts = lhsAsts.take(lhsAsts.size - 1).zip(rhsAsts.take(lhsAsts.size - 1))
        val packedAsts  = lhsAsts.takeRight(1) zip Seq(packedRHS)
        alignedAsts ++ packedAsts
      } else {
        lhsAsts.zip(rhsAsts)
      }

    assigns.map { argPair =>
      val lhsCode = argPair._1.nodes.headOption match {
        case Some(id: NewIdentifier) => id.code
        case Some(lit: NewLiteral)   => lit.code
        case _                       => ""
      }

      val rhsCode = argPair._2.nodes.headOption match {
        case Some(id: NewIdentifier) => id.code
        case Some(lit: NewLiteral)   => lit.code
        case Some(call: NewCall)     => call.code
        case _                       => ""
      }

      val syntheticCallNode = NewCall()
        .name(operatorName)
        .code(lhsCode + " = " + rhsCode)
        .methodFullName(operatorName)
        .dispatchType(DispatchTypes.STATIC_DISPATCH)
        .typeFullName(Defines.Any)
        .lineNumber(ctx.EQ().getSymbol().getLine())
        .columnNumber(ctx.EQ().getSymbol().getCharPositionInLine())

      callAst(syntheticCallNode, Seq(argPair._1, argPair._2))
    }
  }

  def astForSimpleScopedConstantReferencePrimaryContext(ctx: SimpleScopedConstantReferencePrimaryContext): Seq[Ast] = {
    val localVar  = ctx.CONSTANT_IDENTIFIER()
    val varSymbol = localVar.getSymbol()
    val node      = createIdentifierWithScope(ctx, varSymbol.getText, varSymbol.getText, Defines.Any, List(Defines.Any))

    val operatorName = getOperatorName(ctx.COLON2().getSymbol)
    val callNode = NewCall()
      .name(operatorName)
      .code(text(ctx))
      .methodFullName(operatorName)
      .signature("")
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(Defines.Any)
      .lineNumber(ctx.COLON2().getSymbol.getLine)
      .columnNumber(ctx.COLON2().getSymbol.getCharPositionInLine())

    Seq(callAst(callNode, Seq(Ast(node))))

  }

  def astForCommandWithDoBlockContext(ctx: CommandWithDoBlockContext): Seq[Ast] = ctx match {
    case ctx: ArgsAndDoBlockCommandWithDoBlockContext =>
      val argsAsts   = astForArguments(ctx.argumentsWithoutParentheses().arguments())
      val doBlockAst = Seq(astForDoBlock(ctx.doBlock()))
      argsAsts ++ doBlockAst
    case ctx: RubyParser.ArgsAndDoBlockAndMethodIdCommandWithDoBlockContext =>
      val argsAsts     = astForArguments(ctx.argumentsWithoutParentheses().arguments())
      val doBlockAsts  = Seq(astForDoBlock(ctx.doBlock()))
      val methodIdAsts = astForMethodIdentifierContext(ctx.methodIdentifier(), text(ctx))
      methodIdAsts ++ argsAsts ++ doBlockAsts
    case ctx: RubyParser.PrimaryMethodArgsDoBlockCommandWithDoBlockContext =>
      val argsAsts       = astForArguments(ctx.argumentsWithoutParentheses().arguments())
      val doBlockAsts    = Seq(astForDoBlock(ctx.doBlock()))
      val methodNameAsts = astForMethodNameContext(ctx.methodName())
      val primaryAsts    = astForPrimaryContext(ctx.primary())
      primaryAsts ++ methodNameAsts ++ argsAsts ++ doBlockAsts
    case _ =>
      logger.error(s"astForCommandWithDoBlockContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForChainedCommandWithDoBlockContext(ctx: ChainedCommandWithDoBlockContext): Seq[Ast] = {
    val cmdAsts   = astForCommandWithDoBlockContext(ctx.commandWithDoBlock())
    val mNameAsts = ctx.methodName().asScala.flatMap(mName => astForMethodNameContext(mName)).toSeq
    val apAsts = ctx
      .argumentsWithParentheses()
      .asScala
      .flatMap(ap => {
        astForArgumentsWithParenthesesContext(ap)
      })
      .toSeq
    cmdAsts ++ mNameAsts ++ apAsts
  }

  def astForArgumentsWithParenthesesContext(ctx: ArgumentsWithParenthesesContext): Seq[Ast] = ctx match {
    case _: BlankArgsArgumentsWithParenthesesContext  => Seq(Ast())
    case ctx: ArgsOnlyArgumentsWithParenthesesContext => astForArguments(ctx.arguments())
    case ctx: ExpressionsAndChainedCommandWithDoBlockArgumentsWithParenthesesContext =>
      val expAsts = ctx
        .expressions()
        .expression
        .asScala
        .flatMap(exp => {
          astForExpressionContext(exp)
        })
        .toSeq
      val ccDoBlock = astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock())
      expAsts ++ ccDoBlock
    case ctx: ChainedCommandWithDoBlockOnlyArgumentsWithParenthesesContext =>
      astForChainedCommandWithDoBlockContext(ctx.chainedCommandWithDoBlock())
    case _ =>
      logger.error(s"astForArgumentsWithParenthesesContext() $relativeFilename, ${text(ctx)} All contexts mismatched.")
      Seq(Ast())
  }

  def astForBlockParametersContext(ctx: BlockParametersContext): Seq[Ast] = {
    if (ctx.singleLeftHandSide() != null) {
      astForSingleLeftHandSideContext(ctx.singleLeftHandSide())
    } else if (ctx.multipleLeftHandSide() != null) {
      astForMultipleLeftHandSideContext(ctx.multipleLeftHandSide())
    } else {
      Seq(Ast())
    }
  }

  def astForBlockParameterContext(ctx: BlockParameterContext): Seq[Ast] = {
    if (ctx.blockParameters() != null) {
      astForBlockParametersContext(ctx.blockParameters())
    } else {
      Seq(Ast())
    }
  }

  def astForBlockMethod(
    ctxStmt: StatementsContext,
    ctxParam: Option[BlockParameterContext],
    blockMethodName: String,
    lineStart: Int,
    lineEnd: Int,
    colStart: Int,
    colEnd: Int
  ): Seq[Ast] = {
    /*
     * Model a block as a method
     */
    val methodFullName = classStack.reverse :+ blockMethodName mkString pathSep
    val newMethodNode = methodNode(ctxStmt, blockMethodName, text(ctxStmt), methodFullName, None, relativeFilename)
      .lineNumber(lineStart)
      .lineNumberEnd(lineEnd)
      .columnNumber(colStart)
      .columnNumberEnd(colEnd)

    scope.pushNewScope(newMethodNode)
    val astMethodParam = ctxParam.map(astForBlockParameterContext).getOrElse(Seq())

    val publicModifier = NewModifier().modifierType(ModifierTypes.PUBLIC)
    val paramSeq = astMethodParam.headOption match {
      case Some(value) =>
        value.nodes.map {
          /* In majority of cases, node will be an identifier */
          case identifierNode: NewIdentifier =>
            val param = NewMethodParameterIn()
              .name(identifierNode.name)
              .code(identifierNode.code)
              .lineNumber(identifierNode.lineNumber)
              .columnNumber(identifierNode.columnNumber)
            Ast(param)
          case callNode: NewCall =>
            /* TODO: Occasionally, we might encounter a _ call in cases like "do |_, x|" where we should handle this?
             * But for now, we just return an empty AST. Keeping this match explicitly here so we come back */
            Ast()
          case _ =>
            Ast()
        }.toSeq
      case None => Seq()
    }
    val paramNames = (astMethodParam ++ paramSeq)
      .flatMap(_.root)
      .collect {
        case x: NewMethodParameterIn => x.name
        case x: NewIdentifier        => x.name
      }
      .toSet
    val locals        = scope.createAndLinkLocalNodes(diffGraph, paramNames).map(Ast.apply)
    val astBody       = astForStatements(ctxStmt, true)
    val methodRetNode = NewMethodReturn().typeFullName(Defines.Any)

    scope.popScope()

    Seq(
      methodAst(
        newMethodNode,
        paramSeq,
        blockAst(blockNode(ctxStmt), locals ++ astBody.toList),
        methodRetNode,
        Seq(publicModifier)
      )
    )
  }

  def astForAssociationContext(ctx: AssociationContext): Seq[Ast] = {
    val terminalNode = Option(ctx.COLON()) match
      case Some(value) => value
      case None        => ctx.EQGT()
    val operatorText = getOperatorName(terminalNode.getSymbol)

    val callArgs =
      Option(ctx.keyword()) match {
        case Some(ctxKeyword) =>
          val expr1Ast  = astForCallNode(ctx, ctxKeyword.getText)
          val expr2Asts = astForExpressionContext(ctx.expression().get(0))
          Seq(expr1Ast) ++ expr2Asts
        case None =>
          var expr2Asts = Seq(Ast())
          val expr1Asts = astForExpressionContext(ctx.expression().get(0))
          if (ctx.expression().size() > 1 && ctx.expression().get(1) != null) {
            expr2Asts = astForExpressionContext(ctx.expression().get(1))
          }
          expr1Asts ++ expr2Asts
      }

    val callNode = NewCall()
      .name(operatorText)
      .code(text(ctx))
      .methodFullName(operatorText)
      .dispatchType(DispatchTypes.STATIC_DISPATCH)
      .typeFullName(Defines.Any)
      .lineNumber(terminalNode.getSymbol.getLine)
      .columnNumber(terminalNode.getSymbol.getCharPositionInLine)
    Seq(callAst(callNode, callArgs))
  }

  def astForAssociationsContext(ctx: AssociationsContext): Seq[Ast] = {
    ctx
      .association()
      .asScala
      .flatMap(astForAssociationContext)
      .toSeq
  }

}

/** Extends the Scope class to help scope variables and create locals.
  *
  * TODO: Extend this to similarly link parameter nodes (especially `this` node) for consistency.
  */
class RubyScope extends Scope[String, NewIdentifier, NewNode] {

  private type VarMap        = Map[String, VarGroup]
  private type ScopeNodeType = NewNode

  /** Groups a local node with its referencing identifiers.
    */
  private case class VarGroup(local: NewLocal, ids: List[NewIdentifier])

  /** Links a scope to its variable groupings.
    */
  private val scopeToVarMap = mutable.HashMap.empty[ScopeNodeType, VarMap]

  override def addToScope(identifier: String, variable: NewIdentifier): NewNode = {
    val scopeNode = super.addToScope(identifier, variable)
    stack.headOption.foreach(head => scopeToVarMap.appendIdentifierToVarGroup(head.scopeNode, variable))
    scopeNode
  }

  override def popScope(): Option[NewNode] = {
    stack.headOption.map(_.scopeNode).foreach(scopeToVarMap.remove)
    super.popScope()
  }

  /** Will generate local nodes for this scope's variables, excluding those that reference parameters.
    * @param paramNames
    *   the names of parameters.
    */
  def createAndLinkLocalNodes(
    diffGraph: BatchedUpdate.DiffGraphBuilder,
    paramNames: Set[String] = Set.empty
  ): List[DeclarationNew] = stack.headOption match
    case Some(top) => scopeToVarMap.buildVariableGroupings(top.scopeNode, paramNames ++ Set("this"), diffGraph)
    case None      => List.empty[DeclarationNew]

  private implicit class IdentifierExt(node: NewIdentifier) {

    /** Creates a new VarGroup and corresponding NewLocal for the given identifier.
      */
    def toNewVarGroup: VarGroup = {
      val newLocal = NewLocal()
        .name(node.name)
        .code(node.name)
        .lineNumber(node.lineNumber)
        .columnNumber(node.columnNumber)
        .typeFullName(node.typeFullName)
      VarGroup(newLocal, List(node))
    }

  }

  private implicit class ScopeExt(scopeMap: mutable.Map[ScopeNodeType, VarMap]) {

    /** Registers the identifier to its corresponding variable grouping in the given scope.
      */
    def appendIdentifierToVarGroup(key: ScopeNodeType, identifier: NewIdentifier): Unit =
      scopeMap.updateWith(key) {
        case Some(varMap: VarMap) =>
          Some(varMap.updatedWith(identifier.name) {
            case Some(varGroup: VarGroup) => Some(varGroup.copy(ids = varGroup.ids :+ identifier))
            case None                     => Some(identifier.toNewVarGroup)
          })
        case None =>
          Some(Map(identifier.name -> identifier.toNewVarGroup))
      }

    /** Will persist the variable groupings that do not represent parameter nodes and link them with REF edges.
      * @return
      *   the list of persisted local nodes.
      */
    def buildVariableGroupings(
      key: ScopeNodeType,
      paramNames: Set[String],
      diffGraph: BatchedUpdate.DiffGraphBuilder
    ): List[DeclarationNew] =
      scopeMap.get(key) match
        case Some(varMap) =>
          varMap.values
            .filterNot { case VarGroup(local, _) => paramNames.contains(local.name) }
            .map { case VarGroup(local, ids) =>
              ids.foreach(id => diffGraph.addEdge(id, local, EdgeTypes.REF))
              local
            }
            .toList
        case None => List.empty[DeclarationNew]
  }

}
