package zio.direct.core.util.debug

import scala.reflect.macros.whitebox.{Context => MacroContext}
import zio.direct.core.util.WithFormat
import zio.direct.core.metaprog.Trees

class PrintMacMacro(val c: MacroContext) extends WithFormat {
  import c.universe._

  def apply(value: Tree): Tree = {
    println(
      "================= Printing Tree =================\n" +
        show(value)
    )
    q"()"
  }

  def detail(value: Tree): Tree = {
    println(
      "================= Printing Tree =================\n" +
        show(value) + "\n" +
        "================= Printing Tree =================\n" +
        Format(showRaw(value))
    )

    Trees.traverse(c)(value) {
      // case v: ValDef =>
      //   println(s"========= ${show(v)} - isLazy: ${v.mods.hasFlag(Flag.LAZY)} - isMutable: ${v.mods.hasFlag(Flag.MUTABLE)} - isImplicit: ${v.mods.hasFlag(Flag.IMPLICIT)}")
      case id: Ident =>
        println(s"========= ${show(id)} - isImplicit: ${id.symbol}")
    }

    q"()"
  }

}
