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

package uk.gov.hmrc.apiplatform.modules.submissions

import scala.concurrent.ExecutionContext.Implicits.global

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.testkit.NoMaterializer

import play.api.libs.json.{JsError, JsSuccess}
import play.api.test.Helpers._
import play.api.test.{FakeRequest, Helpers}

import uk.gov.hmrc.apiplatform.modules.submissions.controllers.QuestionnairesController
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models._
import uk.gov.hmrc.apiplatform.modules.submissions.mocks.QuestionnaireDAOMockModule
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class QuestionnairesControllerSpec extends AsyncHmrcSpec {
  import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.GroupOfQuestionnaires._
  implicit val mat: Materializer = NoMaterializer

  trait Setup extends QuestionnaireDAOMockModule with SubmissionsTestData {
    val underTest = new QuestionnairesController(QuestionnaireDAOMock.aMock, Helpers.stubControllerComponents())
  }

  "fetch" should {
    "return ok response with questionnaire when found" in new Setup {
      QuestionnaireDAOMock.Fetch.thenReturn(Some(questionnaire))

      val result = underTest.fetch(questionnaireId)(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[Questionnaire] match {
        case JsSuccess(s, _) => succeed
        case JsError(e)      => fail(s"Not parsed as a response $e")
      }
    }

    "return not found when not found" in new Setup {
      QuestionnaireDAOMock.Fetch.thenReturn(None)

      val result = underTest.fetch(questionnaireId)(FakeRequest(GET, "/"))

      status(result) shouldBe BAD_REQUEST
    }
  }

  "active questionnaires groups" should {
    "return ok response" in new Setup {
      QuestionnaireDAOMock.ActiveQuestionnaireGroupings.thenUseStandardOnes()
      val result = underTest.activeQuestionnaires()(FakeRequest(GET, "/"))

      status(result) shouldBe OK
      contentAsJson(result).validate[List[GroupOfQuestionnaires]] match {
        case JsSuccess(s, _) => succeed
        case JsError(e)      => fail(s"Not parsed as a response $e")
      }
    }
  }
}
