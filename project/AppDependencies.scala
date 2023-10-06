import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  lazy val bootstrapVersion = "7.15.0"
  lazy val hmrcMongoVersion = "0.74.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"       %% "bootstrap-backend-play-28"         % bootstrapVersion,
    "uk.gov.hmrc"       %% "play-hmrc-api"                     % "7.2.0-play-28",
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-work-item-repo-play-28" % hmrcMongoVersion,
    "commons-net"        % "commons-net"                       % "3.6",
    "com.github.t3hnar" %% "scala-bcrypt"                      % "4.1",
    "commons-validator"  % "commons-validator"                 % "1.7",
    "uk.gov.hmrc"       %% "internal-auth-client-play-28"      % "1.4.0",
    "uk.gov.hmrc"       %% "api-platform-application-events"   % "0.32.0",
    "uk.gov.hmrc"       %% "api-platform-application-domain"   % "0.21.0"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"           %% "bootstrap-test-play-28"   % bootstrapVersion,
    "uk.gov.hmrc.mongo"     %% "hmrc-mongo-test-play-28"  % hmrcMongoVersion,
    "org.scalaj"            %% "scalaj-http"              % "2.4.2",
    "com.github.tomakehurst" % "wiremock-jre8-standalone" % "2.33.2",
    "org.mockito"           %% "mockito-scala-scalatest"  % "1.17.4",
    "org.scalatest"         %% "scalatest"                % "3.2.16",
    "com.vladsch.flexmark"   % "flexmark-all"             % "0.62.2"
  ).map(_ % "test, it")
}
