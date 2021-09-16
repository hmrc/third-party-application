package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories

import uk.gov.hmrc.mongo.ReactiveRepository
import com.google.inject.{Singleton, Inject}
import scala.concurrent.ExecutionContext
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import reactivemongo.bson.BSONObjectID
import akka.stream.Materializer
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.AnswersToQuestionnaire

@Singleton
private[repositories] class AnswersRepository @Inject()(mongo: ReactiveMongoComponent)(implicit val mat: Materializer, val ec: ExecutionContext) 
extends ReactiveRepository[AnswersToQuestionnaire, BSONObjectID]("answersToQuestionnaires", mongo.mongoConnector.db,
    AnswersToQuestionnaire.format, ReactiveMongoFormats.objectIdFormats) {
  
}
