package uk.gov.hmrc.thirdpartyapplication.controllers.query

sealed trait Sorting

object Sorting {
  case object NameAscending         extends Sorting
  case object NameDescending        extends Sorting
  case object SubmittedAscending    extends Sorting
  case object SubmittedDescending   extends Sorting
  case object LastUseDateAscending  extends Sorting
  case object LastUseDateDescending extends Sorting
  case object NoSorting             extends Sorting

  def apply(text: String): Option[Sorting] = {
    import cats.implicits._
    text match {
      case "NAME_ASC"       => NameAscending.some
      case "NAME_DESC"      => NameDescending.some
      case "SUBMITTED_ASC"  => SubmittedAscending.some
      case "SUBMITTED_DESC" => SubmittedDescending.some
      case "LAST_USE_ASC"   => LastUseDateAscending.some
      case "LAST_USE_DESC"  => LastUseDateDescending.some
      case "NO_SORT"        => NoSorting.some
      case _                => None
    }
  }
}
