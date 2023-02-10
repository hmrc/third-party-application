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

package uk.gov.hmrc.apiplatform.modules.common.domain.models

/** Actor refers to actors that triggered an event
  */
sealed trait Actor

object Actors {

  /** A third party developer who is a collaborator on the application for the event this actor is responsible for triggering
    *
    * @param email
    *   the developers email address at the time of the event
    */
  case class Collaborator(email: String) extends Actor

  /** A gatekeeper stride user (typically SDST)
    *
    * @param user
    *   the stride user fullname of the gatekeeper user who triggered the event on which they are the actor
    */
  case class GatekeeperUser(user: String) extends Actor

  /** An automated job
    *
    * @param jobId
    *   the job name or instance of the job possibly as a UUID
    */
  case class ScheduledJob(jobId: String) extends Actor

  /** Unknown source - probably 3rd party code such as PPNS invocations
    */
  case object Unknown extends Actor

}
