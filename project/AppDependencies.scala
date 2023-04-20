import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  lazy val bootstrapVersion = "7.12.0"
  lazy val hmrcMongoVersion = "0.74.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-28"         % bootstrapVersion,
    "com.typesafe.play" %% "play-json"                         % "2.9.2",
    "uk.gov.hmrc"       %% "play-hmrc-api"                     % "7.1.0-play-28",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-28" % hmrcMongoVersion,
    "commons-net"        % "commons-net"                       % "3.6",
    "org.typelevel"     %% "cats-core"                         % "2.9.0",
    "com.github.t3hnar" %% "scala-bcrypt"                      % "4.1",
    "uk.gov.hmrc"       %% "time"                              % "3.25.0",
    "commons-validator"  % "commons-validator"                 % "1.7",
    "uk.gov.hmrc"       %% "internal-auth-client-play-28"      % "1.2.0",
    "uk.gov.hmrc"       %% "api-platform-application-events"   % "0.15.0",
    "uk.gov.hmrc"       %% "api-platform-application-commands" % "0.12.0"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"           %% "bootstrap-test-play-28"   % bootstrapVersion,
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-test-play-28"  % hmrcMongoVersion,
    "org.scalaj"            %% "scalaj-http"              % "2.3.0",
    "com.github.tomakehurst" % "wiremock-jre8-standalone" % "2.27.2",
    "org.mockito"           %% "mockito-scala-scalatest"  % "1.16.42"
  ).map(_ % "test, it")
}
