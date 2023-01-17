resolvers += "HMRC-open-artefacts-maven" at "https://open.artefacts.tax.service.gov.uk/maven2"
resolvers += Resolver.url("HMRC-open-artefacts-ivy", url("https://open.artefacts.tax.service.gov.uk/ivy2"))(Resolver.ivyStylePatterns)

resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("uk.gov.hmrc"       % "sbt-auto-build"        % "3.8.0")
addSbtPlugin("uk.gov.hmrc"       % "sbt-distributables"    % "2.1.0")
addSbtPlugin("com.typesafe.play" % "sbt-plugin"            % "2.8.18")
addSbtPlugin("org.scoverage"     % "sbt-scoverage"         % "1.9.3")
addSbtPlugin("org.scalastyle"   %% "scalastyle-sbt-plugin" % "1.0.0")
addSbtPlugin("ch.epfl.scala"     % "sbt-bloop"             % "1.4.13")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"          % "2.4.6") 
