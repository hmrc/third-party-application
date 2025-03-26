package uk.gov.hmrc.thirdpartyapplication.controllers.query

sealed trait DeleteRestrictionFilter

case object DeleteRestrictionFilter {
  case object NoRestriction extends DeleteRestrictionFilter
  case object DoNotDelete   extends DeleteRestrictionFilter

  def apply(text: String): Option[DeleteRestrictionFilter] = {
    import cats.implicits._
    text match {
      case "DO_NOT_DELETE"  => DoNotDelete.some
      case "NO_RESTRICTION" => NoRestriction.some
    }
  }
}
