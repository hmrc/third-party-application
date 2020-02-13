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
import play.api.Configuration
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
    } yield RunningOfJobSuccessful
  }

  def toNotification(application: (UUID, String, DateTime, Set[String])): Future[ApplicationToBeDeletedNotification] =
    for {
      userDetails <- thirdPartyDeveloperConnector.fetchUsersByEmailAddresses(application._4)
      validatedUsers = userDetails.filter(_.validated)
      usersToNotify = validatedUsers.map(user => (user.email, user.firstName, user.lastName))
    } yield
      ApplicationToBeDeletedNotification(
        application._1,
        application._2,
        application._3,
        application._3.plus(deleteJobConfig.deleteApplicationsIfUnusedFor.toMillis).toLocalDate,
        usersToNotify)

  def sendNotification(notification: ApplicationToBeDeletedNotification): Future[(UUID, NotificationResult)] =
    if (notificationJobConfig.dryRun) writeNotificationsToLog(notification) else sendEmailNotifications(notification)

  def writeNotificationsToLog(notification: ApplicationToBeDeletedNotification): Future[(UUID, NotificationResult)] = {
    def redact(text: String): String = s"${text.head}${"*" * (text.length - 2)}${text.last}"
    def redactEmail(emailAddress: String): String = {
      val emailSegments = emailAddress.split('@')
      s"${redact(emailSegments(0))}@${redact(emailSegments(1))}"
    }
    def redactedUserList(users: Seq[(String, String, String)]): String =
      users
        .map(user => s"${redact(user._2)} ${redact(user._3)} (${redactEmail(user._1)})")
        .mkString(", ")

    logger.info(
      s"[DryRun] Would have sent notification that application [${notification.applicationName}] in environment [${notificationJobConfig.environmentName}] has not been used for [${notification.lastUsedInWords}] and would be deleted on [${notification.deletionDateInWords}] to users [${redactedUserList(notification.users)}]")
    Future.successful((notification.applicationId, NotificationSent))
  }

  def sendEmailNotifications(notification: ApplicationToBeDeletedNotification): Future[(UUID, NotificationResult)] = {
    Future.sequence(notification.users.map { user =>
      emailConnector.sendApplicationToBeDeletedNotification(
        user._1,
        user._2,
        user._3,
        notification.applicationName,
        notificationJobConfig.environmentName,
        notification.lastUsedInWords,
        deleteJobConfig.deleteApplicationsIfUnusedFor.toString,
        notification.deletionDateInWords)
    })
      .map((responses: Seq[HttpResponse]) => responses.filter(_.status < 400))
      .map(f => if (f.isEmpty) (notification.applicationId, NotificationFailed) else (notification.applicationId, NotificationSent)) // If at least one email has been sent consider the notification sent
  }

  def recordNotificationSent(notificationResult: (UUID, NotificationResult)): Future[Unit] = {
    if (notificationJobConfig.dryRun) {
      Future.successful()
    } else {
      notificationResult._2 match {
        case NotificationSent => applicationRepository.recordDeleteNotificationSent(notificationResult._1).map(_ => Future.successful(()))
        case NotificationFailed =>
          logger.warn(s"Sending notification failed for Application [${notificationResult._1}]")
          Future.successful(())
      }
    }
  }
}

case class ApplicationToBeDeletedNotificationsConfig(sendNotificationsInAdvance: FiniteDuration,
                                                     emailServiceURL: String,
                                                     emailTemplateId: String,
                                                     environmentName: String,
                                                     dryRun: Boolean)

case class ApplicationToBeDeletedNotification(applicationId: UUID,
                                              applicationName: String,
                                              lastUsedDate: DateTime,
                                              deletionDate: LocalDate,
                                              users: Seq[(String, String, String)]) {
  def lastUsedInWords: String = FiniteDuration(DateTime.now.getMillis - lastUsedDate.getMillis, TimeUnit.MILLISECONDS).toDays + " days"
  def deletionDateInWords: String = deletionDate.toString("d MMMM yyyy")
}

trait NotificationResult
object NotificationSent extends NotificationResult
object NotificationFailed extends NotificationResult
