/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatform.modules.approvals.repositories

import akka.stream.Materializer
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.apiplatform.modules.approvals.domain.models.{ResponsibleIndividualVerification, ResponsibleIndividualVerificationId}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ResponsibleIndividualVerificationRepository @Inject() (mongo: MongoComponent)
                                                            (implicit val mat: Materializer, val ec: ExecutionContext)
    extends PlayMongoRepository[ResponsibleIndividualVerification](
      collectionName = "responsibleIndividualVerification",
      mongoComponent = mongo,
      domainFormat = ResponsibleIndividualVerification.format,
      indexes = Seq(
        IndexModel(ascending("id"), IndexOptions()
          .name("responsibleIndividualVerificationIdIndex")
          .unique(true)
          .background(true)
        ),
        IndexModel(ascending("createdOn"),IndexOptions()
            .name("responsibleIndividualVerificationCreatedOnIndex")
            .background(true)
        ),
        IndexModel(ascending("applicationId", "submissionId", "submissionInstance"), IndexOptions()
            .name("responsibleIndividualVerificationAppSubmissionIdIndex")
            .unique(true)
            .background(true)
        )
      )
    ) with MongoJavatimeFormats.Implicits {}
