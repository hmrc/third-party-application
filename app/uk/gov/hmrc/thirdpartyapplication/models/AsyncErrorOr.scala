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

package uk.gov.hmrc.thirdpartyapplication.models

import cats.data.EitherT

import scala.concurrent.{ExecutionContext, Future}

object AsyncErrorOr {
  import cats.implicits._

  type Error = Throwable

  type AsyncErrorOr[A] = EitherT[Future, Error, A]

  def fromFuture[A](future: Future[A])(implicit ec: ExecutionContext): AsyncErrorOr[A] = {
    EitherT.liftF(future)
  }

  def left[A](error: Error)(implicit ec: ExecutionContext): AsyncErrorOr[A] = {
    EitherT.leftT(error)
  }

  def right[A](value: A)(implicit ec: ExecutionContext): AsyncErrorOr[A] = {
    EitherT.rightT(value)
  }

  def pure[A](value: A)(implicit ec: ExecutionContext): AsyncErrorOr[A] = right(value)

  type ErrorOr[A] = Either[Error, A]
}
