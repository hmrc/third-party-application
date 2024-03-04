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

import play.api.libs.json._

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._

class AnswerQuestionsJsonFormatterSpec extends HmrcSpec {

  "Can format" should {
    "work for text answers" in {
      val answer: ActualAnswer = ActualAnswer.TextAnswer("Bobby")
      val text                 = Json.prettyPrint(Json.toJson(answer))
      text shouldBe """{
                      |  "value" : "Bobby",
                      |  "answerType" : "text"
                      |}""".stripMargin

      val obj = Json.parse(text).as[ActualAnswer]
      answer shouldBe obj
    }

    "work for single choice answers" in {
      val answer: ActualAnswer = ActualAnswer.SingleChoiceAnswer("Bobby")
      val text                 = Json.prettyPrint(Json.toJson(answer))
      text shouldBe """{
                      |  "value" : "Bobby",
                      |  "answerType" : "singleChoice"
                      |}""".stripMargin
    }

    "work for multiple choice answers" in {
      val answer: ActualAnswer = ActualAnswer.MultipleChoiceAnswer(Set("Bobby", "Freddy"))
      val text                 = Json.prettyPrint(Json.toJson(answer))
      text shouldBe """{
                      |  "values" : [ "Bobby", "Freddy" ],
                      |  "answerType" : "multipleChoice"
                      |}""".stripMargin
    }

    "work for AcknowledgedAnswer" in {
      val answer: ActualAnswer = ActualAnswer.AcknowledgedAnswer
      Json.prettyPrint(Json.toJson(answer)) shouldBe """{
                                                       |  "answerType" : "acknowledged"
                                                       |}""".stripMargin
    }

    "work for NoAnswers" in {
      val answer: ActualAnswer = ActualAnswer.NoAnswer
      Json.prettyPrint(Json.toJson(answer)) shouldBe """{
                                                       |  "answerType" : "noAnswer"
                                                       |}""".stripMargin
    }
  }
}
