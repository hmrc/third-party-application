package uk.gov.hmrc.thirdpartyapplication.modules.questionnaires.domain.models


sealed trait StatementFragment
sealed trait NonBulletStatementFragment extends StatementFragment
sealed trait SimpleStatementFragment extends NonBulletStatementFragment
case class StatementText(text: String) extends SimpleStatementFragment
case class StatementLink(text: String, url: String) extends SimpleStatementFragment

case class StatementBullets(bullets: List[NonBulletStatementFragment]) extends StatementFragment

object StatementBullets {
  def apply(bullets: NonBulletStatementFragment*) = new StatementBullets(bullets.toList)
}

case class CompoundFragment(fragments: List[SimpleStatementFragment]) extends NonBulletStatementFragment

object CompoundFragment {
  def apply(fragments: SimpleStatementFragment*) = new CompoundFragment(fragments.toList)
}

case class Statement(fragments: List[StatementFragment])

object Statement {
  def apply(fragments: StatementFragment*) = new Statement(fragments.toList)
}

