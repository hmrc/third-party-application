/*
 * Copyright 2018 HM Revenue & Customs
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

import java.util.UUID

import com.google.inject._
import play.api.Logger
import uk.gov.hmrc.models.ApplicationData
import uk.gov.hmrc.repository.ApplicationRepository

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class RemoveSageTermsAgreement @Inject()(applicationRepository: ApplicationRepository) {

//  val applicationId: UUID = UUID.fromString("ab349380-17cc-4de0-a7ac-c76baedd7133") // QA - API Platform
  val applicationId: UUID = UUID.fromString("33575d00-95cb-4e36-97b0-949d99b0a081") // Prod - Sage Application

  val logPrefix = "RemoveSageTermsAgreement"

  Logger.warn(s"$logPrefix: Starting process for clearing Terms of Use agreement for application ID $applicationId")

  applicationRepository.fetch(applicationId).map {
    case Some(app: ApplicationData) =>
      Logger.warn(s"$logPrefix: Retrieved application with the name ${app.name}")
      val updatedApp = app.copy(checkInformation = app.checkInformation.map(ci => ci.copy(termsOfUseAgreements = Seq.empty)))
      applicationRepository.save(updatedApp) recover recovery
      Logger.warn(s"$logPrefix: Successfully updated application ${updatedApp.id} to remove the terms of use agreement")
    case _ => Logger.error(s"$logPrefix: Application $applicationId was not found")
  } recover recovery

  private def recovery: PartialFunction[Throwable, Unit] = {
    case e: Throwable => Logger.error(s"$logPrefix: An unexpected error occurred: ${e.getMessage}", e)
  }
}
