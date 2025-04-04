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
      """uk\.gov\.hmrc\.apiplatform\.modules\.test_only\..*"""
    ).mkString(";"),
    ScoverageKeys.coverageMinimumStmtTotal := 91.49,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
