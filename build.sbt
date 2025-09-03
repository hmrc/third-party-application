import uk.gov.hmrc.DefaultBuildSettings

lazy val appName = "third-party-application"

Global / bloopAggregateSourceDependencies := true
Global / bloopExportJarClassifiers := Some(Set("sources"))

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / majorVersion := 0
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

lazy val microservice = Project(appName, file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin)
  .settings(ScoverageSettings())
  .settings(
    libraryDependencies ++= AppDependencies(),
    retrieveManaged := true,
    scalacOptions   += "-Wconf:src=routes/.*:s",
    routesImport ++= Seq(
      "uk.gov.hmrc.apiplatform.modules.common.domain.models._",
      "uk.gov.hmrc.apiplatform.modules.applications.core.domain.models._",
      "uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._",
      "uk.gov.hmrc.apiplatform.modules.submissions.domain.models._"
    )
  )
  .settings(
    addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full)
  )
  .settings(
    Test / parallelExecution := false,
    Test / fork              := false,
    Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-eT"),
    Test / unmanagedSourceDirectories ++= Seq(baseDirectory.value / "shared-test"),
    Test / javaOptions += s"-javaagent:${csrCacheDirectory.value.getAbsolutePath}/https/repo1.maven.org/maven2/org/mockito/mockito-core/${AppDependencies.mockitoVersion}/mockito-core-${AppDependencies.mockitoVersion}.jar"
  )
  .settings(
    target := { if (scoverage.ScoverageKeys.coverageEnabled.value) target.value / "coverage" else target.value},
    coverageDataDir := { if (scoverage.ScoverageKeys.coverageEnabled.value) target.value / ".." else target.value},
  )
  
val it = (project in file("it"))
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test")
  .settings(
    name := "integration-tests",
    DefaultBuildSettings.itSettings()
  )

commands ++= Seq(
  Command.command("cleanAll") { state => "clean" :: "it/clean" :: state },
  Command.command("fmtAll") { state => "scalafmtAll" :: "it/scalafmtAll" :: state },
  Command.command("fixAll") { state => "scalafixAll" :: "it/scalafixAll" :: state },
  Command.command("testAll") { state => "test" :: "it/test" :: state },
  Command.command("run-all-tests") { state => "testAll" :: state },
  Command.command("clean-and-test") { state => "cleanAll" :: "compile" :: "run-all-tests" :: state },
  Command.command("pre-commit") { state => "fmtAll" :: "fixAll" :: "coverage" :: "cleanAll" :: "testAll" :: "coverageOff" :: "coverageAggregate" :: state }
)
