package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

trait QuestionJsonFormatters extends StatementJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import play.api.libs.json._
  import uk.gov.hmrc.play.json.Union

  implicit val jsonFormatWording = Json.valueFormat[Wording]
  implicit val jsonFormatLabel = Json.valueFormat[Label]


  implicit val jsonFormatQuestionChoice = Json.valueFormat[QuestionChoice]
  implicit val jsonFormatTextQuestion = Json.format[TextQuestion]
  implicit val jsonFormatYesNoQuestion = Json.format[YesNoQuestion]
  implicit val jsonFormatChooseOneOfQuestion = Json.format[ChooseOneOfQuestion]
  implicit val jsonFormatMultiChoiceQuestion = Json.format[MultiChoiceQuestion]
  implicit val jsonFormatSingleChoiceQuestion = Json.format[SingleChoiceQuestion]

  implicit val jsonFormatQuestion: Format[Question] = Union.from[Question]("questionType")
    .and[ChooseOneOfQuestion]("choose")
    .and[MultiChoiceQuestion]("multi")
    .and[YesNoQuestion]("yesNo")
    .and[SingleChoiceQuestion]("single")
    .and[TextQuestion]("text")
    .format
}

object QuestionJsonFormatters extends QuestionJsonFormatters
