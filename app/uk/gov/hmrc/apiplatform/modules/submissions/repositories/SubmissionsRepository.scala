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

package uk.gov.hmrc.apiplatform.modules.submissions.repositories

import java.time.Instant
import scala.concurrent.ExecutionContext

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.model.Indexes.ascending
import org.mongodb.scala.model.{IndexModel, IndexOptions}

import play.api.libs.json.*
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}

import uk.gov.hmrc.apiplatform.modules.common.domain.services.NonEmptyListFormatters
import uk.gov.hmrc.apiplatform.modules.submissions.domain.models.*
import uk.gov.hmrc.apiplatform.modules.submissions.models.ApplicationsByAnswer

object SubmissionsRepository {

  object MongoFormats {
    import uk.gov.hmrc.play.json.Union

    given keyReadsQuestionnaireId: KeyReads[Questionnaire.Id]   = KeyReads(key => JsSuccess(Questionnaire.Id(key)))
    given keyWritesQuestionnaireId: KeyWrites[Questionnaire.Id] = KeyWrites(_.value)

    given stateWrites: Writes[QuestionnaireState] = Writes {
      case QuestionnaireState.NotStarted    => JsString("NotStarted")
      case QuestionnaireState.InProgress    => JsString("InProgress")
      case QuestionnaireState.NotApplicable => JsString("NotApplicable")
      case QuestionnaireState.Completed     => JsString("Completed")
    }

    given stateReads: Reads[QuestionnaireState] = Reads {
      case JsString("NotStarted")    => JsSuccess(QuestionnaireState.NotStarted)
      case JsString("InProgress")    => JsSuccess(QuestionnaireState.InProgress)
      case JsString("NotApplicable") => JsSuccess(QuestionnaireState.NotApplicable)
      case JsString("Completed")     => JsSuccess(QuestionnaireState.Completed)
      case _                         => JsError("Failed to parse QuestionnaireState value")
    }

    given questionnaireProgressFormat: OFormat[QuestionnaireProgress] = Json.format[QuestionnaireProgress]
    given questionIdsOfInterestFormat: OFormat[QuestionIdsOfInterest] = Json.format[QuestionIdsOfInterest]

    given dateFormat: Format[Instant] = MongoJavatimeFormats.instantFormat

    import Submission.Status.*

    given RejectedStatusFormat: OFormat[Declined]                                         = Json.format[Declined]
    given AcceptedStatusFormat: OFormat[Granted]                                          = Json.format[Granted]
    given AcceptedWithWarningsStatusFormat: OFormat[GrantedWithWarnings]                  = Json.format[GrantedWithWarnings]
    given failedStatusFormat: OFormat[Failed]                                             = Json.format[Failed]
    given warningsStatusFormat: OFormat[Warnings]                                         = Json.format[Warnings]
    given pendingResponsibleIndividualStatusFormat: OFormat[PendingResponsibleIndividual] = Json.format[PendingResponsibleIndividual]
    given SubmittedStatusFormat: OFormat[Submitted]                                       = Json.format[Submitted]
    given answeringStatusFormat: OFormat[Answering]                                       = Json.format[Answering]
    given CreatedStatusFormat: OFormat[Created]                                           = Json.format[Created]
    given applicationsByAnswerFormat: OFormat[ApplicationsByAnswer]                       = Json.format[ApplicationsByAnswer]

    given submissionStatus: OFormat[Submission.Status] = Union.from[Submission.Status]("Submission.StatusType")
      .and[Declined]("declined")
      .and[Granted]("granted")
      .and[GrantedWithWarnings]("grantedWithWarnings")
      .and[Failed]("failed")
      .and[Warnings]("warnings")
      .and[PendingResponsibleIndividual]("pendingResponsibleIndividual")
      .and[Submitted]("submitted")
      .and[Answering]("answering")
      .and[Created]("created")
      .format

    import GroupOfQuestionnaires.given
    import NonEmptyListFormatters.given

    given submissionInstanceFormat: OFormat[Submission.Instance] = Json.format[Submission.Instance]
    given submissionFormat: OFormat[Submission]                  = Json.format[Submission]
  }
}

@Singleton
class SubmissionsRepository @Inject() (mongo: MongoComponent)(using val ec: ExecutionContext)
    extends PlayMongoRepository[Submission](
      collectionName = "submissions",
      mongoComponent = mongo,
      domainFormat = SubmissionsRepository.MongoFormats.submissionFormat,
      extraCodecs = Seq(Codecs.playFormatCodec(SubmissionsRepository.MongoFormats.applicationsByAnswerFormat)),
      indexes = Seq(
        IndexModel(
          ascending("applicationId"),
          IndexOptions()
            .name("applicationIdIndex")
            .background(true)
        ),
        IndexModel(
          ascending("id"),
          IndexOptions()
            .name("submissionIdIndex")
            .unique(true)
            .background(true)
        )
      ),
      replaceIndexes = true
    ) {}
