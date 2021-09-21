import scoverage.ScoverageKeys
  
object ScoverageSettings {
  def apply() = Seq(
    ScoverageKeys.coverageExcludedPackages := Seq(
      "<empty",
      """.*\.domain\.models\..*""" ,
      """uk\.gov\.hmrc\.thirdpartyapplication\.controllers\.binders""",
      """uk\.gov\.hmrc\.thirdpartyapplication\.modules\.questionnaires\.controllers\.binders""",
      """uk\.gov\.hmrc\.thirdpartyapplication\.modules\.questionnaires\.controllers""",  // TODO - remove
      """uk\.gov\.hmrc\.BuildInfo""" ,
      """.*\.Routes""" ,
      """.*\.RoutesPrefix""" ,
      // """questionnaires\.Routes""" ,
      // """questionnaires\.RoutesPrefix""" ,
      """.*Filters?""" ,
      """.*\.Reverse[^.]*""",
    ).mkString(";"),
    ScoverageKeys.coverageMinimum := 89.00,
    ScoverageKeys.coverageFailOnMinimum := true,
    ScoverageKeys.coverageHighlighting := true
  )
}
