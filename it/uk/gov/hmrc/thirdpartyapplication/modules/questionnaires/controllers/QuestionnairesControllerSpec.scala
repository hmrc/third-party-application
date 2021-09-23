package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.controllers

import uk.gov.hmrc.thirdpartyapplication.component.BaseFeatureSpec
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.thirdpartyapplication.util.CredentialGenerator
import uk.gov.hmrc.thirdpartyapplication.component.DummyCredentialGenerator
import play.api.inject.bind
import scalaj.http.Http
import play.api.libs.json.Json
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models.GroupOfQuestionnaires
import play.api.test.Helpers.OK
import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services.GroupOfQuestionnairesJsonFormatters

class QuestionnairesControllerSpec
  extends BaseFeatureSpec
  with GroupOfQuestionnairesJsonFormatters {  
    
  val configOverrides = Map[String,Any](
    "mongodb.uri" -> "mongodb://localhost:27017/third-party-application-test"          
  )
  
  override def fakeApplication =
    GuiceApplicationBuilder()
      .configure(configOverrides)
      .overrides(bind[CredentialGenerator].to[DummyCredentialGenerator])
      .build()

  Feature("Fetch all active groups of questionnaires") {

    Scenario("Fetch all groups") {

      When("We fetch all groups")
      val fetchResponse = Http(s"$serviceUrl/questionnaires").asString
      fetchResponse.code shouldBe OK
      val result = Json.parse(fetchResponse.body).as[List[GroupOfQuestionnaires]]

      Then("All grouped questionnaires are returned in the result")
      result.size shouldBe 4
      result(0).heading shouldBe "Your processes"
    }
  }
}
