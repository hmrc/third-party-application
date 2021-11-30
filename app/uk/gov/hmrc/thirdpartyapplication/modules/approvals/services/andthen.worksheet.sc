import scala.util.Failure
import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}
import scala.concurrent.ExecutionContext.Implicits.global

var x = 1

// val f1: Future[String] = successful("Bob")
val f1: Future[String] = failed(new RuntimeException("Bob sucks"))
val fAlt: (Exception) => Future[String] = (f) => successful("Fred "+f.getLocalizedMessage())
val fAlt2: (Failure[_]) => Future[String] = (f) => { x = x + 1; successful("Fred "+f.exception.getLocalizedMessage()) }
// val fAlt2: (Failure[_]) => Future[String] = (f) => { x = x + 1; failed(new RuntimeException("Boom")) }

f1 recoverWith {
  case e: Exception => fAlt(e)
}

f1 andThen {
  case e: Failure[String] => fAlt2(e)
}

x