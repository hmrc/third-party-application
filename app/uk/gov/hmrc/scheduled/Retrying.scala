/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.scheduled

import akka.pattern.after
import play.api.libs.concurrent.Akka
import play.api.Play.current

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

// Taken from https://gist.github.com/viktorklang/9414163

object Retrying {

  def retry[T](f: => Future[T], delay: FiniteDuration, retries: Int = 0): Future[T] = {
    f recoverWith {
      case _: RuntimeException if retries > 0 =>
        after(delay, Akka.system.scheduler)(retry(f, delay, retries - 1))
    }
  }

}
