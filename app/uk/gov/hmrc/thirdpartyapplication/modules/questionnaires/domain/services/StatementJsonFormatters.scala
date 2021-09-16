package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.services


trait StatementJsonFormatters {
  import uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models._
  import play.api.libs.json._
  import play.api.libs.functional.syntax._
  import uk.gov.hmrc.play.json.Union

  implicit val jsonFormatStatementText = Json.format[StatementText]
  implicit val jsonFormatStatementLink = Json.format[StatementLink]

  implicit lazy val readsStatementBullets: Reads[StatementBullets] = (
      ( __ \ "bullets" ).read(Reads.seq[NonBulletStatementFragment](jsonFormatNonBulletStatementFragment)
    .map(_.toList).map(StatementBullets(_)))
  )

  implicit lazy val writesStatementBullets: OWrites[StatementBullets] = (
    (
              (__ \ "bullets").write(Writes.seq[NonBulletStatementFragment](jsonFormatNonBulletStatementFragment.writes))
    )
    .contramap (unlift(StatementBullets.unapply))
  )

  implicit lazy val jsonFormatStatementBullets: OFormat[StatementBullets] = OFormat(readsStatementBullets, writesStatementBullets)

  implicit lazy val readsCompoundFragment: Reads[CompoundFragment] = (
      ( __ \ "bullets" ).read(Reads.seq[SimpleStatementFragment](jsonFormatSimpleStatementFragment)
    .map(_.toList).map(CompoundFragment(_)))
  )

  implicit lazy val writesCompoundFragment: OWrites[CompoundFragment] = (
    (
              (__ \ "bullets").write(Writes.seq[SimpleStatementFragment](jsonFormatSimpleStatementFragment.writes))
    )
    .contramap (unlift(CompoundFragment.unapply))
  )

  implicit lazy val jsonFormatCompoundFragment: OFormat[CompoundFragment] = OFormat(readsCompoundFragment, writesCompoundFragment)

  implicit lazy val jsonFormatSimpleStatementFragment: Format[SimpleStatementFragment] = Union.from[SimpleStatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .format

  implicit lazy val jsonFormatNonBulletStatementFragment: Format[NonBulletStatementFragment] = Union.from[NonBulletStatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .andLazy[CompoundFragment]("compound", jsonFormatCompoundFragment)
    .format

  implicit lazy val jsonFormatStatementFragment: Format[StatementFragment] = Union.from[StatementFragment]("statementType")
    .and[StatementText]("text")
    .and[StatementLink]("link")
    .andLazy[StatementBullets]("bullets", jsonFormatStatementBullets)
    .andLazy[CompoundFragment]("compound", jsonFormatCompoundFragment)
    .format

  implicit val jsonFormatStatement = Json.format[Statement]
}

object StatementJsonFormatters extends StatementJsonFormatters