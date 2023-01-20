import scoverage.ScoverageKeys
  
object ScoverageSettings {
  def apply() = Seq(
    ScoverageKeys.coverageExcludedPackages := Seq(
      "<empty>",
      """.*\.controllers\.binders""",
      """uk\.gov\.hmrc\.BuildInfo""" ,
      """.*\.Routes""" ,
      """.*\.RoutesPrefix""" ,
      """.*\.Reverse[^.]*""",
      """uk\.gov\.hmrc\.apiplatform\.modules\.common\..*""",
      """uk\.gov\.hmrc\.apiplatform\.modules\.scheduling\..*""",
      """uk\.gov\.hmrc\.apiplatform\.modules\.gkauth\.domain\.models\..*""",
    ).mkString(";"),
    ScoverageKeys.coverageMinimumStmtTotal := 92.00,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
