import sbt._

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-backend-play-28"          % "5.16.0",
    "uk.gov.hmrc"                 %% "play-json-union-formatter"          % "1.15.0-play-28",
    "com.typesafe.play"           %% "play-json"                          % "2.9.2",
    "com.typesafe.play"           %% "play-json-joda"                     % "2.9.2",
    "uk.gov.hmrc"                 %% "play-hmrc-api"                      % "6.4.0-play-28",
    "uk.gov.hmrc"                 %% "metrix"                             % "5.0.0-play-28",
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-play-28"                 % "0.64.0",
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-work-item-repo-play-28"  % "0.64.0",
    "commons-net"                 %  "commons-net"                        % "3.6",
    "org.typelevel"               %% "cats-core"                          % "2.0.0",
    "com.github.t3hnar"           %% "scala-bcrypt"                       % "4.1",
    "uk.gov.hmrc"                 %% "time"                               % "3.25.0",
    "commons-validator"           %  "commons-validator"                  % "1.7"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                 %% "bootstrap-test-play-28"             % "5.16.0",
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-test-play-28"            % "0.64.0",
    "org.scalaj"                  %% "scalaj-http"                        % "2.3.0",
    "com.github.tomakehurst"      %  "wiremock-jre8-standalone"           % "2.27.2",
    "org.mockito"                 %% "mockito-scala-scalatest"            % "1.16.42"
  ).map(_ % "test, it")
}
