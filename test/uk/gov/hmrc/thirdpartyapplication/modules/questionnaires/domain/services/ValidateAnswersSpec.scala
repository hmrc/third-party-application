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
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.mocks.QuestionBuilder
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import org.scalatest.Inside
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.AsIdsHelpers
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.ValidateAnswers

class ValidateAnswersSpec extends HmrcSpec with Inside with QuestionBuilder with AsIdsHelpers {
  
    import org.scalatest.prop.TableDrivenPropertyChecks._
  
  def answerOf(text: String*) = text.toList
  def noAnswer: List[String] = List.empty
    
  "ValidateAnswers" should {   

    "for acknowledgement questions" in {
      val question = acknowledgementOnly(1)
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validAnswer: AnswerMatching = Right(AcknowledgedAnswer)

      val passes = Table(
        ("description"                              , "question"      , "answer"              , "expects"),
        ("valid answer"                             , question        , noAnswer              , validAnswer),
        ("too many answers"                         , question        , answerOf("Yes")       , aFailure),
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: List[String], expects: AnswerMatching) => 
        expects match {
          case Right(answer) =>
            ValidateAnswers.validate(question, answers) shouldBe Right(answer)
          case Left(()) =>
            ValidateAnswers.validate(question, answers) shouldBe 'Left
        }
      }
    }

    "for single choice questions" in {
      val question = yesNoQuestion(1)
      val optionalQuestion = question.makeOptionalPass
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validAnswer: AnswerMatching = Right(SingleChoiceAnswer("Yes"))
      val validEmptyAnswer: AnswerMatching = Right(NoAnswer)

      val passes = Table(
        ("description"                              , "question"      , "answer"              , "expects"),
        ("valid answer"                             , question        , answerOf("Yes")       , validAnswer),
        ("too many answers"                         , question        , answerOf("Yes", "Bob"), aFailure),
        ("invalid answer"                           , question        , answerOf("Bob")       , aFailure),
        
        ("valid answer on optional"                 , optionalQuestion, answerOf("Yes")       , validAnswer),
        ("invalid answer on optional"               , optionalQuestion, answerOf("Bob")       , aFailure),
        
        ("empty answer is invalid on non optional"  , question        , noAnswer              , aFailure),
        ("empty answer is valid on optional"        , optionalQuestion, noAnswer              , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: List[String], expects: AnswerMatching) => 
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
      val optionalQuestion = question.makeOptionalPass
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validSingleAnswer: AnswerMatching = Right(MultipleChoiceAnswer(Set("One")))
      val validMultiAnswer: AnswerMatching = Right(MultipleChoiceAnswer(Set("One", "Two")))
      val validEmptyAnswer: AnswerMatching = Right(NoAnswer)

      val passes = Table(
        ("description"                              , "question"        , "answer"                  , "expects"),
        ("valid answer"                             , question          , answerOf("One")           , validSingleAnswer),
        ("valid answers"                            , question          , answerOf("One", "Two")    , validMultiAnswer),
        ("invalid answer"                           , question          , answerOf("Zero")          , aFailure),
        ("invalid answers"                          , question          , answerOf("Zero", "Fred")  , aFailure),
        ("mixed validity answers"                   , question          , answerOf("Zero", "One")   , aFailure),
        ("mixed validity answers"                   , question          , answerOf("One", "Zero")   , aFailure),
        
        ("valid answer on optional"                 , optionalQuestion  , answerOf("One")          , validSingleAnswer),
        ("valid answers on optional"                , optionalQuestion  , answerOf("One", "Two")   , validMultiAnswer),
        ("invalid answer on optional"               , optionalQuestion  , answerOf("Zero")         , aFailure),
        ("invalid answers on optional"              , optionalQuestion  , answerOf("Zero", "Fred") , aFailure),
        ("mixes answers on optional"                , optionalQuestion  , answerOf("Zero", "One")  , aFailure),
        ("mixes answers on optional"                , optionalQuestion  , answerOf("One", "Zero")  , aFailure),
        
        ("empty answer is invalid on non optional"  , question          , noAnswer                  , aFailure),
        ("empty answer is valid on optional"        , optionalQuestion  , noAnswer                  , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: List[String], expects: AnswerMatching) => 
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
      val optionalQuestion = question.makeOptionalPass
      type AnswerMatching = Either[Unit, ActualAnswer]
      val aFailure: AnswerMatching = Left(())
      val validAnswer: AnswerMatching = Right(TextAnswer("Bobby"))
      val validEmptyAnswer: AnswerMatching = Right(NoAnswer)

      val passes = Table(
        ("description"                              , "question"      , "answer"                 , "expects"),
        ("valid answer"                             , question        , answerOf("Bobby")        , validAnswer),
        ("too many answers"                         , question        , answerOf("Bobby", "Fred"), aFailure),
        ("valid answer on optional"                 , optionalQuestion, answerOf("Bobby")        , validAnswer),
        
        ("empty answer is invalid on non optional"  , question        , noAnswer                 , aFailure),
        ("empty answer is valid on optional"        , optionalQuestion, noAnswer                 , validEmptyAnswer)
      ) 
      
      forAll(passes) { (_: String, question: Question, answers: List[String], expects: AnswerMatching) => 
        expects match {
          case Right(answer) =>
            ValidateAnswers.validate(question, answers) shouldBe Right(answer)
          case Left(()) =>
            ValidateAnswers.validate(question, answers) shouldBe 'Left
        }
      }
    }
  }
}
