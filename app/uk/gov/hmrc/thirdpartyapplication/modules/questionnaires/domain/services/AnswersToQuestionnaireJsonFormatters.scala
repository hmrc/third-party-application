package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

trait AnswersToQuestionnaireJsonFormatters extends QuestionnaireJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
  import play.api.libs.json._

  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats
  implicit val format = Json.format[AnswersToQuestionnaire]
}
