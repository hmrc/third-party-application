package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

import uk.gov.hmrc.thirdpartyapplication.domain.services.MapJsonFormatters
import scala.collection.immutable.ListMap

trait AnswersToQuestionnaireJsonFormatters extends QuestionnaireJsonFormatters with MapJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import play.api.libs.json._
  
  def toRaw(in: ListMap[QuestionId, Answer]): ListMap[String, Answer] = {
    in.flatMap { case (k,v) => ListMap(k.value -> v) }
  }
  def fromRaw(in: ListMap[String, Answer]): ListMap[QuestionId, Answer] = {
    in.flatMap { case (k,v) => ListMap(QuestionId(k) -> v) }
  }
  
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
  implicit val listMapWrites: Writes[ListMap[QuestionId, Answer]] = listMapWrites[Answer].contramap[ListMap[QuestionId, Answer]](toRaw)
  implicit val listMapReads: Reads[ListMap[QuestionId, Answer]] = listMapReads[Answer].map(fromRaw)
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val format = Json.format[AnswersToQuestionnaire]
}

object AnswersToQuestionnaireJsonFormatters extends AnswersToQuestionnaireJsonFormatters
