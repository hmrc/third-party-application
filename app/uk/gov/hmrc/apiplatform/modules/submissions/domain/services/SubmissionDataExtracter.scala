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

object SubmissionDataExtracter {
  private def getTextQuestionOfInterest(submission: Submission, questionId: QuestionId) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, NoAnswer)
    actualAnswer match {
      case TextAnswer(answer) => Some(answer)
      case _                  => None
    }
  }

  private def getSingleChoiceQuestionOfInterest(submission: Submission, questionId: QuestionId) = {
    val actualAnswer: ActualAnswer = submission.latestInstance.answersToQuestions.getOrElse(questionId, NoAnswer)
    actualAnswer match {
      case SingleChoiceAnswer(answer) => Some(answer)
      case _                          => None
    }
  }

  def getApplicationName(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.applicationNameId)
  }

  private def getUrlOrDesktopText(firstQuestionId: QuestionId, urlValueQuestionId: QuestionId)(submission: Submission): Option[String] = {
    getSingleChoiceQuestionOfInterest(submission, firstQuestionId).flatMap(answer => {
      answer match {
        case "Yes" => getTextQuestionOfInterest(submission, urlValueQuestionId)
        case "No" => None
        case _ => Some("desktop")
      }
    })
  }

  def getPrivacyPolicyUrl(submission: Submission) = getUrlOrDesktopText(
    submission.questionIdsOfInterest.privacyPolicyId, submission.questionIdsOfInterest.privacyPolicyUrlId)(submission)

  def getTermsAndConditionsUrl(submission: Submission) = getUrlOrDesktopText(
    submission.questionIdsOfInterest.termsAndConditionsId, submission.questionIdsOfInterest.termsAndConditionsUrlId)(submission)

  def getOrganisationUrl(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.organisationUrlId)
  }

  def getResponsibleIndividualName(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualNameId)
  }

  def getResponsibleIndividualEmail(submission: Submission): Option[String] = {
    getTextQuestionOfInterest(submission, submission.questionIdsOfInterest.responsibleIndividualEmailId)
  }}
