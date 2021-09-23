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
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories._
import akka.stream.Materializer
import play.api.test.NoMaterializer
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

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
  val questionnaire = QuestionnaireDAO.Questionnaires.DevelopmentPractices.questionnaire

  trait Setup {
    val referenceId = ReferenceId.random
    val applicationId = ApplicationId.random

    def checkResult(in: AnswersToQuestionnaire) = {
      in.applicationId shouldBe applicationId
      in.questionnaireId shouldBe questionnaire.id
    }
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

  "create overwrite and fetch" should {
    val aQuestion: YesNoQuestion = questionnaire.questions.head.question.asInstanceOf[YesNoQuestion]
    val firstQuestionId = aQuestion.id
    val firstQuestionAnswer = SingleChoiceAnswer("Yes")

    "return the latest" in new Setup {
      val createResult = dao.create(applicationId, questionnaire.id)

      val fetchResult1: AnswersToQuestionnaire = await(createResult.flatMap(refId => dao.fetch(refId))).value

      val answerAQuestion = fetchResult1.copy(answers = fetchResult1.answers + (firstQuestionId -> firstQuestionAnswer))

      val saveResult = dao.save(answerAQuestion)

      val fetchResult2: AnswersToQuestionnaire = await(saveResult.flatMap(atq => dao.fetch(atq.referenceId))).value

      checkResult(fetchResult1)
      checkResult(fetchResult2)
      
      fetchResult1.answers shouldBe 'empty
      val startedOn1 = fetchResult1.startedOn
      
      fetchResult2.answers.size shouldBe 1
      val startedOn2 = fetchResult2.startedOn
      startedOn1.getMillis shouldBe startedOn2.getMillis
    }
  }
}
