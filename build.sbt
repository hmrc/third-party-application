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

lazy val playJsonVersion = "2.7.3"
lazy val akkaVersion     = "2.5.23"
lazy val akkaHttpVersion = "10.0.15"

lazy val compile = Seq(
  "uk.gov.hmrc" %% "bootstrap-play-26" % "1.8.0",
  "uk.gov.hmrc" %% "play-scheduling" % "7.4.0-play-26",
  "uk.gov.hmrc" %% "play-json-union-formatter" % "1.11.0",
  "com.typesafe.play" %% "play-json" % playJsonVersion,
  "com.typesafe.play" %% "play-json-joda" % playJsonVersion,
  "uk.gov.hmrc" %% "play-hmrc-api" % "4.1.0-play-26",
  "uk.gov.hmrc" %% "metrix" % "4.4.0-play-26",
  "uk.gov.hmrc" %% "simple-reactivemongo" % "7.26.0-play-26",
  "org.reactivemongo" %% "reactivemongo-akkastream" % reactiveMongoVer,
  "commons-net" % "commons-net" % "3.6",
  "org.typelevel" %% "cats-core" % "2.0.0",
  "com.github.t3hnar" %% "scala-bcrypt" % "4.1",

  "com.typesafe.akka" %% "akka-stream"    % akkaVersion     force(),
  "com.typesafe.akka" %% "akka-protobuf"  % akkaVersion     force(),
  "com.typesafe.akka" %% "akka-slf4j"     % akkaVersion     force(),
  "com.typesafe.akka" %% "akka-actor"     % akkaVersion     force(),
  "com.typesafe.akka" %% "akka-http-core" % akkaHttpVersion force()
)
val scope = "test,it"

lazy val test = Seq(
  "uk.gov.hmrc" %% "reactivemongo-test" % "4.19.0-play-26" % scope,
  "org.pegdown" % "pegdown" % "1.6.0" % scope,
  "org.scalaj" %% "scalaj-http" % "2.3.0" % scope,
  "com.github.tomakehurst" % "wiremock" % "1.58" % scope,
  "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.3" % scope,
  "org.mockito" %% "mockito-scala-scalatest" % "1.14.0" % scope,
  "com.typesafe.play" %% "play-test" % PlayVersion.current % scope
)

// Temporary Workaround for intermittent (but frequent) failures of Mongo integration tests when running on a Mac
// See Jira story GG-3666 for further information
def tmpMacWorkaround =
  if (sys.props.get("os.name").exists(_.toLowerCase.contains("mac"))) {
    Seq("org.reactivemongo" % "reactivemongo-shaded-native" % "0.16.1-osx-x86-64" % "runtime,test,it")
  } else Seq()

lazy val plugins: Seq[Plugins] = Seq(PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory)
lazy val playSettings: Seq[Setting[_]] = Seq.empty

lazy val microservice = (project in file("."))
  .enablePlugins(plugins: _*)
  .settings(playSettings: _*)
  .settings(scalaSettings: _*)
  .settings(publishingSettings: _*)
  .settings(defaultSettings(): _*)
  .settings(
    name := appName,
    scalaVersion := "2.12.11",
    libraryDependencies ++= appDependencies,
    retrieveManaged := true,
    routesGenerator := InjectedRoutesGenerator,
    majorVersion := 0
  )
  .settings(playPublishingSettings: _*)
  .settings(
    testOptions in Test := Seq(Tests.Filter(unitFilter), Tests.Argument(TestFrameworks.ScalaTest, "-eT")),
    fork in Test := false,
    parallelExecution in Test := false
  )
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    fork in IntegrationTest := false,
    unmanagedSourceDirectories in IntegrationTest := (baseDirectory in IntegrationTest) (base => Seq(base / "test")).value,
    addTestReportOption(IntegrationTest, "int-test-reports"),
    testGrouping in IntegrationTest := oneForkedJvmPerTest((definedTests in IntegrationTest).value),
    testOptions in IntegrationTest := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-eT")),
    parallelExecution in IntegrationTest := false)
  .settings(
    resolvers += Resolver.jcenterRepo
  )
  .settings(scalacOptions ++= Seq("-deprecation", "-feature", "-Ypartial-unification"))

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

// Coverage configuration
coverageMinimum := 89
coverageFailOnMinimum := true
coverageExcludedPackages := "<empty>;com.kenshoo.play.metrics.*;.*definition.*;prod.*;testOnlyDoNotUseInAppConf.*;app.*;uk.gov.hmrc.BuildInfo"
