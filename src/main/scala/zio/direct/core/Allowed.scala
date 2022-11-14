package zio.direct.core

import scala.quoted._
import zio.direct.core.util.Format
import zio.direct.core.metaprog.Trees
import zio.direct.core.metaprog.Extractors._
import zio.direct.core.util.PureTree
import zio.direct.core.util.Unsupported
import zio.direct.core.util.Examples
import zio.direct.core.metaprog.Instructions
import zio.direct.core.metaprog.Verify

object Allowed {

  def validateBlocksIn(using Quotes)(expr: Expr[_], instructions: Instructions): Unit =
    import quotes.reflect._
    validateBlocksTree(expr.asTerm, instructions)

  private def validateAwaitClause(using Quotes)(expr: quotes.reflect.Tree, instructions: Instructions): Unit =
    import quotes.reflect._
    Trees.traverse(expr, Symbol.spliceOwner) {
      // Cannot have nested awaits:
      case tree @ RunCall(_) =>
        Unsupported.Error.withTree(tree, Examples.AwaitInAwaitError)

      // Assignment in an await allowed by not recommenteded
      case asi: Assign =>
        Unsupported.Warn.withTree(asi, Examples.AwaitAssignmentNotRecommended)
    }

  // TODO this way of traversing the tree is error prone not not very efficient. Re-write this
  // using the TreeTraverser directly and make `traverse` private.
  private def validateBlocksTree(using Quotes)(inputTree: quotes.reflect.Tree, instructions: Instructions): Unit =
    import quotes.reflect._

    val declsError =
      instructions.verify match {
        case Verify.Strict  => Examples.DeclarationNotAllowed
        case Verify.Lenient => Examples.DeclarationNotAllowedWithAwaits
      }

    def validate(expr: Tree): Unit =
      // println(s"---------- Validating: ${Format.Tree(expr)}")
      expr match {
        case CaseDef(pattern, cond, output) =>
          cond match {
            case None              =>
            case Some(PureTree(_)) =>
            case Some(nonpure) =>
              Unsupported.Error.awaitUnsupported(nonpure, "Match conditionals are not allow to contain `await`. Move the `await` call out of the match-statement.")
          }

        case term: Term =>
          validateTerm(term)

        // if verification is in "Lenient mode", allow ClassDefs, DefDefs, and ValDefs so long
        // as there are no 'await' calls inside of them
        case PureTree(_) if (instructions.verify == Verify.Lenient) =>

        // Do not allow declarations inside of defer blocks
        case v: ClassDef =>
          Unsupported.Error.withTree(v, declsError)
        // defdefs are not allowed inside of defer blocks unless they are auto-generated by scala
        // (scala does that when doing implicit-functions etc...)
        case v: DefDef if (!v.symbol.flags.is(Flags.Mutable) && !v.symbol.flags.is(Flags.Synthetic)) =>
          Unsupported.Error.withTree(v, declsError)
        case v: ValDef if (v.symbol.flags.is(Flags.Mutable)) =>
          Unsupported.Error.withTree(v, declsError)

        // otherwise ignore, the tree traversal will continue
        case _ =>
      }
    end validate

    def validateTerm(expr: Term): Unit =
      expr match {
        // should be handled by the tree traverser but put here just in case
        case tree @ RunCall(content) =>
          validateAwaitClause(content.asTerm, instructions)

        // special error for assignment
        case asi: Assign =>
          Unsupported.Error.withTree(asi, Examples.AssignmentNotAllowed)

        // All the kinds of valid things a Term can be in defer blocks
        // Originally taken from TreeMap.transformTerm in Quotes.scala
        case Ident(name)             =>
        case Select(qualifier, name) =>
        case This(qual)              =>
        case Super(qual, mix)        =>
        case Apply(fun, args)        =>
        case TypeApply(fun, args)    =>
        case Literal(const)          =>
        case New(tpt)                =>
        case Typed(expr, tpt)        =>
        case Block(stats, expr)      =>
        case If(cond, thenp, elsep)  =>
        // Anonymous functions run from things inside of Async can have these
        case Closure(meth, tpt)                 =>
        case Match(selector, cases)             =>
        case Return(expr, from)                 =>
        case While(cond, body)                  =>
        case Try(block, cases, finalizer)       =>
        case Inlined(call, bindings, expansion) =>
        case SummonFrom(cases)                  =>

        case otherTree =>
          Unsupported.Error.awaitUnsupported(otherTree)
      }
    end validateTerm

    (new TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit = {
        tree match {
          case tree @ RunCall(content) =>
            validateAwaitClause(content.asTerm, instructions)
          case _ =>
        }
        validate(tree)
        super.traverseTree(tree)(owner)
      }
    ).traverseTree(inputTree)(Symbol.spliceOwner)
  end validateBlocksTree

  object ParallelExpression {
    def unapply(using Quotes)(expr: Expr[_]): Boolean =
      import quotes.reflect._
      checkAllowed(expr.asTerm)

    private def checkAllowed(using Quotes)(tree: quotes.reflect.Term): Boolean = {
      import quotes.reflect._
      tree match {
        case Ident(name) =>
          true
        case Select(qualifier, name) =>
          checkAllowed(qualifier)
        case This(qual) =>
          true
        case Super(qual, mix) =>
          checkAllowed(qual)
        case Apply(fun, args) =>
          checkTerms(args)
        case TypeApply(fun, args) =>
          checkAllowed(fun)
        case Literal(const) =>
          true
        case New(tpt) =>
          true
        case Typed(expr, tpt) =>
          checkAllowed(expr)
        case tree: NamedArg =>
          checkAllowed(tree.value)
        case Repeated(elems, elemtpt) =>
          checkTerms(elems)
        case Inlined(call, bindings, expansion) =>
          checkAllowed(expansion)
        case _ =>
          Unsupported.Error.awaitUnsupported(tree)
      }
    }

    private def checkTerms(using Quotes)(trees: List[quotes.reflect.Term]): Boolean =
      trees.forall(x => checkAllowed(x))
  }
}
