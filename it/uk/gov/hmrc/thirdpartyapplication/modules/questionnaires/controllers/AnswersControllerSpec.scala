package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.controllers

import uk.gov.hmrc.thirdpartyapplication.component.BaseFeatureSpec
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.AnswersToQuestionnaireJsonFormatters
import uk.gov.hmrc.mongo.MongoSpecSupport
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.MongoConnector
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.repositories.AnswersRepository
import org.scalatest.concurrent.Eventually

class AnswersControllerSpec
  extends BaseFeatureSpec
    with MongoSpecSupport
    with Eventually
    with AnswersToQuestionnaireJsonFormatters {  
    
  val configOverrides = Map[String,Any](
    "mongodb.uri" -> "mongodb://localhost:27017/third-party-application-test"          
  )
  
  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val answersRepository = new AnswersRepository(reactiveMongoComponent)

  override def beforeEach() {
    List(answersRepository).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(answersRepository).foreach { db =>
      await(db.drop)
    }
  }

  feature("Fetch answers to questionnaires by reference id") {

    scenario("Fetch all groups") {
    }
  }
}
