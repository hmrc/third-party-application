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
  import cats.data.NonEmptyList
  import uk.gov.hmrc.time.DateTimeUtils

  object AnswerQuestion {
    import Submission.AnswersToQuestions

    private def fromOption[A](opt: Option[A], msg: String): Either[String,A] = opt.fold[Either[String,A]](Left(msg))(v => Right(v))

    private def cond[A](cond: => Boolean, ok: A, msg: String): Either[String, A] = if(cond) Right(ok) else Left(msg)

    def questionsToAsk(questionnaire: Questionnaire, context: AskWhen.Context, answersToQuestions: AnswersToQuestions): List[QuestionId] = {
      questionnaire.questions.collect {
        case (qi) if AskWhen.shouldAsk(context, answersToQuestions)(qi.askWhen) => qi.question.id
      }
    }

    def recordAnswer(submission: Submission, questionId: QuestionId, rawAnswers: List[String]): Either[String, ExtendedSubmission] = {
      for {
        question                        <- fromOption(submission.findQuestion(questionId), "Not valid for this submission")
        context                          = submission.context
        validatedAnswers                <- ValidateAnswers.validate(question, rawAnswers)
        latestInstance                   = submission.latestInstance

        updatedAnswersToQuestions       <- cond(latestInstance.isOpenToAnswers, latestInstance.answersToQuestions + (questionId -> validatedAnswers), "Answers cannot be recorded for a Submission that is not in progress")
        // we assume no recursion needed for the next 3 steps - otherwise the ask when question structure must have been implemented in a complex recursive mess
        updatedQuestionnaireProgress     = deriveProgressOfQuestionnaires(submission.allQuestionnaires, context, updatedAnswersToQuestions)
        areQuestionsAnswered             = updatedQuestionnaireProgress.values
                                            .map(_.state)
                                            .forall(QuestionnaireState.isCompleted)
        questionsThatShouldBeAsked       = updatedQuestionnaireProgress.flatMap(_._2.questionsToAsk).toList
        finalAnswersToQuestions          = updatedAnswersToQuestions.filter { case (qid, _) => questionsThatShouldBeAsked.contains(qid) }
        updatedSubmission                = updateSubmissionState(finalAnswersToQuestions, areQuestionsAnswered, submission)
      } yield ExtendedSubmission(updatedSubmission, updatedQuestionnaireProgress)
    }

    def updateSubmissionState(answers: Submission.AnswersToQuestions, areQuestionsAnswered: Boolean, submission: Submission): Submission = {
      import Submission._

      val addAnsweringStatus = addStatusHistory(Submission.Status.Answering(DateTimeUtils.now, areQuestionsAnswered))
      (addAnsweringStatus andThen updateLatestAnswersTo(answers))(submission)
    }

    def deriveProgressOfQuestionnaire(questionnaire: Questionnaire, context: AskWhen.Context, answersToQuestions: AnswersToQuestions): QuestionnaireProgress = {
      val questionsToAsk = AnswerQuestion.questionsToAsk(questionnaire, context, answersToQuestions)
      val (answeredQuestions, unansweredQuestions) = questionsToAsk.partition(answersToQuestions.contains)
      val state = (unansweredQuestions.headOption, answeredQuestions.nonEmpty) match {
        case (None, true)       => QuestionnaireState.Completed
        case (None, false)      => QuestionnaireState.NotApplicable
        case (_, true)          => QuestionnaireState.InProgress
        case (_, false)         => QuestionnaireState.NotStarted
      }

      QuestionnaireProgress(state, questionsToAsk)
    }
      
    def deriveProgressOfQuestionnaires(questionnaires: NonEmptyList[Questionnaire], context: AskWhen.Context, answersToQuestions: AnswersToQuestions): Map[QuestionnaireId, QuestionnaireProgress] = {
      questionnaires.toList.map(q => (q.id -> deriveProgressOfQuestionnaire(q, context, answersToQuestions))).toMap
    }
  }
