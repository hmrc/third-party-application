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

package unit.uk.gov.hmrc.thirdpartyapplication.scheduled

import akka.actor.ActorSystem
import play.api.test.FakeApplication
import play.api.test.Helpers.running
import uk.gov.hmrc.thirdpartyapplication.scheduled.Retrying
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

import scala.concurrent.duration._
import scala.concurrent.Future

class RetryingSpec extends AsyncHmrcSpec with Retrying{

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  private implicit val timeout = 1.second

  "retry" should {

    "execute a successful future without delay" in running(FakeApplication()) {

      def successfulFuture: Future[Int] = {
        Future.successful(204)
      }

      val res = await(retry(successfulFuture, delay = 5.seconds, retries = 0))
      res shouldBe 204
    }

    "retry to execute a future in case of failure" in running(FakeApplication()) {

      val retryTimes = 3

      var maxExecutions = retryTimes + 1
      def slowSuccessfulFuture: Future[Int] = {
        maxExecutions -= 1
        if (maxExecutions == 0) {
          Future.successful(maxExecutions)
        } else {
          Future.failed(new RuntimeException)
        }
      }

      val res = await(retry(slowSuccessfulFuture, delay = 20.millis, retries = retryTimes))
      res shouldBe 0
    }

    "retry to execute a future up to `n` times before to throw the error" in running(FakeApplication()) {

      val retryTimes = 5

      var failures = 0
      def failedFuture = {
        failures += 1
        Future.failed(new RuntimeException)
      }

      intercept[RuntimeException] {
        await(retry(failedFuture, delay = 20.millis, retries = retryTimes))
      }

      failures shouldBe retryTimes + 1
    }
  }

}
