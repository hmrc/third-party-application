import scoverage.ScoverageKeys
  
object ScoverageSettings {
  def apply() = Seq(
    ScoverageKeys.coverageExcludedPackages := Seq(
      "<empty",
      """.*\.domain\.models\..*""" ,
      """uk\.gov\.hmrc\.thirdpartyapplication\.controllers\.binders""",
      """uk\.gov\.hmrc\.thirdpartyapplication\.modules\.submissions\.controllers\.binders""",
      """uk\.gov\.hmrc\.BuildInfo""" ,
      """.*\.Routes""" ,
      """.*\.RoutesPrefix""" ,
      """.*\.Reverse[^.]*"""
    ).mkString(";"),
    ScoverageKeys.coverageMinimum := 89.00,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
