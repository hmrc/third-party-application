import sbt._
import play.core.PlayVersion
import play.sbt.PlayImport._

object AppDependencies {
  def apply() : Seq[ModuleID] = compile ++ test

  val scopes = "test, it"

  lazy val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-26"  % "5.13.0",
    "uk.gov.hmrc"             %% "play-scheduling"            % "7.4.0-play-26",
    "uk.gov.hmrc"             %% "play-json-union-formatter"  % "1.15.0-play-26",
    "com.typesafe.play"       %% "play-json"                  % "2.8.1",
    "com.typesafe.play"       %% "play-json-joda"             % "2.8.1",
    "uk.gov.hmrc"             %% "play-hmrc-api"              % "4.1.0-play-26",
    "uk.gov.hmrc"             %% "metrix"                     % "4.7.0-play-26",
    "org.reactivemongo"       %% "reactivemongo-akkastream"   % "0.18.8",
    "commons-net"             %  "commons-net"                % "3.6",
    "org.typelevel"           %% "cats-core"                  % "2.0.0",
    "com.github.t3hnar"       %% "scala-bcrypt"               % "4.1"
  )

  lazy val test = Seq(
    "uk.gov.hmrc"             %% "reactivemongo-test"         % "4.21.0-play-26",
    "org.pegdown"             %  "pegdown"                    % "1.6.0",
    "org.scalaj"              %% "scalaj-http"                % "2.3.0",
    "com.github.tomakehurst"  %  "wiremock-jre8-standalone"   % "2.27.2",
    "org.scalatestplus.play"  %% "scalatestplus-play"         % "3.1.3",
    "org.mockito"             %% "mockito-scala-scalatest"    % "1.14.0",
    "com.typesafe.play"       %% "play-test"                  % PlayVersion.current
  ).map(_ % scopes)
}
