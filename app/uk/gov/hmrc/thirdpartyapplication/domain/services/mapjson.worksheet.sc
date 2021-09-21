import uk.gov.hmrc.thirdpartyapplication.domain.services.MapJsonFormatters
import play.api.libs.json.Reads
import play.api.libs.json.{JsObject, JsString, Json, Writes, JsArray}
import scala.collection.immutable.ListMap
import play.api.libs.json.Json

case class Key(value: String)

case class Value(part1: String, part2: Int)
implicit val valueFormat = Json.format[Value]

import uk.gov.hmrc.thirdpartyapplication.domain.services.MapJsonFormatters._

implicit val asString = (k: Key) => k.value
val writer = implicitly[Writes[ListMap[Key, Value]]]

val listMapRaw = ListMap(Key("k1") -> Value("p1a", 1), Key("a1") -> Value("p2a", 2))
val listMapRawJson = Json.toJson[ListMap[Key, Value]](listMapRaw)(writer)
val out = Json.stringify(listMapRawJson)

implicit val asKey: (String) => Key = (s) => Key(s)
implicit val readsV = implicitly[Reads[Value]]
val parse = Json.parse(out)

val reader = MapJsonFormatters.listMapReads[Key, Value](asKey, readsV)
val listMapRead = Json.fromJson[ListMap[Key, Value]](parse)(reader).asOpt.get
