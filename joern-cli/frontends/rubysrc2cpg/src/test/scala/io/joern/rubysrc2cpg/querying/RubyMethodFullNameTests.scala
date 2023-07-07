package io.joern.rubysrc2cpg.querying

import io.joern.rubysrc2cpg.testfixtures.RubyCode2CpgFixture
import io.shiftleft.semanticcpg.language._
import org.scalatest.BeforeAndAfterAll
import io.joern.rubysrc2cpg.Config

class RubyMethodFullNameTests extends RubyCode2CpgFixture with BeforeAndAfterAll {

  private val config = Config().withEnableDependencyDownload(true)

  "Code for method full name when method present in module" should {
    val cpg = code(
      """
        |require "dummy_logger"
        |
        |v = Main_module::Main_outer_class.new
        |v.first_fun("value")
        |
        |g = Help.new
        |g.help_print()
        |
        |""".stripMargin,
      "main.rb"
    )
      .moreCode(
        """
          |source 'https://rubygems.org'
          |gem 'dummy_logger'
          |
          |""".stripMargin,
        "Gemfile"
      )
      .withConfig(config)
    "recognise call node" in {
      cpg.call.name("first_fun").l.size shouldBe 1
    }

    "recognise methodFullName for call Node" in {
      if (!scala.util.Properties.isWin) {
        cpg.call.name("first_fun").head.methodFullName should equal(
          "dummy_logger::program:Main_module:Main_outer_class:first_fun"
        )
        cpg.call
          .name("help_print")
          .head
          .methodFullName shouldBe "dummy_logger::program:Help:help_print"
      }
    }
  }

  "Code for method full name when method present in other file" should {
    val cpg = code(
      """
        |require_relative "util/help.rb"
        |
        |v = Outer.new
        |v.printValue()
        |
        |""".stripMargin,
      "main.rb"
    )
      .moreCode(
        """
          |class Outer
          | def printValue()
          |   puts "print"
          | end
          |end
          |""".stripMargin,
        Seq("util", "help.rb").mkString(java.io.File.separator)
      )
      .withConfig(config)

    "recognise call node" in {
      cpg.call.name("printValue").size shouldBe 1
    }

    "recognise method full name for call node" in {
      if (!scala.util.Properties.isWin) {
        cpg.call
          .name("printValue")
          .head
          .methodFullName shouldBe "util/help.rb::program:Outer:printValue"
      }
    }
  }

  "Code for methodFullName on builtin function" should {
    val cpg = code("""
        |class Outer
        | def fun()
        |   puts "value"
        | end
        |end
        |""".stripMargin)
      .withConfig(config)

    "recognise call node" in {
      cpg.call.name("puts").size shouldBe 1
    }

    "recognise methodFullName for call node" in {
      cpg.call.name("puts").head.methodFullName shouldBe "__builtin:puts"
    }
  }

  "Code for methodFullName when method present on same file" should {
    val cpg = code(
      """
        |module Outer
        | class Inner
        |   def fun()
        |     puts "value"
        |   end
        | end
        |end
        |
        |temp = Outer.Inner.new
        |temp.fun()
        |""".stripMargin,
      "main.rb"
    ).withConfig(config)

    "recognise call node" in {
      cpg.call.name("fun").size shouldBe 1
    }

    "recognise methodFullName for call Node" in {
      cpg.call.name("fun").head.methodFullName shouldBe "main.rb::program:Outer:Inner:fun"
    }
  }
}
