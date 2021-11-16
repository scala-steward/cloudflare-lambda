javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

lazy val commonSettings = Seq(
  organization := "Dwolla",
  homepage := Option(url("https://github.com/Dwolla/cloudflare-lambda")),
  scalaVersion := "2.12.10",
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
)

lazy val specs2Version = "4.8.1"
lazy val awsSdkVersion = "1.11.354"
lazy val scalaAwsUtilsVersion = "1.6.1"

lazy val root = (project in file("."))
  .settings(
    name := "cloudflare-lambda",
    resolvers ++= Seq(
      Resolver.mavenLocal
    ),
    libraryDependencies ++= {
      Seq(
        "co.fs2" %% "fs2-core" % "2.1.0",
        "org.typelevel" %% "cats-core" % "2.0.0",
        "org.typelevel" %% "cats-effect" % "2.0.0",
        "com.chuusai" %% "shapeless" % "2.3.3",
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "4.0.0-M2",
        "com.dwolla" %% "fs2-aws" % "2.0.0-M5",
        "io.circe" %% "circe-fs2" % "0.12.0",
        "com.dwolla" %% "cloudflare-api-client" % "4.0.0-M13",
        "org.http4s" %% "http4s-blaze-client" % "0.21.0-M5",
        "com.amazonaws" % "aws-java-sdk-kms" % awsSdkVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "org.specs2" %% "specs2-core" % specs2Version % Test,
        "org.specs2" %% "specs2-mock" % specs2Version % Test,
        "org.specs2" %% "specs2-matcher-extra" % specs2Version % Test,
        "org.specs2" %% "specs2-cats" % specs2Version % Test,
        "com.dwolla" %% "testutils-specs2" % "2.0.0-M3" % Test exclude("ch.qos.logback", "logback-classic")
      )
    },
  )
  .settings(commonSettings: _*)
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(PublishToS3)

lazy val stack: Project = (project in file("stack"))
  .settings(commonSettings: _*)
  .settings(
    resolvers ++= Seq(Resolver.jcenterRepo),
    libraryDependencies ++= {
      Seq(
        "com.monsanto.arch" %% "cloud-formation-template-generator" % "3.10.0",
        "org.specs2" %% "specs2-core" % specs2Version % "test,it",
        "com.amazonaws" % "aws-java-sdk-cloudformation" % awsSdkVersion % IntegrationTest,
        "com.dwolla" %% "scala-aws-utils" % scalaAwsUtilsVersion % IntegrationTest withSources()
      )
    },
    stackName := (name in root).value,
    stackParameters := List(
      "S3Bucket" -> (s3Bucket in root).value,
      "S3Key" -> (s3Key in root).value
    ),
    awsAccountId := sys.props.get("AWS_ACCOUNT_ID"),
    awsRoleName := Option("cloudformation/deployer/cloudformation-deployer"),
    scalacOptions --= Seq(
      "-Xlint:missing-interpolator",
      "-Xlint:option-implicit",
    ),
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(CloudFormationStack)
  .dependsOn(root)

assemblyMergeStrategy in assembly := {
  case PathList(ps @ _*) if ps.last == "Log4j2Plugins.dat" => sbtassembly.Log4j2MergeStrategy.plugincache
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("log4j2.xml") => MergeStrategy.singleOrError
  case _ => MergeStrategy.first
}
test in assembly := {}
