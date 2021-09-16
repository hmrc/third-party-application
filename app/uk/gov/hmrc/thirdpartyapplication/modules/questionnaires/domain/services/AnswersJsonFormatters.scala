package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services


trait AnswersJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import play.api.libs.json._
  import uk.gov.hmrc.play.json.Union

  implicit val jsonFormatSingleChoiceAnswer = Json.format[SingleChoiceAnswer]
  implicit val jsonFormatMultipleChoiceAnswer = Json.format[MultipleChoiceAnswer]
  implicit val jsonFormatTextAnswer = Json.format[TextAnswer]

  implicit val jsonFormatAnswerType: Format[Answer] = Union.from[Answer]("answer")
    .and[SingleChoiceAnswer]("singleChoiceAnswer")
    .and[MultipleChoiceAnswer]("multipleChoiceAnswer")
    .and[TextAnswer]("textAnswer")
    .format
}

object AnswersJsonFormatters extends AnswersJsonFormatters