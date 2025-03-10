import sbt._
import org.apache.ivy.core.module.descriptor.ExcludeRule

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  lazy val bootstrapVersion         = "9.7.0"
  lazy val hmrcMongoVersion         = "2.4.0"
  lazy val applicationEventVersion  = "0.79.0" // Ensure this version of the application-events library uses the appDomainVersion below
  lazy val applicationDomainVersion = "0.75.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                   %% "bootstrap-backend-play-30"                % bootstrapVersion,
    "uk.gov.hmrc.mongo"             %% "hmrc-mongo-work-item-repo-play-30"        % hmrcMongoVersion,
    "commons-net"                    % "commons-net"                              % "3.6",
    "com.github.t3hnar"             %% "scala-bcrypt"                             % "4.1",
    "commons-validator"              % "commons-validator"                        % "1.7",
    "uk.gov.hmrc"                   %% "internal-auth-client-play-30"             % "3.0.0",
    "uk.gov.hmrc"                   %% "api-platform-application-events"          % applicationEventVersion exclude("uk.gov.hmrc","api-platform-application-domain"),
    "uk.gov.hmrc"                   %% "api-platform-application-domain"          % applicationDomainVersion,
    "com.iheart"                    %% "ficus"                                    % "1.5.2"
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                   %% "bootstrap-test-play-30"                   % bootstrapVersion,
    "uk.gov.hmrc.mongo"             %% "hmrc-mongo-test-play-30"                  % hmrcMongoVersion,
    "com.softwaremill.sttp.client3" %% "core"                                     % "3.9.8",
    "org.mockito"                   %% "mockito-scala-scalatest"                  % "1.17.29",
    "com.vladsch.flexmark"           % "flexmark-all"                             % "0.62.2",
    "uk.gov.hmrc"                   %% "api-platform-application-domain-fixtures" % applicationDomainVersion
  ).map(_ % "test")
}
