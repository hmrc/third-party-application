/*
 * Copyright 2019 HM Revenue & Customs
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

package common.uk.gov.hmrc.common

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import play.api.LoggerLike

import scala.collection.mutable

import scala.collection.JavaConversions._

class SuppressedLogFilter(val messagesContaining: String) extends Filter[ILoggingEvent] {
  private val suppressedEntries = new mutable.MutableList[ILoggingEvent]()

  override def decide(event: ILoggingEvent): FilterReply = {
    if (event.getMessage.contains(messagesContaining)) {
      suppressedEntries += event
      FilterReply.DENY
    } else {
      FilterReply.NEUTRAL
    }
  }

  def hasError(msg: String) = {
    suppressedEntries.exists(entry => entry.getLevel == Level.ERROR && entry.getMessage.contains(msg))
  }

  def hasWarn(msg: String) = {
    suppressedEntries.exists(entry => entry.getLevel == Level.WARN && entry.getMessage.contains(msg))
  }

  def hasInfo(msg: String) = {
    suppressedEntries.exists(entry => entry.getLevel == Level.INFO && entry.getMessage.contains(msg))
  }
}

trait LogSuppressing {
  def withSuppressedLoggingFrom(logger: Logger, messagesContaining: String)(body: (=> SuppressedLogFilter) => Unit) {

    val appenders = logger.iteratorForAppenders().toList
    val appendersWithFilters = appenders.map(appender => appender->appender.getCopyOfAttachedFiltersList)

    val filter = new SuppressedLogFilter(messagesContaining)
    appenders.foreach(_.addFilter(filter))

    try body(filter)
    finally {
      appendersWithFilters.foreach { case(appender, filters) =>
        appender.clearAllFilters
        filters.foreach(appender.addFilter(_))
      }
    }
  }

  def withSuppressedLoggingFrom(logger: LoggerLike, messagesContaining: String)(body: (=> SuppressedLogFilter) => Unit) {
    withSuppressedLoggingFrom(logger.logger.asInstanceOf[Logger], messagesContaining)(body)
  }
}


