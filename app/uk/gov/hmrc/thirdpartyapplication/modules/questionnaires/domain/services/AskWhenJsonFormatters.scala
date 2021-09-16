package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services

trait AskWhenJsonFormatters extends AnswersJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import play.api.libs.json._
  import uk.gov.hmrc.play.json.Union

  implicit val jsonFormatAskWhenContext = Json.format[AskWhenContext]
  implicit val jsonFormatAskWhenAnswer = Json.format[AskWhenAnswer]
  implicit val jsonFormatAskAlways = Json.format[AlwaysAsk.type]

  implicit val jsonFormatCondition: Format[AskWhen] = Union.from[AskWhen]("askWhen")
    .and[AskWhenContext]("askWhenContext")
    .and[AskWhenAnswer]("askWhenAnswer")
    .and[AlwaysAsk.type]("alwaysAsk")
    .format
}

object AskWhenJsonFormatters extends AskWhenJsonFormatters