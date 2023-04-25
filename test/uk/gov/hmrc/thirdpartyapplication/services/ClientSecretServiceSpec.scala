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

package uk.gov.hmrc.thirdpartyapplication.services

import scala.concurrent.ExecutionContext.Implicits.global

import com.github.t3hnar.bcrypt._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import uk.gov.hmrc.apiplatform.modules.applications.domain.models.{ApplicationId, ClientSecretsHashingConfig}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecretData
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class ClientSecretServiceSpec extends AsyncHmrcSpec with ApplicationRepositoryMockModule with FixedClock {

  val myWorkFactor   = 5
  val config: Config = ConfigFactory.empty().withValue("application-domain-lib.client-secrets-hashing.work-factor", ConfigValueFactory.fromAnyRef(myWorkFactor))
  val hashConfig     = ClientSecretsHashingConfig(config)

  val underTest = new ClientSecretService(hashConfig, ApplicationRepoMock.aMock, FixedClock.clock)


  "clientSecretIsValid" should {
    val applicationId = ApplicationId.random
    val fooSecret     = ClientSecretData(name = "secret-1", createdOn = now, hashedSecret = "foo".bcrypt(myWorkFactor))
    val barSecret     = ClientSecretData(name = "secret-2", createdOn = now, hashedSecret = "bar".bcrypt(myWorkFactor))
    val bazSecret     = ClientSecretData(name = "secret-3", createdOn = now, hashedSecret = "baz".bcrypt(myWorkFactor))

    "return the ClientSecret that matches the provided secret value" in {
      val matchingSecret = await(underTest.clientSecretIsValid(applicationId, "bar", Seq(fooSecret, barSecret, bazSecret)))

      matchingSecret should be(Some(barSecret))
      ApplicationRepoMock.verifyZeroInteractions()
    }

    "return the ClientSecret that matches the provided secret value and rehash it if the work factor has changed" in {
      val secretWithDifferentWorkFactor = ClientSecretData(name = "secret-4", createdOn = now, hashedSecret = "different-work-factor".bcrypt(myWorkFactor - 1))

      ApplicationRepoMock.UpdateClientSecretHash.thenReturn(applicationId, secretWithDifferentWorkFactor.id)(mock[ApplicationData])

      val matchingSecret =
        await(underTest.clientSecretIsValid(applicationId, "different-work-factor", Seq(fooSecret, barSecret, bazSecret, secretWithDifferentWorkFactor)))

      matchingSecret should be(Some(secretWithDifferentWorkFactor))
      ApplicationRepoMock.UpdateClientSecretHash.verifyCalledWith(applicationId, secretWithDifferentWorkFactor.id)
    }

    "return None if the secret value provided does not match" in {
      val matchingSecret = await(underTest.clientSecretIsValid(applicationId, "foobar", Seq(fooSecret, barSecret, bazSecret)))

      matchingSecret should be(None)
      ApplicationRepoMock.verifyZeroInteractions()
    }
  }

  "lastUsedOrdering" should {
    val mostRecent = ClientSecretData(name = "secret-1", hashedSecret = "foo", lastAccess = Some(now))
    val middle     = ClientSecretData(name = "secret-2", hashedSecret = "bar", lastAccess = Some(now.minusDays(1)))
    val agesAgo    = ClientSecretData(name = "secret-3", hashedSecret = "baz", lastAccess = Some(now.minusDays(10)))

    "sort client secrets by most recently used" in {
      val sortedList = List(middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be(mostRecent)
      sortedList(1) should be(middle)
      sortedList(2) should be(agesAgo)
    }

    "sort client secrets with no last used date to the end" in {
      val noLastUsedDate = ClientSecretData(name = "secret-1", createdOn = now, hashedSecret = "foo", lastAccess = None)

      val sortedList = List(noLastUsedDate, middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be(mostRecent)
      sortedList(1) should be(middle)
      sortedList(2) should be(agesAgo)
      sortedList(3) should be(noLastUsedDate)
    }
  }

  "requiresRehash" should {
    "return true if work factor used on existing hash is different to current configuration" in {
      val hashedSecret = "foo".bcrypt(myWorkFactor - 1)

      underTest.requiresRehash(hashedSecret) should be(true)
    }

    "return false if work factor used on existing hash matches current configuration" in {
      val hashedSecret = underTest.timedHashSecret("foo")

      underTest.requiresRehash(hashedSecret) should be(false)
    }

    "return false if work factor used on existing hash is better than current configuration" in {
      val hashedSecret = "foo".bcrypt(myWorkFactor + 1)

      underTest.requiresRehash(hashedSecret) should be(false)
    }
  }

  "workFactorOfHash" should {
    "correctly identify the work factor used to hash a secret" in {
      val expectedWorkFactor = 5
      val hashedSecret       = underTest.timedHashSecret("foo")

      underTest.workFactorOfHash(hashedSecret) should be(expectedWorkFactor)
    }
  }
}
