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

package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories

import uk.gov.hmrc.mongo.ReactiveRepository
import com.google.inject.{Singleton, Inject}
import scala.concurrent.ExecutionContext
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import reactivemongo.bson.BSONObjectID
import akka.stream.Materializer
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.AnswersToQuestionnaire
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswersToQuestionnaireJsonFormatters

@Singleton
private[repositories] class AnswersRepository @Inject()(mongo: ReactiveMongoComponent)(implicit val mat: Materializer, val ec: ExecutionContext) 
extends ReactiveRepository[AnswersToQuestionnaire, BSONObjectID]("answersToQuestionnaires", mongo.mongoConnector.db,
    AnswersToQuestionnaireJsonFormatters.format, ReactiveMongoFormats.objectIdFormats) {

}
