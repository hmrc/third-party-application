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

package uk.gov.hmrc.thirdpartyapplication.helpers

import org.scalatestplus.mockito.MockitoSugar
import org.slf4j
import play.api.{LoggerLike, MarkerContext}

import scala.collection.mutable.ListBuffer

class StubLogger extends LoggerLike with MockitoSugar {
  override val logger: slf4j.Logger = mock[slf4j.Logger]

  val infoMessages = new ListBuffer[String]()
  val debugMessages = new ListBuffer[String]()
  val warnMessages = new ListBuffer[String]()
  val errorMessages = new ListBuffer[String]()
  val capturedExceptions = new ListBuffer[Throwable]()

  override def info(message: => String)(implicit mc: MarkerContext): Unit = infoMessages += message
  override def debug(message: => String)(implicit mc: MarkerContext): Unit = debugMessages += message
  override def warn(message: => String)(implicit mc: MarkerContext): Unit = warnMessages += message
  override def error(message: => String)(implicit mc: MarkerContext): Unit = errorMessages += message
  override def error(message: => String, throwable: => Throwable)(implicit mc: MarkerContext): Unit = {
    errorMessages += message
    capturedExceptions += throwable
  }
}
