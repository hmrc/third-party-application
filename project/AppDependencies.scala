import sbt._
import org.apache.ivy.core.module.descriptor.ExcludeRule

object AppDependencies {
  def apply(): Seq[ModuleID] = compileDeps ++ testDeps

  private val bootstrapVersion         = "10.8.0"
  private val hmrcMongoVersion         = "2.13.0"

  private val commonDomainVersion = "1.4.0"
  private val appEventsVersion    = "1.3.0"
  private val appDomainVersion    = "1.6.0"

  private lazy val compileDeps = Seq(
    "uk.gov.hmrc"                   %% "bootstrap-backend-play-30"                % bootstrapVersion,
    "uk.gov.hmrc.mongo"             %% "hmrc-mongo-work-item-repo-play-30"        % hmrcMongoVersion,
    "commons-net"                    % "commons-net"                              % "3.6",
    "com.github.t3hnar"             %% "scala-bcrypt"                             % "4.1",
    "commons-validator"              % "commons-validator"                        % "1.7",
    "uk.gov.hmrc"                   %% "internal-auth-client-play-30"             % "3.1.0",
    "org.typelevel"                 %% "cats-core"                                % "2.13.0",
    "com.iheart"                    %% "ficus"                                    % "1.5.2",
    "uk.gov.hmrc"                   %% "api-platform-common-domain"               % commonDomainVersion,
    "uk.gov.hmrc"                   %% "api-platform-application-domain"          % appDomainVersion,
    "uk.gov.hmrc"                   %% "api-platform-application-events"          % appEventsVersion
  )

  private lazy val testDeps = Seq(
    "uk.gov.hmrc"                   %% "bootstrap-test-play-30"                   % bootstrapVersion,
    "uk.gov.hmrc.mongo"             %% "hmrc-mongo-test-play-30"                  % hmrcMongoVersion,
    "com.softwaremill.sttp.client3" %% "core"                                     % "3.9.8",
    "com.vladsch.flexmark"           % "flexmark-all"                             % "0.62.2",
    "uk.gov.hmrc"                   %% "api-platform-common-domain-fixtures"      % commonDomainVersion,
    "uk.gov.hmrc"                   %% "api-platform-application-domain-fixtures" % appDomainVersion
  ).map(_ % Test)
}
