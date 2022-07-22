package io.joern.jssrc2cpg.io

import better.files.File
import io.joern.jssrc2cpg.Config
import io.joern.jssrc2cpg.passes.AstCreationPass
import io.joern.jssrc2cpg.utils.AstGenRunner
import io.joern.x2cpg.X2Cpg.newEmptyCpg
import io.shiftleft.semanticcpg.language._
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import java.util.regex.Pattern

class ExcludeTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks {

  private val projectUnderTest = File(getClass.getResource("/excludes").toURI)

  private def testWithArguments(exclude: Seq[String], excludeRegex: String, expectedFiles: Set[String]): Unit = {
    File.usingTemporaryDirectory("jssrc2cpgTests") { tmpDir =>
      val cpg    = newEmptyCpg()
      val config = Config(inputPath = projectUnderTest.toString, outputPath = tmpDir.toString)
      val finalConfig =
        config.copy(ignoredFiles = exclude.map(config.createPathForIgnore), ignoredFilesRegex = excludeRegex.r)
      val astgenResult = AstGenRunner.execute(finalConfig, tmpDir)
      new AstCreationPass(cpg, astgenResult, finalConfig).createAndApply()
      cpg.file.name.l should contain theSameElementsAs expectedFiles.map(_.replace("/", java.io.File.separator))
    }
  }

  "Using different excludes via program arguments" should {

    val testInput = Table(
      // -- Header for naming all test parameters
      ("statement", "exclude", "excludeRegex", "expectedResult"),
      // --
      // Test for default:
      (
        "exclude nothing if no excludes are given",
        Seq.empty[String],
        "",
        Set("index.js", "a.js", "folder/b.js", "folder/c.js", "foo.bar/d.js")
      ),
      // --
      // Tests for --exclude only:
      (
        "exclude a file with --exclude with relative path",
        Seq("index.js"),
        "",
        Set("a.js", "folder/b.js", "folder/c.js", "foo.bar/d.js")
      ),
      (
        "exclude files with --exclude with relative paths",
        Seq("index.js", "folder/b.js"),
        "",
        Set("a.js", "folder/c.js", "foo.bar/d.js")
      ),
      (
        "exclude a file with --exclude with absolute path",
        Seq(s"$projectUnderTest/index.js"),
        "",
        Set("a.js", "folder/b.js", "folder/c.js", "foo.bar/d.js")
      ),
      (
        "exclude files with --exclude with absolute paths",
        Seq(s"$projectUnderTest/index.js", s"$projectUnderTest/folder/b.js"),
        "",
        Set("a.js", "folder/c.js", "foo.bar/d.js")
      ),
      (
        "exclude files with --exclude with mixed paths",
        Seq("index.js", s"$projectUnderTest/folder/b.js"),
        "",
        Set("a.js", "folder/c.js", "foo.bar/d.js")
      ),
      (
        "exclude a folder with --exclude with absolute path",
        Seq(s"$projectUnderTest/folder/"),
        "",
        Set("a.js", "index.js", "foo.bar/d.js")
      ),
      (
        "exclude a folder with --exclude with relative path",
        Seq("folder/"),
        "",
        Set("a.js", "index.js", "foo.bar/d.js")
      ),
      // --
      // Tests for --exclude-regex only:
      (
        "exclude a file with --exclude-regex",
        Seq.empty,
        ".*index\\..*",
        Set("a.js", "folder/b.js", "folder/c.js", "foo.bar/d.js")
      ),
      ("exclude files with --exclude-regex", Seq.empty, ".*(index|b)\\..*", Set("a.js", "folder/c.js", "foo.bar/d.js")),
      (
        "exclude a complete folder with --exclude-regex",
        Seq.empty,
        s".*${Pattern.quote(java.io.File.separator)}folder${Pattern.quote(java.io.File.separator)}.*",
        Set("index.js", "a.js", "foo.bar/d.js")
      ),
      // --
      // Tests for mixed arguments
      (
        "exclude files with --exclude and --exclude-regex",
        Seq("a.js"),
        ".*(index|b)\\..*",
        Set("folder/c.js", "foo.bar/d.js")
      )
    )

    forAll(testInput) { (statement, exclude, excludeRegex, result) =>
      s"$statement" in testWithArguments(exclude, excludeRegex, result)
    }

  }

}