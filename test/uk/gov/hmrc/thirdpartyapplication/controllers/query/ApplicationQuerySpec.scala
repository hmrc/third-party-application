package uk.gov.hmrc.thirdpartyapplication.controllers.query

import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationWithCollaboratorsFixtures
import org.scalatest.EitherValues
import cats.data.NonEmptyList

class ApplicationQuerySpec extends HmrcSpec with ApplicationWithCollaboratorsFixtures with EitherValues {

  val appOneParam = "applicationId" -> Seq(applicationIdOne.toString)
  val pageSizeParam = "pageSize" -> Seq("10")
  val noSubsParam = "noSubscriptions" -> Seq()
  val invalidItem = Map("someheadername" -> Seq("bob"))


  "topLevel" should {
    val test = (ps: Map[String,Seq[String]], hs: Map[String,Seq[String]]) => ApplicationQuery.topLevel(ps, hs).toEither

    "work when given a correct applicationId" in {
      test(Map(appOneParam), Map.empty).value shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "work when given a correct applicationId and some irrelevant header" in {
      test(Map(appOneParam), invalidItem).value shouldBe ApplicationQuery.ById(applicationIdOne)
    }

    "fail for applicationId with pageSize" in {
      test(Map(appOneParam, pageSizeParam), Map.empty) shouldBe Left(NonEmptyList.one("applicationId cannot be mixed with any other parameters"))
    }
    
    "fail for applicationId with pageSize first" in {
      test(Map(pageSizeParam, appOneParam), Map.empty) shouldBe Left(NonEmptyList.one("applicationId cannot be mixed with any other parameters"))
    }

  }
}
