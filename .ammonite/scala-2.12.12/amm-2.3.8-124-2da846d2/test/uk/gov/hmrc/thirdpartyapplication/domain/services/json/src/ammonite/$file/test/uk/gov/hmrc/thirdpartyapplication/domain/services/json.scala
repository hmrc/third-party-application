
package ammonite
package $file.test.uk.gov.hmrc.thirdpartyapplication.domain.services
import _root_.ammonite.interp.api.InterpBridge.{
  value => interp
}
import _root_.ammonite.interp.api.InterpBridge.value.{
  exit,
  scalaVersion
}
import _root_.ammonite.interp.api.IvyConstructor.{
  ArtifactIdExt,
  GroupIdExt
}
import _root_.ammonite.compiler.CompilerExtensions.{
  CompilerInterpAPIExtensions,
  CompilerReplAPIExtensions
}
import _root_.ammonite.runtime.tools.{
  browse,
  grep,
  time,
  tail
}
import _root_.ammonite.compiler.tools.{
  desugar,
  source
}
import _root_.mainargs.{
  arg,
  main
}
import _root_.ammonite.repl.tools.Util.{
  PathRead
}


object json{
/*<script>*/import play.api.libs.json._
import play.api.libs.json.Json._

val x = Seq(1,2,3)

val y = JsArray(JsNumber(1), JsNumber(2))
/*<amm>*/val res_4 = /*</amm>*/Json.stringify(y)
/*</script>*/ /*<generated>*/
def $main() = { scala.Iterator[String]() }
  override def toString = "json"
  /*</generated>*/
}
