package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.services.query.StreamCompression
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository.LimitedApp
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.CoreApplicationFixtures
import uk.gov.hmrc.apiplatform.modules.common.utils.HmrcSpec
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock
import scala.collection.mutable.Map
import uk.gov.hmrc.thirdpartyapplication.services.query._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifierFixtures
import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApplicationId
import scala.collection.mutable.ArrayBuffer
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiIdentifier

class CompressStreamSpec extends HmrcSpec with CoreApplicationFixtures with ApiIdentifierFixtures with FixedClock {

  val applicationIdZero = ApplicationId.random
  val appNameZero = ApplicationName("XYZ")

  val subscriptions1 = Some(Set(apiIdentifierOne, apiIdentifierTwo))
  val subscriptions2 = subscriptions1
  val subscriptions3 = Some(Set(apiIdentifierTwo, apiIdentifierThree))
  
  val app0: LimitedApp = LimitedApp(applicationIdZero, appNameZero, instant, instant, None)
  val app1: LimitedApp = LimitedApp(applicationIdOne, appNameOne, instant, instant, subscriptions1)
  val app2: LimitedApp = LimitedApp(applicationIdTwo, appNameTwo, instant, instant, subscriptions2)
  val app3: LimitedApp = LimitedApp(applicationIdThree, appNameThree, instant, instant, subscriptions3)
 
  "StreamCompression" should {
    val empty: StreamCompression.LookupTable = Map.empty

    "compress the first record with no subscriptions" in {
      val table: StreamCompression.LookupTable = Map.empty

      val compressed = StreamCompression.compress( (table, app0) )
      
      compressed shouldBe
        (( empty, List(OutputApp(SimpleApp(applicationIdZero, appNameZero, instant, instant), None)) ))

      StreamCompression.decompress( (ArrayBuffer.empty[ApiIdentifier], compressed._2) )._2 shouldBe List(app0)
    }

    "compress the first record with subscriptions" in {
      val table: StreamCompression.LookupTable = Map.empty

      val compressed = StreamCompression.compress( (table, app1) )
      
      compressed shouldBe
        (( 
          Map(apiIdentifierOne -> 1, apiIdentifierTwo -> 2), 
          List(
            OutputSubscription(apiIdentifierOne),
            OutputSubscription(apiIdentifierTwo),
            OutputApp(SimpleApp(applicationIdOne, appNameOne, instant, instant), Some(Set(1,2)))
          ) 
        ))


      StreamCompression.decompress( (ArrayBuffer.empty[ApiIdentifier], compressed._2) )._2 shouldBe List(app1)

    }

    "compress the second record with subscriptions" in {
      val table: StreamCompression.LookupTable = Map.empty

      val (_, list1) = StreamCompression.compress( (table, app1) )
      
      val compressed = StreamCompression.compress( (table, app2) ) 
      
      compressed shouldBe
        (( 
          Map(apiIdentifierOne -> 1, apiIdentifierTwo -> 2), 
          List(
            OutputApp(SimpleApp(applicationIdTwo, appNameTwo, instant, instant), Some(Set(1,2)))
          ) 
        ))

      StreamCompression.decompress( (ArrayBuffer.empty[ApiIdentifier], list1 ++ compressed._2) )._2 shouldBe List(app1, app2)
    }

    "compress the third record with subscriptions" in {
      val table: StreamCompression.LookupTable = Map.empty

      val (_, list1) = StreamCompression.compress( (table, app1) )
      val (_, list2) = StreamCompression.compress( (table, app2) )
      
      val compressed = StreamCompression.compress( (table, app3) )
      compressed shouldBe
        (( 
          Map(apiIdentifierOne -> 1, apiIdentifierTwo -> 2, apiIdentifierThree -> 3), 
          List(
            OutputSubscription(apiIdentifierThree),
            OutputApp(SimpleApp(applicationIdThree, appNameThree, instant, instant), Some(Set(2,3)))
          ) 
        ))

      StreamCompression.decompress( (ArrayBuffer.empty[ApiIdentifier], list1 ++ list2 ++ compressed._2) )._2 shouldBe List(app1, app2, app3)
    }
  }
}
