/*
 * Copyright 2023 HM Revenue & Customs
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

import cats.Apply
import cats.syntax.option._

import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.services.ApplicationLogger
import uk.gov.hmrc.apiplatform.modules.applications.common.domain.models.FullName
import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

object SubmissionDataExtracter extends ApplicationLogger {

  private def getTextQuestionOfInterest(submission: Submission, questionId: Question.Id) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, ActualAnswer.NoAnswer)
    actualAnswer match {
      case ActualAnswer.TextAnswer(answer) => Some(answer)
      case _                               => None
    }
  }

  private def getSingleChoiceQuestionOfInterest(submission: Submission, questionId: Question.Id) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, ActualAnswer.NoAnswer)
    actualAnswer match {
      case ActualAnswer.SingleChoiceAnswer(answer) => Some(answer)
      case _                                       => None
    }
  }

  private def getMultiChoiceQuestionOfInterest(submission: Submission, questionId: Question.Id): Option[Set[String]] = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, ActualAnswer.NoAnswer)
    actualAnswer match {
      case ActualAnswer.MultipleChoiceAnswer(answers) => Some(answers)
      case _                                          => None
    }
  }

  def getApplicationName(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.applicationNameId)
  }

  def getOrganisationUrl(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.organisationUrlId)
  }

  private def getAnswerForYesOrNoResponsibleIndividualIsRequester(submission: Submission): Option[String] = {
    val responsibleIndividualIsRequesterId = submission.questionIdsOfInterest.responsibleIndividualIsRequesterId
    getSingleChoiceQuestionOfInterest(submission, responsibleIndividualIsRequesterId)
  }

  def isRequesterTheResponsibleIndividual(submission: Submission) = getAnswerForYesOrNoResponsibleIndividualIsRequester(submission).contains("Yes")

  def getResponsibleIndividualName(submission: Submission, requestedByName: String): Option[FullName] = {
    getAnswerForYesOrNoResponsibleIndividualIsRequester(submission).flatMap(_ match {
      case "Yes" => Some(FullName(requestedByName))
      case "No"  => getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualNameId).map(FullName(_))
    })
  }

  def getResponsibleIndividualEmail(submission: Submission, requestedByEmailAddress: String): Option[String] = {
    getAnswerForYesOrNoResponsibleIndividualIsRequester(submission).flatMap(_ match {
      case "Yes" => Some(requestedByEmailAddress)
      case "No"  => getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualEmailId)
    })
  }

  def getServerLocations(submission: Submission): Option[Set[ServerLocation]] =
    getMultiChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.serverLocationsId)
      .map(_.map(text =>
        text match {
          case "In the UK"                                   => ServerLocation.InUK
          case "In the European Economic Area (EEA)"         => ServerLocation.InEEA
          case "Outside the EEA with adequacy agreements"    => ServerLocation.OutsideEEAWithAdequacy
          case "Outside the EEA with no adequacy agreements" => ServerLocation.OutsideEEAWithoutAdequacy
          case s                                             => throw new RuntimeException(s)
        }
      ))

  def getTermsAndConditionsLocation(submission: Submission): Option[TermsAndConditionsLocation] = {
    val yesNoOrDesktop   = getSingleChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.termsAndConditionsId)
    lazy val urlIfChosen = getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.termsAndConditionsUrlId)

    yesNoOrDesktop.flatMap(_ match {
      case "Yes"                                              => urlIfChosen.map(TermsAndConditionsLocations.Url(_))
      case "No"                                               => TermsAndConditionsLocations.NoneProvided.some
      case "The terms and conditions are in desktop software" => TermsAndConditionsLocations.InDesktopSoftware.some
    })
  }

  def getPrivacyPolicyLocation(submission: Submission): Option[PrivacyPolicyLocation] = {
    val yesNoOrDesktop   = getSingleChoiceQuestionOfInterest(submission, submission.questionIdsOfInterest.privacyPolicyId)
    lazy val urlIfChosen = getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.privacyPolicyUrlId)

    yesNoOrDesktop.flatMap(_ match {
      case "Yes"                                       => urlIfChosen.map(PrivacyPolicyLocations.Url(_))
      case "No"                                        => PrivacyPolicyLocations.NoneProvided.some
      case "The privacy policy is in desktop software" => PrivacyPolicyLocations.InDesktopSoftware.some
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

    Apply[Option].map4(responsibleIndividualName, responsibleIndividualEmail, termsAndConditionsLocation, privacyPolicyLocation) {
      case (name, email, tnc, pp) =>
        ImportantSubmissionData(organisationUrl, ResponsibleIndividual(name, email.toLaxEmail), serverLocations, tnc, pp, List.empty)
    }
  }
}
