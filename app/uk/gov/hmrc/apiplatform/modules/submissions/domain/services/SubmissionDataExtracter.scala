/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.services

import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ImportantSubmissionData
import uk.gov.hmrc.thirdpartyapplication.domain.models.ResponsibleIndividual
import cats.Apply
import uk.gov.hmrc.thirdpartyapplication.domain.models.ServerLocation
import uk.gov.hmrc.thirdpartyapplication.domain.models.TermsAndConditionsLocation
import uk.gov.hmrc.thirdpartyapplication.domain.models.PrivacyPolicyLocation
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger

object SubmissionDataExtracter extends ApplicationLogger {
  private def getTextQuestionOfInterest(submission: Submission, questionId: Question.Id) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, NoAnswer)
    actualAnswer match {
      case TextAnswer(answer) => Some(answer)
      case _                  => None
    }
  }

  private def getSingleChoiceQuestionOfInterest(submission: Submission, questionId: Question.Id) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, NoAnswer)
    actualAnswer match {
      case SingleChoiceAnswer(answer) => Some(answer)
      case _                          => None
    }
  }
  private def getMultiChoiceQuestionOfInterest(submission: Submission, questionId: Question.Id): Option[Set[String]] = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, NoAnswer)
    actualAnswer match {
      case MultipleChoiceAnswer(answers) => Some(answers)
      case _                             => None
    }
  }

  def getApplicationName(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.applicationNameId)
  }

  def getOrganisationUrl(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.organisationUrlId)
  }

  def getResponsibleIndividualName(submission: Submission, requestedByName: String): Option[ResponsibleIndividual.Name] = {
    val responsibleIndividualIsRequesterId = submission.questionIdsOfInterest.responsibleIndividualIsRequesterId
    val yesOrNoResponsibleIndividualIsRequester = getSingleChoiceQuestionOfInterest(submission, responsibleIndividualIsRequesterId)

    yesOrNoResponsibleIndividualIsRequester.flatMap(_ match {
      case "Yes" => Some(ResponsibleIndividual.Name(requestedByName))
      case "No" => getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualNameId).map(ResponsibleIndividual.Name)
    })
  }

  def getResponsibleIndividualEmail(submission: Submission, requestedByEmailAddress: String): Option[ResponsibleIndividual.EmailAddress] = {
    val responsibleIndividualIsRequesterId = submission.questionIdsOfInterest.responsibleIndividualIsRequesterId
    val yesOrNoResponsibleIndividualIsRequester = getSingleChoiceQuestionOfInterest(submission, responsibleIndividualIsRequesterId)

    yesOrNoResponsibleIndividualIsRequester.flatMap(_ match {
      case "Yes" => Some(ResponsibleIndividual.EmailAddress(requestedByEmailAddress))
      case "No" => getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualEmailId).map(ResponsibleIndividual.EmailAddress)
    })
  }

  def getServerLocations(submission: Submission): Option[Set[ServerLocation]] =
    getMultiChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.serverLocationsId)
    .map( _.map( text => text match {
      case "In the UK" => ServerLocation.InUK
      case "In the European Economic Area (EEA)" => ServerLocation.InEEA
      case "Outside the EEA with adequacy agreements" => ServerLocation.OutsideEEAWithAdequacy
      case "Outside the EEA with no adequacy agreements" => ServerLocation.OutsideEEAWithoutAdequacy
      case s => throw new RuntimeException()
    }))

  def getTermsAndConditionsLocation(submission: Submission): Option[TermsAndConditionsLocation] = {
    import cats.implicits._
    val yesNoOrDesktop = getSingleChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.termsAndConditionsId)
    lazy val urlIfChosen = getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.termsAndConditionsUrlId)

    yesNoOrDesktop.flatMap( _ match {
      case "Yes" => urlIfChosen.map(TermsAndConditionsLocation.Url(_))
      case "No" => TermsAndConditionsLocation.NoneProvided.some
      case "The terms and conditions are in desktop software" => TermsAndConditionsLocation.InDesktopSoftware.some
    })
  }

  def getPrivacyPolicyLocation(submission: Submission): Option[PrivacyPolicyLocation] = {
    import cats.implicits._
    val yesNoOrDesktop = getSingleChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.privacyPolicyId)
    lazy val urlIfChosen = getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.privacyPolicyUrlId)

    yesNoOrDesktop.flatMap( _ match {
      case "Yes" => urlIfChosen.map(PrivacyPolicyLocation.Url(_))
      case "No" => PrivacyPolicyLocation.NoneProvided.some
      case "The privacy policy is in desktop software" => PrivacyPolicyLocation.InDesktopSoftware.some
    })
  }

  def getImportantSubmissionData(submission: Submission, requestedByName: String, requestedByEmailAddress: String): Option[ImportantSubmissionData] = {
    val organisationUrl            = getOrganisationUrl(submission)
    val responsibleIndividualName  = getResponsibleIndividualName(submission, requestedByName)
    val responsibleIndividualEmail = getResponsibleIndividualEmail(submission, requestedByEmailAddress)
    val serverLocations            = getServerLocations(submission).getOrElse(Set.empty)
    val termsAndConditionsLocation = getTermsAndConditionsLocation(submission)
    val privacyPolicyLocation      = getPrivacyPolicyLocation(submission)

    logger.debug(s"Organisation url $organisationUrl")
    logger.debug(s"responsibleIndividualName $responsibleIndividualName")
    logger.debug(s"responsibleIndividualEmail $responsibleIndividualEmail")
    logger.debug(s"serverLocations $serverLocations")
    logger.debug(s"termsAndConditionsLocation $termsAndConditionsLocation")
    logger.debug(s"privacyPolicyLocation $privacyPolicyLocation")

    import cats.implicits._
    Apply[Option].map4(responsibleIndividualName, responsibleIndividualEmail, termsAndConditionsLocation, privacyPolicyLocation) {
      case (name, email, tnc, pp) => 
        ImportantSubmissionData(organisationUrl, ResponsibleIndividual(name, email), serverLocations, tnc, pp, List.empty)
    }
  }
}
