resolvers += "HMRC-open-artefacts-maven" at "https://open.artefacts.tax.service.gov.uk/maven2"
resolvers += Resolver.url("HMRC-open-artefacts-ivy", url("https://open.artefacts.tax.service.gov.uk/ivy2"))(Resolver.ivyStylePatterns)

resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("uk.gov.hmrc"       % "sbt-auto-build"        % "3.24.0")
addSbtPlugin("uk.gov.hmrc"       % "sbt-distributables"    % "2.6.0")
addSbtPlugin("org.playframework" % "sbt-plugin"            % "3.0.10")
addSbtPlugin("org.scoverage"     % "sbt-scoverage"         % "2.4.4")
addSbtPlugin("ch.epfl.scala"     % "sbt-bloop"             % "2.0.19")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"          % "2.5.2")
addSbtPlugin("ch.epfl.scala"     % "sbt-scalafix"          % "0.14.5")

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
