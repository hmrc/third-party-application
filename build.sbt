import play.core.PlayVersion
import sbt.Keys._
import sbt.Tests.{Group, SubProcess}
import sbt._
import uk.gov.hmrc.DefaultBuildSettings._
import uk.gov.hmrc.PublishingSettings._
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin._

lazy val appName = "third-party-application"

lazy val appDependencies: Seq[ModuleID] = compile ++ test

lazy val compile = Seq(
  "uk.gov.hmrc" %% "bootstrap-play-25" % "4.9.0",
  "uk.gov.hmrc" %% "mongo-lock" % "5.1.1",
  "uk.gov.hmrc" %% "play-reactivemongo" % "6.4.0",
  "uk.gov.hmrc" %% "play-scheduling" % "5.4.0",
  "uk.gov.hmrc" %% "play-json-union-formatter" % "1.4.0",
  "uk.gov.hmrc" %% "play-hmrc-api" % "3.4.0-play-25"
)
lazy val test = Seq(
  "uk.gov.hmrc" %% "reactivemongo-test" % "3.1.0" % "test,it",
  "uk.gov.hmrc" %% "hmrctest" % "3.6.0-play-25" % "test,it",
  "org.pegdown" % "pegdown" % "1.6.0" % "test,it",
  "org.scalaj" %% "scalaj-http" % "2.3.0" % "test,it",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test,it",
  "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.0" % "test,it",
  "com.typesafe.play" %% "play-test" % PlayVersion.current % "test,it",
  "com.github.tomakehurst" % "wiremock" % "1.58" % "test,it",
  "org.mockito" % "mockito-core" % "1.9.5" % "test,it"
)
lazy val plugins: Seq[Plugins] = Seq(_root_.play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory)
lazy val playSettings: Seq[Setting[_]] = Seq.empty

lazy val microservice = (project in file("."))
  .enablePlugins(plugins: _*)
  .settings(playSettings: _*)
  .settings(scalaSettings: _*)
  .settings(publishingSettings: _*)
  .settings(defaultSettings(): _*)
  .settings(
    name := appName,
    libraryDependencies ++= appDependencies,
    retrieveManaged := true,
    routesGenerator := InjectedRoutesGenerator,
    majorVersion := 0
  )
  .settings(
    unmanagedSourceDirectories in Compile += baseDirectory.value / "common",
    unmanagedResourceDirectories in Compile += baseDirectory.value / "resources"
  )
  .settings(playPublishingSettings: _*)
  .settings(inConfig(TemplateTest)(Defaults.testSettings): _*)
  .settings(
    testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-eT")
  )
  .configs(IntegrationTest)
  .settings(inConfig(TemplateItTest)(Defaults.itSettings): _*)
  .settings(
    Keys.fork in IntegrationTest := false,
    unmanagedSourceDirectories in IntegrationTest <<= (baseDirectory in IntegrationTest) (base => Seq(base / "test")),
    addTestReportOption(IntegrationTest, "int-test-reports"),
    testGrouping in IntegrationTest := oneForkedJvmPerTest((definedTests in IntegrationTest).value),
    testOptions in IntegrationTest += Tests.Argument(TestFrameworks.ScalaTest, "-eT"),
    parallelExecution in IntegrationTest := false)
  .settings(
    resolvers += Resolver.jcenterRepo
  )
  .settings(scalacOptions ++= Seq("-deprecation", "-feature"))
  .settings(ivyScala := ivyScala.value map (_.copy(overrideScalaVersion = true)))

lazy val allPhases = "tt->test;test->test;test->compile;compile->compile"
lazy val allItPhases = "tit->it;it->it;it->compile;compile->compile"

lazy val TemplateTest = config("tt") extend Test
lazy val TemplateItTest = config("tit") extend IntegrationTest
lazy val playPublishingSettings: Seq[sbt.Setting[_]] = Seq(

  credentials += SbtCredentials,

  publishArtifact in(Compile, packageDoc) := false,
  publishArtifact in(Compile, packageSrc) := false
) ++
  publishAllArtefacts

def oneForkedJvmPerTest(tests: Seq[TestDefinition]) =
  tests map {
    test => Group(test.name, Seq(test), SubProcess(ForkOptions(runJVMOptions = Seq("-Dtest.name=" + test.name))))
  }

// Coverage configuration
coverageMinimum := 88
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>;com.kenshoo.play.metrics.*;.*definition.*;prod.*;testOnlyDoNotUseInAppConf.*;app.*;uk.gov.hmrc.BuildInfo"
