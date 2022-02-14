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

package uk.gov.hmrc.apiplatform.modules.submissions.domain.models

import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import org.joda.time.DateTime
import java.util.UUID
import cats.data.NonEmptyList

sealed trait QuestionnaireState

object QuestionnaireState {
  case object NotStarted extends QuestionnaireState
  case object InProgress extends QuestionnaireState
  case object NotApplicable extends QuestionnaireState
  case object Completed extends QuestionnaireState

  def describe(state: QuestionnaireState): String = state match {
    case NotStarted => "Not Started"
    case InProgress => "In Progress"
    case NotApplicable => "Not Applicable"
    case Completed => "Completed"
  }

  def isCompleted(state: QuestionnaireState): Boolean = state match {
    case NotStarted | InProgress => false
    case _ => true
  }
}

case class QuestionnaireProgress(state: QuestionnaireState, questionsToAsk: List[QuestionId])


case class QuestionIdsOfInterest(
    applicationNameId: QuestionId,
    privacyPolicyUrlId: QuestionId,
    termsAndConditionsUrlId: QuestionId,
    organisationUrlId: QuestionId,
    responsibleIndividualNameId: QuestionId,
    responsibleIndividualEmailId: QuestionId
)

object Submission {
  type AnswersToQuestions = Map[QuestionId, ActualAnswer]

  case class Id(value: String) extends AnyVal

  object Id {
    implicit val format = play.api.libs.json.Json.valueFormat[Id]
    
    def random: Id = Id(UUID.randomUUID().toString())
  }

  val addInstance: (Submission.AnswersToQuestions, Submission.Status) => Submission => Submission = (answers, status) => s => {
    val newInstance = Submission.Instance(s.latestInstance.index+1, answers, NonEmptyList.of(status))
    s.copy(instances = newInstance :: s.instances)
  }
  
  val changeLatestInstance: (Submission.Instance => Submission.Instance) => Submission => Submission = delta => s => {
    s.copy(instances = NonEmptyList(delta(s.instances.head), s.instances.tail))
  }

  val addStatusHistory: (Submission.Status) => Submission => Submission = newStatus => s => {
    require(Submission.Status.isLegalTransition(s.status, newStatus))
    changeLatestInstance(_.copy(statusHistory = newStatus :: s.latestInstance.statusHistory))(s)
  }

  val changeStatusHistory: (Submission.Status => Submission.Status) => Submission => Submission = delta => s => {
    val inStatus = s.latestInstance.statusHistory.head
    val outStatus = delta(inStatus)

    changeLatestInstance(
      _.copy(statusHistory = NonEmptyList(outStatus, s.latestInstance.statusHistory.tail))
    )(s)
  }

  val updateLatestAnswersTo: (Submission.AnswersToQuestions) => Submission => Submission = (newAnswers) => changeLatestInstance(_.copy(answersToQuestions = newAnswers))


  sealed trait Status {
    def isOpenToAnswers = isCreated || isAnswering
    
    def isAnsweredCompletely = this match {
      case Submission.Status.Answering(_, completed) => completed
      case _ => false      
    }


    def isCreated = this match {
      case _ : Submission.Status.Created => true
      case _ => false      
    }

    def isAnswering = this match {
      case _ : Submission.Status.Answering => true
      case _ => false      
    }
    
    def isSubmitted = this match {
      case _ : Submission.Status.Submitted => true
      case _ => false      
    }

    def isGranted = this match {
      case _ : Submission.Status.Granted => true
      case _ => false      
    }
  }

  object Status {
    case class Declined(
      timestamp: DateTime,
      name: String,
      reasons: String
    ) extends Status
    
    case class Granted(
      timestamp: DateTime,
      name: String
    ) extends Status

    case class Submitted(
      timestamp: DateTime,
      requestedBy: String
    ) extends Status

    case class Answering(
      timestamp: DateTime,
      completed: Boolean
    ) extends Status

    case class Created(
      timestamp: DateTime,
      requestedBy: String
    ) extends Status

    def isLegalTransition(from: Submission.Status, to: Submission.Status): Boolean = (from, to) match {
      case (c: Created, a: Answering)         => true
      case (Answering(_, true), s: Submitted) => true
      case (s: Submitted, d: Declined)        => true
      case (s: Submitted, g: Granted)         => true
      case _                                  => false
    }
  }

  case class Instance(
    index: Int,
    answersToQuestions: Submission.AnswersToQuestions,
    statusHistory: NonEmptyList[Submission.Status]
  ) {
    lazy val status: Status = statusHistory.head
    lazy val isOpenToAnswers = status.isOpenToAnswers
  }
}

case class Submission(
  id: Submission.Id,
  applicationId: ApplicationId,
  startedOn: DateTime,
  groups: NonEmptyList[GroupOfQuestionnaires],
  questionIdsOfInterest: QuestionIdsOfInterest,
  instances: NonEmptyList[Submission.Instance]
) {
  lazy val allQuestionnaires: NonEmptyList[Questionnaire] = groups.flatMap(g => g.links)

  lazy val allQuestions: NonEmptyList[Question] = allQuestionnaires.flatMap(l => l.questions.map(_.question))

  def findQuestion(questionId: QuestionId): Option[Question] = allQuestions.find(q => q.id == questionId)

  def findQuestionnaireContaining(questionId: QuestionId): Option[Questionnaire] = 
    allQuestionnaires.find(qn => 
      qn.questions.exists(qi => 
        qi.question.id == questionId
      )
    )

  lazy val latestInstance = instances.head

  lazy val isOpenToAnswers = latestInstance.isOpenToAnswers
  
  lazy val status: Submission.Status = latestInstance.statusHistory.head
}


case class ExtendedSubmission(
  submission: Submission,
  questionnaireProgress: Map[QuestionnaireId, QuestionnaireProgress]
) {
  lazy val isCompleted = 
    questionnaireProgress.values
    .map(_.state)
    .forall(QuestionnaireState.isCompleted)

  lazy val isOpenToAnswers = submission.isOpenToAnswers
  lazy val canBeSubmitted = isOpenToAnswers && isCompleted
  lazy val status: Submission.Status = submission.status
}

case class MarkedSubmission(
  submission: Submission,
  questionnaireProgress: Map[QuestionnaireId, QuestionnaireProgress],
  markedAnswers: Map[QuestionId, Mark]
) {
  lazy val isFail = markedAnswers.values.toList.contains(Fail) | markedAnswers.values.filter(_ == Warn).size >= 4
  lazy val isWarn = markedAnswers.values.toList.contains(Warn)
  lazy val isPass = !isWarn && !isFail
}