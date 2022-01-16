package io.joern.kotlin2cpg.types

import org.jetbrains.kotlin.resolve.BindingContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Ignore

@Ignore // uncomment as soon as the sourceDir path is correct
class KotlinScriptFilteringTests extends AnyFreeSpec with Matchers {
  "Running type inference operation on external project with lots of KotlinScript sources" - {
    "should return an empty binding context" in {
      val sourceDir = "src/test/resources/external_projects/kotlin-dsl"
      val environment = CompilerAPI.makeEnvironment(Seq(sourceDir), Seq())
      environment.getSourceFiles should not be List()

      val typeInfoProvider = new KotlinTypeInfoProvider(environment)
      typeInfoProvider.bindingContext should not be null
      typeInfoProvider.hasEmptyBindingContext shouldBe true
    }

    "should not return an empty binding context" in {
      val sourceDir = "src/test/resources/external_projects/kotlin-dsl"
      val dirsForSourcesToCompile = InferenceSourcesPicker.dirsForRoot(sourceDir)
      val environment = CompilerAPI.makeEnvironment(dirsForSourcesToCompile, Seq())
      environment.getSourceFiles should not be List()

      val typeInfoProvider = new KotlinTypeInfoProvider(environment)
      typeInfoProvider.bindingContext should not be null
      typeInfoProvider.hasEmptyBindingContext shouldBe false
    }
  }
}