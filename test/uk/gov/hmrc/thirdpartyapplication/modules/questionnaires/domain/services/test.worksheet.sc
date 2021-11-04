import scala.collection.immutable.ListMap
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.models._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.domain.services.SubmissionsJsonFormatters._
import uk.gov.hmrc.thirdpartyapplication.modules.submissions.repositories.QuestionnaireDAO.Questionnaires._

Json.prettyPrint(Json.toJson(CustomersAuthorisingYourSoftware.question3))


val textAnswer = TextAnswer("The Answer")
val answer: ActualAnswer = textAnswer
Json.prettyPrint(Json.toJson(answer))
Json.prettyPrint(Json.toJson(Some(answer)))

val answer2: ActualAnswer = NoAnswer
Json.prettyPrint(Json.toJson(answer2))



val answers: Map[QuestionId, ActualAnswer] = ListMap(QuestionId.random -> answer, QuestionId.random -> NoAnswer)

val text = Json.prettyPrint(Json.toJson(answers))

val reverseAnswers = Json.parse(text).as[Map[QuestionId, ActualAnswer]]

QuestionId.random

// """{
//   "ac2c6c07-56f6-4477-a476-ef8ce74b17e5" : {
//     "value" : "The Answer",
//     "answerType" : "text"
//   },
//   "a414574b-dbe5-4e8a-af01-51533b588f99" : {
//     "answerType" : "noAnswer"
//   }
// }"""