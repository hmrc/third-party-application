/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.thirdpartyapplication.controllers

import play.api.mvc.PathBindable
import uk.gov.hmrc.thirdpartyapplication.models.{UserId, ApplicationId}
import play.api.mvc.QueryStringBindable
import java.{util => ju}
import scala.util.Try

package object binders {
  private def applicationIdFromString(text: String): Either[String, ApplicationId] = {
    Try(ju.UUID.fromString(text))
    .toOption
    .toRight(s"Cannot accept $text as ApplicationId")
    .map(ApplicationId(_))
  }

  private def userIdFromString(text: String): Either[String, UserId] = {
    Try(ju.UUID.fromString(text))
    .toOption
    .toRight(s"Cannot accept $text as UserId")
    .map(UserId(_))
  }

  implicit def applicationIdPathBinder(implicit textBinder: PathBindable[String]): PathBindable[ApplicationId] = new PathBindable[ApplicationId] {
    override def bind(key: String, value: String): Either[String, ApplicationId] = {
      textBinder.bind(key, value).flatMap(applicationIdFromString)
    }

    override def unbind(key: String, applicationId: ApplicationId): String = {
      applicationId.value.toString()
    }
  }

  implicit def applicationIdQueryStringBindable(implicit textBinder: QueryStringBindable[String]) = new QueryStringBindable[ApplicationId] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ApplicationId]] = {
      textBinder.bind(key, params).map(_.flatMap(applicationIdFromString))
    }

    override def unbind(key: String, applicationId: ApplicationId): String = {
      textBinder.unbind(key, applicationId.value.toString())
    }
  }

  implicit def userIdPathBinder(implicit textBinder: PathBindable[String]): PathBindable[UserId] = new PathBindable[UserId] {
    override def bind(key: String, value: String): Either[String, UserId] = {
      textBinder.bind(key, value).flatMap(userIdFromString)
    }

    override def unbind(key: String, userId: UserId): String = {
      userId.value.toString()
    }
  }

  implicit def queryStringBindable(implicit textBinder: QueryStringBindable[String]) = new QueryStringBindable[UserId] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, UserId]] = {
      for {
        textOrBindError <- textBinder.bind("developerId", params)
      } yield textOrBindError match {
        case Right(idText) => UserId.fromString(idText).toRight(s"Cannot accept $idText as a developer identifier")
        case _ => Left("Unable to bind a developer identifier")
      }
    }

    override def unbind(key: String, developerId: UserId): String = {
      textBinder.unbind("developerId", developerId.asText)
    }
  }
}
