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

package unit.uk.gov.hmrc.thirdpartyapplication.services

import com.github.t3hnar.bcrypt._
import uk.gov.hmrc.thirdpartyapplication.models.ClientSecret
import uk.gov.hmrc.thirdpartyapplication.services.{ClientSecretService, ClientSecretServiceConfig}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.DateTimeUtils

class ClientSecretServiceSpec extends AsyncHmrcSpec {

  val fastWorkFactor = 5

  val underTest = new ClientSecretService(ClientSecretServiceConfig(fastWorkFactor))

  "generateClientSecret" should {
    "create new ClientSecret object using UUID for secret value" in {
      val generatedClientSecret = underTest.generateClientSecret()

      generatedClientSecret.id.isEmpty should be (false)
      generatedClientSecret.secret.isEmpty should be (false)
      generatedClientSecret.name.length should be (36)
      generatedClientSecret.name take 32 should be ("•" * 32)
      generatedClientSecret.name.slice(32, 36) should be (generatedClientSecret.secret takeRight 4)

      val hashedSecretCheck = generatedClientSecret.secret.isBcryptedSafe(generatedClientSecret.hashedSecret)
      hashedSecretCheck.isSuccess should be (true)
      hashedSecretCheck.get should be (true)
    }
  }

  "clientSecretIsValid" should {
    val fooSecret = ClientSecret(name = "secret-1", secret = "foo", hashedSecret = "foo".bcrypt(fastWorkFactor))
    val barSecret = ClientSecret(name = "secret-2", secret = "bar", hashedSecret = "bar".bcrypt(fastWorkFactor))
    val bazSecret = ClientSecret(name = "secret-3", secret = "baz", hashedSecret = "baz".bcrypt(fastWorkFactor))

    "return the ClientSecret that matches the provided secret value" in {
      val matchingSecret = await(underTest.clientSecretIsValid("bar", Seq(fooSecret, barSecret, bazSecret)))

      matchingSecret should be (Some(barSecret))
    }

    "return None if the secret value provided does not match" in {
      val matchingSecret = await(underTest.clientSecretIsValid("foobar", Seq(fooSecret, barSecret, bazSecret)))

      matchingSecret should be (None)
    }

  }

  "lastUsedOrdering" should {
    val mostRecent = ClientSecret(name = "secret-1", secret = "foo", hashedSecret = "foo".bcrypt(fastWorkFactor), lastAccess = Some(DateTimeUtils.now))
    val middle = ClientSecret(name = "secret-2", secret = "bar", hashedSecret = "bar".bcrypt(fastWorkFactor), lastAccess = Some(DateTimeUtils.now.minusDays(1)))
    val agesAgo =
      ClientSecret(name = "secret-3", secret = "baz", hashedSecret = "baz".bcrypt(fastWorkFactor), lastAccess = Some(DateTimeUtils.now.minusDays(10)))

    "sort client secrets by most recently used" in {
      val sortedList = List(middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be (mostRecent)
      sortedList(1) should be (middle)
      sortedList(2) should be (agesAgo)
    }

    "sort client secrets with no last used date to the end" in {
      val noLastUsedDate = ClientSecret(name = "secret-1", secret = "foo", hashedSecret = "foo".bcrypt(fastWorkFactor), lastAccess = None)

      val sortedList = List(noLastUsedDate, middle, agesAgo, mostRecent).sortWith(underTest.lastUsedOrdering)

      sortedList.head should be (mostRecent)
      sortedList(1) should be (middle)
      sortedList(2) should be (agesAgo)
      sortedList(3) should be (noLastUsedDate)
    }
  }

  "requiresRehash" should {
    "return true if work factor used on existing hash is different to current configuration" in {
      val hashedSecret = "foo".bcrypt(fastWorkFactor + 1)

      underTest.requiresRehash(hashedSecret) should be (true)
    }

    "return false if work factor used on existing hash matches current configuration" in {
      val hashedSecret = underTest.hashSecret("foo")

      underTest.requiresRehash(hashedSecret) should be (false)
    }
  }

  "workFactorOfHash" should {
    "correctly identify the work factor used to hash a secret" in {
      val workFactor = 6
      val hashedSecret = "foo".bcrypt(workFactor)

      underTest.workFactorOfHash(hashedSecret) should be (workFactor)
    }
  }
}
