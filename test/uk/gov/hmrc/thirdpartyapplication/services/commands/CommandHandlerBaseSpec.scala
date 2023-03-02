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

package uk.gov.hmrc.thirdpartyapplication.services.commands

import uk.gov.hmrc.thirdpartyapplication.util.{ApplicationTestData, AsyncHmrcSpec}

trait CommandHandlerBaseSpec 
    extends AsyncHmrcSpec
    with ApplicationTestData
    with CommandActorExamples
    with CommandApplicationExamples {
  
  def checkFailsWith(msg: String, msgs: String*)(fn: => CommandHandler.ResultT) = {
    val testThis = await(fn.value).left.value.toNonEmptyList.toList

    testThis should have length 1 + msgs.length
    testThis.head shouldBe CommandFailures.GenericFailure(msg)
    testThis.tail shouldBe msgs.map(CommandFailures.GenericFailure(_))
  }
      
  def checkFailsWith(fail: CommandFailure, fails: CommandFailure*)(fn: => CommandHandler.ResultT) = {
    val testThis = await(fn.value).left.value.toNonEmptyList.toList

    testThis should have length 1 + fails.length
    testThis.head shouldBe fail
    testThis.tail shouldBe fails
  }
}
