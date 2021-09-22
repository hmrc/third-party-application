/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.repository

import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, MetricsHelper}

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import akka.stream.Materializer
import play.api.test.NoMaterializer
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId
import uk.gov.hmrc.time.DateTimeUtils
import scala.collection.immutable.ListMap

class AnswersToQuestionnaireDAOSpec
  extends AsyncHmrcSpec
    with MongoSpecSupport
    with BeforeAndAfterEach with BeforeAndAfterAll
    {

  implicit var m : Materializer = NoMaterializer

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val answersRepo = new AnswersRepository(reactiveMongoComponent)

  private val dao = new AnswersToQuestionnaireDAO(answersRepo)

  trait Setup {
    
    val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire
    val referenceId = ReferenceId.random
    val applicationId = ApplicationId.random
  }

  override def beforeEach() {
    List(answersRepo).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(answersRepo).foreach { db =>
      await(db.drop)
    }
  }

  "save" should {

    "???" in new Setup {
      val result = dao.create(applicationId, questionnaire.id)

      val fetchResult = await(result.flatMap(refId => dao.fetch(refId))).value

      fetchResult.applicationId shouldBe applicationId
      fetchResult.questionnaireId shouldBe questionnaire.id
      fetchResult.answers shouldBe 'empty
    }
  }
}
