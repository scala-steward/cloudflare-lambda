addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.5.10")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.3.1")
addSbtPlugin("com.codecommit" % "sbt-github-actions" % "0.14.2")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.11")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

libraryDependencies += "io.circe" %% "circe-yaml" % "0.14.1"
