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

package uk.gov.hmrc.apiplatform.modules.submissions.controllers

import play.api.mvc.PathBindable

import uk.gov.hmrc.apiplatform.modules.applications.submissions.domain.models.SubmissionId
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

package object binders {

  implicit def submissionIdPathBinder(implicit textBinder: PathBindable[String]): PathBindable[SubmissionId] = new PathBindable[SubmissionId] {

    private def submissionIdFromString(text: String): Either[String, SubmissionId] = {
      SubmissionId.apply(text).toRight(s"Cannot accept $text as SubmissionId")
    }

    override def bind(key: String, value: String): Either[String, SubmissionId] = {
      textBinder.bind(key, value).flatMap(submissionIdFromString(_))
    }

    override def unbind(key: String, submissionId: SubmissionId): String = {
      submissionId.value.toString()
    }
  }

  implicit def questionnaireIdPathBinder(implicit textBinder: PathBindable[String]): PathBindable[Questionnaire.Id] = new PathBindable[Questionnaire.Id] {

    override def bind(key: String, value: String): Either[String, Questionnaire.Id] = {
      textBinder.bind(key, value).map(Questionnaire.Id(_))
    }

    override def unbind(key: String, questionnaireId: Questionnaire.Id): String = {
      questionnaireId.value
    }
  }

  implicit def questionIdPathBinder(implicit textBinder: PathBindable[String]): PathBindable[Question.Id] = new PathBindable[Question.Id] {

    override def bind(key: String, value: String): Either[String, Question.Id] = {
      textBinder.bind(key, value).map(Question.Id(_))
    }

    override def unbind(key: String, questionId: Question.Id): String = {
      questionId.value
    }
  }
}
