package uk.gov.hmrc.thirdpartyapplication.models

import org.scalatest.{Matchers, WordSpec}
import play.api.libs.json.{Json, Reads}

class MongoFormatSpec extends WordSpec with Matchers {

  "CheckInformation parsing from the database" should {
    "parse fully populated json" in {
      val json =
        """
          |{
          |    "confirmedName" : true,
          |    "apiSubscriptionsConfirmed" : true,
          |    "providedPrivacyPolicyURL" : true,
          |    "providedTermsAndConditionsURL" : true,
          |    "teamConfirmed" : true
          |}
          |""".stripMargin

      val checkInformation: CheckInformation = Json.parse(json).as[CheckInformation](MongoFormat.checkInformationReads)

      checkInformation.confirmedName shouldBe true
      checkInformation.apiSubscriptionsConfirmed shouldBe true
      checkInformation.providedPrivacyPolicyURL shouldBe true
      checkInformation.providedTermsAndConditionsURL shouldBe true
      checkInformation.teamConfirmed shouldBe true
    }
  }

  "default teamConfirmed to false if missing from Json" in {
    val json =
      """
        |{
        |    "confirmedName" : false,
        |    "apiSubscriptionsConfirmed" : false,
        |    "providedPrivacyPolicyURL" : false,
        |    "providedTermsAndConditionsURL" : false
        |}
        |""".stripMargin

    val checkInformation: CheckInformation = Json.parse(json).as[CheckInformation](MongoFormat.checkInformationReads)

    checkInformation.teamConfirmed shouldBe false
  }
}
