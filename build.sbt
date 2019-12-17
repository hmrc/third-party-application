import play.core.PlayVersion
import sbt.Keys._
import sbt.Tests.{Group, SubProcess}
import sbt._
import uk.gov.hmrc.DefaultBuildSettings._
import uk.gov.hmrc.PublishingSettings._
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin._

lazy val appName = "third-party-application"

lazy val appDependencies: Seq[ModuleID] = compile ++ test ++ tmpMacWorkaround

val reactiveMongoVer = "0.18.8"

lazy val compile = Seq(
  "uk.gov.hmrc" %% "bootstrap-play-25" % "5.1.0",
  "uk.gov.hmrc" %% "play-scheduling" % "7.1.0-play-25",
  "uk.gov.hmrc" %% "play-json-union-formatter" % "1.7.0",
  "uk.gov.hmrc" %% "play-hmrc-api" % "3.6.0-play-25",
  "uk.gov.hmrc" %% "metrix" % "3.8.0-play-25",
  "uk.gov.hmrc" %% "simple-reactivemongo" % "7.21.0-play-25",
  "org.reactivemongo" %% "play2-reactivemongo" % (reactiveMongoVer + "-play25"),
  "org.reactivemongo" %% "reactivemongo-play-json" % (reactiveMongoVer + "-play25"),
  "commons-net" % "commons-net" % "3.6"
)
val scope = "test,it"

lazy val test = Seq(
  "uk.gov.hmrc" %% "reactivemongo-test" % "4.15.0-play-25" % scope,
  "uk.gov.hmrc" %% "hmrctest" % "3.9.0-play-25" % scope,
  "org.pegdown" % "pegdown" % "1.6.0" % scope,
  "org.scalaj" %% "scalaj-http" % "2.3.0" % scope,
  "com.github.tomakehurst" % "wiremock" % "1.58" % scope,
  "org.scalatestplus.play" %% "scalatestplus-play" % "2.0.1" % scope,
  "org.mockito" %% "mockito-scala-scalatest" % "1.7.1" % scope,
  "com.typesafe.play" %% "play-test" % PlayVersion.current % scope
)

// Temporary Workaround for intermittent (but frequent) failures of Mongo integration tests when running on a Mac
// See Jira story GG-3666 for further information
def tmpMacWorkaround =
  if (sys.props.get("os.name").exists(_.toLowerCase.contains("mac"))) {
    Seq("org.reactivemongo" % "reactivemongo-shaded-native" % "0.16.1-osx-x86-64" % "runtime,test,it")
  } else Seq()

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
    testOptions in Test := Seq(Tests.Filter(unitFilter), Tests.Argument(TestFrameworks.ScalaTest, "-eT")),
    fork in Test := false,
    parallelExecution in Test := false
  )
  .configs(IntegrationTest)
  .settings(inConfig(TemplateItTest)(Defaults.itSettings): _*)
  .settings(
    fork in IntegrationTest := false,
    unmanagedSourceDirectories in IntegrationTest := (baseDirectory in IntegrationTest) (base => Seq(base / "test")).value,
    addTestReportOption(IntegrationTest, "int-test-reports"),
    testGrouping in IntegrationTest := oneForkedJvmPerTest((definedTests in IntegrationTest).value),
    testOptions in IntegrationTest := Seq(Tests.Filter(itFilter), Tests.Argument(TestFrameworks.ScalaTest, "-eT")),
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

def unitFilter(name: String): Boolean = name startsWith "unit"
def itFilter(name: String): Boolean = name startsWith "it"

// Coverage configuration
coverageMinimum := 88
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>;com.kenshoo.play.metrics.*;.*definition.*;prod.*;testOnlyDoNotUseInAppConf.*;app.*;uk.gov.hmrc.BuildInfo"
