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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import play.api.libs.json.Json

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{Actor, Actors, LaxEmailAddress}

class EventJsonFormatSpec extends AnyWordSpec with Matchers {

  "Actor" should {
    "read to correctJson Format for Collaborator" in {
      val collaborator: Actor = Actors.AppCollaborator(LaxEmailAddress("some value"))
      val collaboratorJson    = Json.toJson(collaborator).toString()
      collaboratorJson shouldBe """{"email":"some value","actorType":"COLLABORATOR"}"""
    }

    "read to correctJson Format for GatekeeperUserActor" in {
      val gkUserActor: Actor = Actors.GatekeeperUser("some value")
      val gkUserJson         = Json.toJson(gkUserActor).toString()
      gkUserJson shouldBe """{"user":"some value","actorType":"GATEKEEPER"}"""
    }

    "read to correctJson Format for ScheduledJobActor" in {
      val scheduledJobActor: Actor = Actors.ScheduledJob("some value")
      val scheduledJobActorJson    = Json.toJson(scheduledJobActor).toString()
      scheduledJobActorJson shouldBe """{"jobId":"some value","actorType":"SCHEDULED_JOB"}"""
    }
  }

}
