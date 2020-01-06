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

package it.uk.gov.hmrc.thirdpartyapplication

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import common.uk.gov.hmrc.thirdpartyapplication.testutils.ApplicationStateUtil
import it.uk.gov.hmrc.thirdpartyapplication.repository.IndexVerification
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Matchers}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.config.DropSandboxIndex
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.ExecutionContext.Implicits.global

class DropSandboxIndexSpec extends UnitSpec
  with MongoSpecSupport with BeforeAndAfterEach with BeforeAndAfterAll with MockitoSugar with ArgumentMatchersSugar with Matchers with IndexVerification with ApplicationStateUtil {

  implicit val s : ActorSystem = ActorSystem("test")
  implicit val m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  val indexName = "sandboxTokenClientIdIndex"
  val index = Index(key = Seq("tokens.production.clientId" -> Ascending), name = Some(indexName), unique = true, background = true)

  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent) {
    override def indexes = index +: super.indexes
  }

  override def beforeEach() {
    applicationRepository.drop
    applicationRepository.ensureIndexes
  }

  override protected def afterAll() {
    applicationRepository.drop
  }

  "Drop Sandbox Index" should {

    "drop the sandboxTokenClientIdIndex when it exists" in {

      new DropSandboxIndex(applicationRepository)

      verifyIndexDoesNotExist(applicationRepository, indexName)
    }

    "return normally when subsequently called after dropping the index" in {

      new DropSandboxIndex(applicationRepository)
      new DropSandboxIndex(applicationRepository)

      verifyIndexDoesNotExist(applicationRepository, indexName)
    }

  }
}
