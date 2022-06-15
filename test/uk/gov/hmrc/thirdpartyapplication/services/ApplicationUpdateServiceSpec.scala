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

package uk.gov.hmrc.thirdpartyapplication.services

import akka.actor.ActorSystem
import cats.data.{NonEmptyChain, NonEmptyList, Validated}
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.http.HttpResponse
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.thirdpartyapplication.domain.models.UpdateApplicationEvent.NameChanged
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.mocks._
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.services.commands.ChangeProductionApplicationNameCommandHandler
import uk.gov.hmrc.thirdpartyapplication.util._
import uk.gov.hmrc.http.HeaderCarrier

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import uk.gov.hmrc.thirdpartyapplication.mocks.connectors.EmailConnectorMockModule

class ApplicationUpdateServiceSpec
  extends AsyncHmrcSpec
  with BeforeAndAfterAll
  with ApplicationStateUtil
  with ApplicationTestData
  with UpliftRequestSamples
  with FixedClock {

  trait Setup extends AuditServiceMockModule
    with ApplicationRepositoryMockModule
    with EmailConnectorMockModule {

    implicit val hc: HeaderCarrier = HeaderCarrier()

    val actorSystem: ActorSystem = ActorSystem("System")

    val applicationId = ApplicationId.random
    val applicationData = anApplicationData(applicationId)
    val instigator = applicationData.collaborators.head.userId
    val newName = "robs new app"
    val changeName = ChangeProductionApplicationName(instigator, timestamp, gatekeeperUser, newName)

    lazy val locked = false
    protected val mockitoTimeout = 1000
    val response = mock[HttpResponse]

    val mockChangeProductionApplicationNameCommandHandler: ChangeProductionApplicationNameCommandHandler = mock[ChangeProductionApplicationNameCommandHandler]

    val underTest = new ApplicationUpdateService(
      ApplicationRepoMock.aMock,
      mockChangeProductionApplicationNameCommandHandler,
      EmailConnectorMock.aMock
    )
  }

  val timestamp = LocalDateTime.now
  val gatekeeperUser = "gkuser1"

  "update with ChangeProductionApplicationName" should {

    "return the updated application if the application exists" in new Setup {
      val appBefore = anApplicationData(applicationId).copy(name = "old name")
      ApplicationRepoMock.Fetch.thenReturn(appBefore)
      val appAfter = anApplicationData(applicationId).copy(name = "new name")
      ApplicationRepoMock.ApplyEvents.thenReturn(appAfter)
      EmailConnectorMock.SendChangeOfApplicationName.thenReturnSuccess()

      val nameChangedEvent = NameChanged(applicationId, timestamp, instigator, appBefore.name, appAfter.name)

      when(mockChangeProductionApplicationNameCommandHandler.process(*[ApplicationData], *[ChangeProductionApplicationName])).thenReturn(
        Future.successful(Validated.valid(NonEmptyList.one(nameChangedEvent)).toValidatedNec)
      )
      val result = await(underTest.update(applicationId, changeName).value)

      ApplicationRepoMock.ApplyEvents.verifyCalledWith(nameChangedEvent)
      result shouldBe Right(appAfter)
    }

    "return the error if the application does not exist" in new Setup {
      ApplicationRepoMock.Fetch.thenReturnNoneWhen(applicationId)
      val result = await(underTest.update(applicationId, changeName).value)

      result shouldBe Left(NonEmptyChain.one(s"No application found with id $applicationId"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }

    "return error for unknown update types" in new Setup {
      val app = anApplicationData(applicationId)
      ApplicationRepoMock.Fetch.thenReturn(app)

      case class UnknownApplicationUpdate(timestamp: LocalDateTime, instigator: UserId) extends ApplicationUpdate
      val unknownUpdate = UnknownApplicationUpdate(timestamp, instigator)

      val result = await(underTest.update(applicationId, unknownUpdate).value)

      result shouldBe Left(NonEmptyChain.one(s"Unknown ApplicationUpdate type $unknownUpdate"))
      ApplicationRepoMock.ApplyEvents.verifyNeverCalled
    }

  }

}
