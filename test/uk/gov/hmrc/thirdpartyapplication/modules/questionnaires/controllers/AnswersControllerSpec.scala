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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires

import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.controllers.AnswersController
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.mocks.AnswersServiceMockModule
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories.QuestionnaireDAO
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import play.api.test.Helpers
import scala.collection.immutable.ListMap
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.libs.json.JsSuccess
import play.api.libs.json.Json
import uk.gov.hmrc.time.DateTimeUtils
import play.api.test.NoMaterializer
import AnswersController._

class AnswersControllerSpec extends AsyncHmrcSpec {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswersToQuestionnaireFrontendJsonFormatters._
  implicit val mat = NoMaterializer
  
  trait Setup extends AnswersServiceMockModule {
    val underTest = new AnswersController(AnswersServiceMock.aMock, Helpers.stubControllerComponents())

    val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
    val referenceId = ReferenceId.random
    val applicationId = ApplicationId.random
    val answers = AnswersToQuestionnaire(referenceId, questionnaire.id, applicationId, DateTimeUtils.now, ListMap.empty)
  }

  "fetch" should {
    implicit val readsFetchResponse = Json.reads[FetchResponse]
  
    "return an ok response when found" in new Setup {
      AnswersServiceMock.Fetch.thenReturn(questionnaire, answers)

      val result = underTest.fetch(referenceId)(FakeRequest())

      status(result) shouldBe OK
      contentAsJson(result).validate[FetchResponse] match {
        case JsSuccess(x,_) => 
          x.answers shouldBe answers
          x.questionnaire shouldBe questionnaire
        case _ => fail("Not parsed as a response")
      }
    }
      
    "return a bad request when not found" in new Setup {
      AnswersServiceMock.Fetch.thenFails("Test Error")

      val result = underTest.fetch(referenceId)(FakeRequest())

      status(result) shouldBe BAD_REQUEST
    }
  }

  "raise" should {
    implicit val writesRaiseRequest = Json.writes[RaiseRequest]
    implicit val readsRaiseResponse = Json.reads[RaiseResponse]
    
    "return an ok response" in new Setup {
      AnswersServiceMock.RaiseQuestionnaire.thenReturn(referenceId)
      
      val jsonBody = Json.toJson(RaiseRequest(applicationId, questionnaire.id))
      val result = underTest.raise().apply(FakeRequest(POST, "/").withBody(jsonBody))

      status(result) shouldBe OK
      contentAsJson(result).validate[RaiseResponse] match {
        case JsSuccess(x,_) => 
          x.referenceId shouldBe referenceId
        case _ => fail("Not parsed as a response")        
      }

    }

    "return a bad request response" in new Setup {
      AnswersServiceMock.RaiseQuestionnaire.thenFails("Test Error")
      
      val jsonBody = Json.toJson(RaiseRequest(applicationId, questionnaire.id))
      val result = underTest.raise()(FakeRequest(POST, "/").withBody(jsonBody))

      status(result) shouldBe BAD_REQUEST
    }
  }
}
