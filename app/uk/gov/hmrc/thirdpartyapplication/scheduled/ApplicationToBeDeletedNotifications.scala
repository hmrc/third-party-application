package uk.gov.hmrc.thirdpartyapplication.scheduled

import javax.inject.Inject
import org.joda.time.DateTime
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

class ApplicationIsToBeDeletedNotification @Inject()(configuration: Configuration,
                                                     applicationRepository: ApplicationRepository,
                                                     mongo: ReactiveMongoComponent)
  extends TimedJob("ApplicationToBeDeletedNotifications", configuration, mongo) {

  val notificationJobConfig = configuration.underlying.as[ApplicationToBeDeletedNotificationsConfig](name)
  val deleteJobConfig = configuration.underlying.as[DeleteUnusedApplicationsConfig]("DeleteUnusedApplications")

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = ???
}

case class ApplicationToBeDeletedNotificationsConfig(notifyWhenUnusedFor: FiniteDuration, dryRun: Boolean) {
  def notificationCutoffDate: DateTime = DateTime.now.minus(notifyWhenUnusedFor.toMillis)
}