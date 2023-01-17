import bloop.integrations.sbt.BloopDefaults
import sbt.Keys._
import sbt.Tests.{Group, SubProcess}
import sbt._
import uk.gov.hmrc.DefaultBuildSettings
import uk.gov.hmrc.DefaultBuildSettings._
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

lazy val appName = "third-party-application"

lazy val plugins: Seq[Plugins]         = Seq(PlayScala, SbtAutoBuildPlugin, SbtDistributablesPlugin)
lazy val playSettings: Seq[Setting[_]] = Seq.empty

lazy val microservice = Project(appName, file("."))
  .enablePlugins(plugins: _*)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(playSettings: _*)
  .settings(scalaSettings: _*)
  .settings(publishingSettings: _*)
  .settings(defaultSettings(): _*)
  .settings(ScoverageSettings())
  .settings(
    name            := appName,
    scalaVersion    := "2.12.13",
    libraryDependencies ++= AppDependencies(),
    retrieveManaged := true,
    routesGenerator := InjectedRoutesGenerator,
    majorVersion    := 0,
    routesImport ++= Seq(
      "uk.gov.hmrc.thirdpartyapplication.controllers.binders._",
      "uk.gov.hmrc.apiplatform.modules.submissions.controllers._",
      "uk.gov.hmrc.apiplatform.modules.submissions.controllers.binders._",
      "uk.gov.hmrc.thirdpartyapplication.domain.models._",
      "uk.gov.hmrc.apiplatform.modules.submissions.domain.models._"
    )
  )
  .settings(
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
  )
  .settings(inConfig(Test)(BloopDefaults.configSettings))
  .settings(
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-eT"),
    Test / fork              := false,
    Test / unmanagedSourceDirectories ++= Seq(baseDirectory.value / "test", baseDirectory.value / "shared-test"),
    Test / parallelExecution := false
  )
  .configs(IntegrationTest)
  .settings(DefaultBuildSettings.integrationTestSettings())
  .settings(inConfig(IntegrationTest)(BloopDefaults.configSettings))
  .settings(
    IntegrationTest / fork              := false,
    IntegrationTest / unmanagedSourceDirectories ++= Seq(baseDirectory.value / "it", baseDirectory.value / "shared-test"),
    addTestReportOption(IntegrationTest, "int-test-reports"),
    IntegrationTest / testGrouping      := oneForkedJvmPerTest((IntegrationTest / definedTests).value),
    IntegrationTest / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-eT"),
    IntegrationTest / parallelExecution := false
  )
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature", "-Ypartial-unification")
  )

def oneForkedJvmPerTest(tests: Seq[TestDefinition]): Seq[Group] =
  tests map { test =>
    Group(
      test.name,
      Seq(test),
      SubProcess(
        ForkOptions().withRunJVMOptions(Vector(s"-Dtest.name=${test.name}"))
      )
    )
  }

Global / bloopAggregateSourceDependencies := true
