/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

import uk.gov.hmrc.thirdpartyapplication.util.HmrcSpec
import cats.data.NonEmptyList
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.QuestionBuilder
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.AsIdsHelpers
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.ValidateAnswers

class ValidateAnswersSpec extends HmrcSpec with Inside with QuestionBuilder with AsIdsHelpers {
  
    import org.scalatest.prop.TableDrivenPropertyChecks._
  
  def answerOf(text: String*) = Some(NonEmptyList(text.toList.head, text.toList.tail))
  def noAnswer: Option[NonEmptyList[String]] = None
    
  "ValidateAnswers" should {   
    
    "for single choice questions" in {
      val question = yesNoQuestion(1)
      val optionalQuestion = question.makeOptional
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validAnswer: AnswerMatching = Right(SingleChoiceAnswer("Yes"))
      val validOptionalAnswer: AnswerMatching = Right(OptionalAnswer(Some(SingleChoiceAnswer("Yes"))))
      val validEmptyAnswer: AnswerMatching = Right(OptionalAnswer(None))

      val passes = Table(
        ("description"                              , "question"      , "answer"              , "expects"),
        ("valid answer"                             , question        , answerOf("Yes")       , validAnswer),
        ("valid first answer"                       , question        , answerOf("Yes", "Bob"), validAnswer),
        ("invalid answer"                           , question        , answerOf("Bob")       , aFailure),
        ("invalid first answer"                     , question        , answerOf("Bob", "Yes"), aFailure),
        ("empty answer is invalid on non optional"  , question        , noAnswer              , aFailure),
                  
        ("valid answer on optional"                 , optionalQuestion, answerOf("Yes")       , validOptionalAnswer),
        ("valid first answer on optional"           , optionalQuestion, answerOf("Yes", "Bob"), validOptionalAnswer),
        ("invalid answer on optional"               , optionalQuestion, answerOf("Bob")       , aFailure),
        ("invalid first answer on optional"         , optionalQuestion, answerOf("Bob", "Yes"), aFailure),
        ("empty answer is valid on optional"        , optionalQuestion, noAnswer              , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: Option[NonEmptyList[String]], expects: AnswerMatching) => 
        expects match {
          case Right(answer) =>
            ValidateAnswers.validate(question, answers) shouldBe Right(answer)
          case Left(()) =>
            ValidateAnswers.validate(question, answers) shouldBe 'Left
        }
      }
    }

    "for multi choice questions" in {
      val question = multichoiceQuestion(1, "One", "Two", "Three")
      val optionalQuestion = question.makeOptional
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validSingleAnswer: AnswerMatching = Right(MultipleChoiceAnswer(Set("One")))
      val validOptionalSingleAnswer: AnswerMatching = Right(OptionalAnswer(Some(MultipleChoiceAnswer(Set("One")))))
      val validMultiAnswer: AnswerMatching = Right(MultipleChoiceAnswer(Set("One", "Two")))
      val validOptionalMultiAnswer: AnswerMatching = Right(OptionalAnswer(Some(MultipleChoiceAnswer(Set("One", "Two")))))
      val validEmptyAnswer: AnswerMatching = Right(OptionalAnswer(None))

      val passes = Table(
        ("description"                              , "question"        , "answer"                  , "expects"),
        ("valid answer"                             , question          , answerOf("One")           , validSingleAnswer),
        ("valid answers"                            , question          , answerOf("One", "Two")    , validMultiAnswer),
        ("invalid answer"                           , question          , answerOf("Zero")          , aFailure),
        ("invalid answers"                          , question          , answerOf("Zero", "Fred")  , aFailure),
        ("mixes answers"                            , question          , answerOf("Zero", "One")   , aFailure),
        ("mixes answers"                            , question          , answerOf("One", "Zero")   , aFailure),
        ("empty answer is invalid on non optional"  , question          , noAnswer                  , aFailure),

        ("valid answer on optional"                 , optionalQuestion  , answerOf("One")          , validOptionalSingleAnswer),
        ("valid answers on optional"                , optionalQuestion  , answerOf("One", "Two")   , validOptionalMultiAnswer),
        ("invalid answer on optional"               , optionalQuestion  , answerOf("Zero")         , aFailure),
        ("invalid answers on optional"              , optionalQuestion  , answerOf("Zero", "Fred") , aFailure),
        ("mixes answers on optional"                , optionalQuestion  , answerOf("Zero", "One")  , aFailure),
        ("mixes answers on optional"                , optionalQuestion  , answerOf("One", "Zero")  , aFailure),
          
        ("empty answer is valid on optional"        , optionalQuestion  , noAnswer                  , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: Option[NonEmptyList[String]], expects: AnswerMatching) => 
        expects match {
          case Right(answer) =>
            ValidateAnswers.validate(question, answers) shouldBe Right(answer)
          case Left(()) =>
            ValidateAnswers.validate(question, answers) shouldBe 'Left
        }
      }
    }

    "for text questions" in {
      val question = textQuestion(1)
      val optionalQuestion = question.makeOptional
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validAnswer: AnswerMatching = Right(TextAnswer("Bobby"))
      val validOptionalAnswer: AnswerMatching = Right(OptionalAnswer(Some(TextAnswer("Bobby"))))
      val validEmptyAnswer: AnswerMatching = Right(OptionalAnswer(None))

      val passes = Table(
        ("description"                              , "question"      , "answer"                 , "expects"),
        ("valid answer"                             , question        , answerOf("Bobby")        , validAnswer),
        ("valid first answer"                       , question        , answerOf("Bobby", "Fred"), validAnswer),
        ("empty answer is invalid on non optional"  , question        , noAnswer                 , aFailure),
                  
        ("valid answer on optional"                 , optionalQuestion, answerOf("Bobby")        , validOptionalAnswer),
        ("valid first answer on optional"           , optionalQuestion, answerOf("Bobby", "Fred"), validOptionalAnswer),
        ("empty answer is valid on optional"        , optionalQuestion, noAnswer                 , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: Option[NonEmptyList[String]], expects: AnswerMatching) => 
        expects match {
          case Right(answer) =>
            ValidateAnswers.validate(question, answers) shouldBe Right(answer)
          case Left(()) =>
            ValidateAnswers.validate(question, answers) shouldBe 'Left
        }
      }
    }
  }

  "validateAnswersWhenNonOptional" can {   
    import ValidateAnswers.validateNonOptional 
    
    "for single choice questions" should {
      val question = yesNoQuestion(1)

      "return 'right(answer) when the first answer is valid" in {
        validateNonOptional(question, NonEmptyList.of("Yes")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      "return 'right(answer) when the first answer is valid regardless of subsequent answers" in {
        validateNonOptional(question, NonEmptyList.of("Yes", "blah")).right.value shouldBe SingleChoiceAnswer("Yes")
      }
      
      "return 'left when the first answer is invalid" in {
        validateNonOptional(question, NonEmptyList.of("Yodel")) shouldBe 'left
      }

      "return 'left when the first answer is invalid even when subsequent answers are correct" in {
        validateNonOptional(question, NonEmptyList.of("Yodel", "Yes")) shouldBe 'left
      }
    }

    "call validateNonOptional for multiple choice questions" should {
      val question = multichoiceQuestion(1, "one","two", "three")

      "return 'right(answers) when all answers are valid" in {
        validateNonOptional(question, NonEmptyList.of("two", "three")).right.value shouldBe MultipleChoiceAnswer(Set("two", "three"))
      }

      "return 'left when not all answers are valid" in {
        validateNonOptional(question, NonEmptyList.of("two", "three", "yodel")) shouldBe 'left
      }
    }

    "call validateNonOptional for text question" should {
      val question = textQuestion(1)

      "return 'right when an answer is given" in {
        validateNonOptional(question, NonEmptyList.of("answered")).right.value shouldBe TextAnswer("answered")
      }
      
      "return 'left when the answer is blank" in {
        validateNonOptional(question, NonEmptyList.of("")) shouldBe 'left
      }
    }
  }
}
