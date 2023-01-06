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

import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.util.{AsyncHmrcSpec, FixedClock}
import uk.gov.hmrc.thirdpartyapplication.mocks.repository.ApplicationRepositoryMockModule
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApplicationId

import scala.concurrent.ExecutionContext.Implicits.global

import java.time.LocalDateTime

class ClientSecretServiceSpec extends AsyncHmrcSpec with ApplicationRepositoryMockModule with FixedClock {

  val fastWorkFactor = 4

  val underTest = new ClientSecretService(ApplicationRepoMock.aMock, ClientSecretServiceConfig(fastWorkFactor))

  "generateClientSecret" should {
    "create new ClientSecret object using UUID for secret value" in {
      val generatedClientSecret: (ClientSecret, String) = underTest.generateClientSecret()

      val clientSecret      = generatedClientSecret._1
      val clientSecretValue = generatedClientSecret._2

      clientSecret.id.isEmpty should be(false)
      clientSecret.name should be(clientSecretValue takeRight 4)

      val hashedSecretCheck = clientSecretValue.isBcryptedSafe(clientSecret.hashedSecret)
      hashedSecretCheck.isSuccess should be(true)
      hashedSecretCheck.get should be(true)
    }
  }

  "clientSecretIsValid" should {
    val applicationId = ApplicationId.random
    val fooSecret     = ClientSecret(name = "secret-1", createdOn = LocalDateTime.now(clock), hashedSecret = "foo".bcrypt(fastWorkFactor))
    val barSecret     = ClientSecret(name = "secret-2", createdOn = LocalDateTime.now(clock), hashedSecret = "bar".bcrypt(fastWorkFactor))
    val bazSecret     = ClientSecret(name = "secret-3", createdOn = LocalDateTime.now(clock), hashedSecret = "baz".bcrypt(fastWorkFactor))

    "return the ClientSecret that matches the provided secret value" in {
      val matchingSecret = await(underTest.clientSecretIsValid(applicationId, "bar", Seq(fooSecret, barSecret, bazSecret)))

      matchingSecret should be(Some(barSecret))
      ApplicationRepoMock.verifyZeroInteractions()
    }

    "return the ClientSecret that matches the provided secret value and rehash it if the work factor has changed" in {
      val secretWithDifferentWorkFactor = ClientSecret(name = "secret-4", createdOn = LocalDateTime.now(clock), hashedSecret = "different-work-factor".bcrypt(fastWorkFactor + 1))

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
    val mostRecent = ClientSecret(name = "secret-1", hashedSecret = "foo".bcrypt(fastWorkFactor), lastAccess = Some(LocalDateTime.now(clock)))
    val middle     = ClientSecret(name = "secret-2", hashedSecret = "bar".bcrypt(fastWorkFactor), lastAccess = Some(LocalDateTime.now(clock) minusDays (1)))
    val agesAgo    = ClientSecret(name = "secret-3", hashedSecret = "baz".bcrypt(fastWorkFactor), lastAccess = Some(LocalDateTime.now(clock).minusDays(10)))

    "sort client secrets by most recently used" in {
      val sortedList = List(middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be(mostRecent)
      sortedList(1) should be(middle)
      sortedList(2) should be(agesAgo)
    }

    "sort client secrets with no last used date to the end" in {
      val noLastUsedDate = ClientSecret(name = "secret-1", createdOn = LocalDateTime.now(clock), hashedSecret = "foo".bcrypt(fastWorkFactor), lastAccess = None)

      val sortedList = List(noLastUsedDate, middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be(mostRecent)
      sortedList(1) should be(middle)
      sortedList(2) should be(agesAgo)
      sortedList(3) should be(noLastUsedDate)
    }
  }

  "requiresRehash" should {
    "return true if work factor used on existing hash is different to current configuration" in {
      val hashedSecret = "foo".bcrypt(fastWorkFactor + 1)

      underTest.requiresRehash(hashedSecret) should be(true)
    }

    "return false if work factor used on existing hash matches current configuration" in {
      val hashedSecret = underTest.hashSecret("foo")

      underTest.requiresRehash(hashedSecret) should be(false)
    }
  }

  "workFactorOfHash" should {
    "correctly identify the work factor used to hash a secret" in {
      val workFactor   = 6
      val hashedSecret = "foo".bcrypt(workFactor)

      underTest.workFactorOfHash(hashedSecret) should be(workFactor)
    }
  }
}
