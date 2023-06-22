package io.joern.rubysrc2cpg

import better.files.File
import io.joern.rubysrc2cpg.passes.{AstCreationPass, AstPackagePass, ConfigPass}
import io.joern.rubysrc2cpg.utils.PackageTable
import io.joern.x2cpg.X2Cpg.withNewEmptyCpg
import io.joern.x2cpg.X2CpgFrontend
import io.joern.x2cpg.datastructures.Global
import io.joern.x2cpg.passes.frontend.{MetaDataPass, TypeNodePass}
import io.joern.x2cpg.utils.ExternalCommand
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Languages
import org.slf4j.LoggerFactory

import java.nio.file.attribute.{PosixFilePermission, PosixFilePermissions}
import scala.sys.process._
import scala.util.{Failure, Success, Try}

class RubySrc2Cpg extends X2CpgFrontend[Config] {

  private val logger = LoggerFactory.getLogger(this.getClass)

  val global = new Global()

  override def createCpg(config: Config): Try[Cpg] = {
    withNewEmptyCpg(config.outputPath, config: Config) { (cpg, config) =>
      val packageTableInfo = new PackageTable()
      new MetaDataPass(cpg, Languages.RUBYSRC, config.inputPath).createAndApply()
      new ConfigPass(cpg, config.inputPath).createAndApply()
      if (config.enableDependencyDownload) {
        val tempDir = File.newTemporaryDirectory()
        try {
          downloadDependency(config.inputPath, tempDir.toString())
          new AstPackagePass(cpg, tempDir.toString(), global, packageTableInfo, config.inputPath).createAndApply()
        } finally {
          tempDir.delete()
        }
      }
      packageTableInfo.printInfo()
      val astCreationPass = new AstCreationPass(config.inputPath, cpg, global, packageTableInfo)
      astCreationPass.createAndApply()
      new TypeNodePass(astCreationPass.allUsedTypes(), cpg).createAndApply()
    }
  }

  private def downloadDependency(inputPath: String, tempPath: String): Unit = {
    val cwd     = File(inputPath)
    val tempDir = File(tempPath)
    tempDir.setPermissions(Set(PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE))
    if (File(s"${cwd.toString()}${java.io.File.separator}Gemfile").exists && tempDir.exists) {
      var command = ""
        ExternalCommand.run(s"bundle config set --local path ${tempDir.path.toString}", File(inputPath).path.toString) match {
          case Success(configOutput) =>
            println(configOutput)
          case Failure(exception) =>
            println(exception.getMessage)
        }

        command = s"bundle install"

      println(command)

      ExternalCommand.run(command, File(inputPath).path.toString) match {
        case Success(bundleOutput) =>
          logger.info(s"Dependency installed successfully: $bundleOutput")
        case Failure(exception) =>
          logger.error(s"Error while downloading dependency: ${exception.getMessage}")
      }
    }
  }
}
