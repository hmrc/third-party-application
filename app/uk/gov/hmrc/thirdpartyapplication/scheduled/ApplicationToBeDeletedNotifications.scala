/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.thirdpartyapplication.scheduled

import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.inject.{Inject, Singleton}
import net.ceedubs.ficus.Ficus._
import org.joda.time.{DateTime, LocalDate}
import play.api.{Configuration, LoggerLike}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.thirdpartyapplication.connector.{EmailConnector, ThirdPartyDeveloperConnector}
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationToBeDeletedNotifications @Inject()(configuration: Configuration,
                                                    applicationRepository: ApplicationRepository,
                                                    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
                                                    emailConnector: EmailConnector,
                                                    mongo: ReactiveMongoComponent)
  extends TimedJob("ApplicationToBeDeletedNotifications", configuration, mongo) {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  val notificationJobConfig: ApplicationToBeDeletedNotificationsConfig = configuration.underlying.as[ApplicationToBeDeletedNotificationsConfig](name)
  val deleteJobConfig: DeleteUnusedApplicationsConfig = configuration.underlying.as[DeleteUnusedApplicationsConfig]("DeleteUnusedApplications")

  val notifier: Notifier =
    if(notificationJobConfig.dryRun) {
      new DryRunNotifier(notificationJobConfig.environmentName, logger)
    } else {
      new EmailNotifier(notificationJobConfig.environmentName, deleteJobConfig.deleteApplicationsIfUnusedFor, emailConnector)
    }

  override def functionToExecute()(implicit ec: ExecutionContext): Future[RunningOfJobSuccessful] = {
    val cutoffDate: DateTime =
      DateTime.now
        .minus(deleteJobConfig.deleteApplicationsIfUnusedFor.toMillis)
        .plus(notificationJobConfig.sendNotificationsInAdvance.toMillis)

    for {
      applicationsToNotify <- applicationRepository.applicationsRequiringDeletionPendingNotification(cutoffDate)
      notifications <- Future.sequence(applicationsToNotify.map(toNotification))
      notificationResults <- Future.sequence(notifications.map(sendNotification))
      _ <- Future.sequence(notificationResults.map(recordNotificationSent))
    } yield ()

    Future.successful(RunningOfJobSuccessful)
  }

  def toNotification(application: (UUID, String, DateTime, Set[String])): Future[ApplicationToBeDeletedNotification] = {
    val deletionDate: LocalDate = application._3.plus(deleteJobConfig.deleteApplicationsIfUnusedFor.toMillis).toLocalDate

    usersToNotify(application._4)
      .map(users => ApplicationToBeDeletedNotification(application._1, application._2, application._3, deletionDate, users))
  }

  def usersToNotify(emailAddresses: Set[String]): Future[Seq[(String, String, String)]] = {
    for {
      userDetails <- thirdPartyDeveloperConnector.fetchUsersByEmailAddresses(emailAddresses)
      validatedUsers = userDetails.filter(_.validated)
      usersToNotify = validatedUsers.map(user => (user.email, user.firstName, user.lastName))
    } yield usersToNotify
  }

  def sendNotification(notification: ApplicationToBeDeletedNotification): Future[(UUID, NotificationResult)] =
    notifier.notifyApplicationToBeDeleted(notification).map((notification.applicationId, _))

  def recordNotificationSent(notificationResult: (UUID, NotificationResult)): Future[Unit] = {
    if (notificationJobConfig.dryRun) {
      Future.successful()
    } else {
      notificationResult._2 match {
        case NotificationSent => applicationRepository.recordDeleteNotificationSent(notificationResult._1).map(_ => Future.successful())
        case NotificationFailed =>
          logger.warn(s"Sending notification failed for Application [${notificationResult._1}]")
          Future.successful()
      }
    }
  }

}

case class ApplicationToBeDeletedNotificationsConfig(sendNotificationsInAdvance: FiniteDuration,
                                                     emailServiceURL: String,
                                                     emailTemplateId: String,
                                                     environmentName: String,
                                                     dryRun: Boolean)

case class ApplicationToBeDeletedNotification(applicationId: UUID, applicationName: String, lastUsedDate: DateTime, deletionDate: LocalDate, users: Seq[(String, String, String)]) {
  def lastUsedInWords: String = FiniteDuration(DateTime.now.getMillis - lastUsedDate.getMillis, TimeUnit.MILLISECONDS).toDays + " days"
  def deletionDateInWords: String = deletionDate.toString("d MMMM yyyy")
}

trait NotificationResult
object NotificationSent extends NotificationResult
object NotificationFailed extends NotificationResult

trait Notifier {
  val environmentName: String
  def notifyApplicationToBeDeleted(applicationToBeDeletedNotification: ApplicationToBeDeletedNotification)
                                  (implicit ec: ExecutionContext): Future[NotificationResult]
}

class DryRunNotifier(override val environmentName: String, logger: LoggerLike) extends Notifier {
  def redact(text: String): String = s"${text.head}${"*" * (text.length - 2)}${text.last}"
  def redactEmail(emailAddress: String): String = {
    val emailSegments = emailAddress.split('@')
    s"${redact(emailSegments(0))}@${redact(emailSegments(1))}"
  }
  def redactedUserList(users: Seq[(String, String, String)]): String =
    users
      .map(user => s"${redact(user._2)} ${redact(user._3)} (${redactEmail(user._1)})")
      .mkString(", ")

  override def notifyApplicationToBeDeleted(notification: ApplicationToBeDeletedNotification)
                                           (implicit ec: ExecutionContext): Future[NotificationResult] = {
    logger.info(
      s"[DryRun] Would have sent notification that application [${notification.applicationName}] in environment [$environmentName] has not been used for [${notification.lastUsedInWords}] and would be deleted on [${notification.deletionDateInWords}] to users [${redactedUserList(notification.users)}]")
    Future.successful(NotificationSent)
  }
}

class EmailNotifier(override val environmentName: String, deletionPeriod: FiniteDuration, val emailConnector: EmailConnector) extends Notifier {
  override def notifyApplicationToBeDeleted(notification: ApplicationToBeDeletedNotification)
                                           (implicit ec: ExecutionContext): Future[NotificationResult] = {
    implicit val hc: HeaderCarrier = HeaderCarrier()

    Future.sequence(notification.users.map { user =>
      emailConnector.sendApplicationToBeDeletedNotification(
        user._1,
        user._2,
        user._3,
        notification.applicationName,
        environmentName,
        notification.lastUsedInWords,
        deletionPeriod.toString,
        notification.deletionDateInWords)
    })
      .map((responses: Seq[HttpResponse]) => responses.filter(_.status < 400))
      .map(f => if (f.isEmpty) NotificationFailed else NotificationSent) // If at least one email has been sent consider the notification sent
  }
}