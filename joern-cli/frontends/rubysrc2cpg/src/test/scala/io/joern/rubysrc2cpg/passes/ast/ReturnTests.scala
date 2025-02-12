package io.joern.rubysrc2cpg.passes.ast

import io.joern.rubysrc2cpg.testfixtures.RubyCode2CpgFixture
import io.shiftleft.codepropertygraph.generated.nodes.MethodRef
import io.shiftleft.semanticcpg.language.*

class ReturnTests extends RubyCode2CpgFixture {

  "a method, where the last statement is a method" should {
    val cpg = code("""
        |Row = Struct.new(:cancel_date) do
        |    def end_date = cancel_date
        |end
        |""".stripMargin)

    "return a method ref" in {
      val List(mRef: MethodRef) = cpg.method("new2").ast.isReturn.astChildren.l: @unchecked
      mRef.methodFullName shouldBe "Test0.rb::program.end_date"
    }
  }

}
