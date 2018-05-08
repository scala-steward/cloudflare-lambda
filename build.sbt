javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

lazy val commonSettings = Seq(
  organization := "Dwolla",
  homepage := Option(url("https://github.com/Dwolla/cloudflare-lambda")),
  scalaVersion := "2.12.6",
  scalacOptions ++= Seq("-feature", "-deprecation")
)

lazy val specs2Version = "4.2.0"
lazy val awsSdkVersion = "1.11.323"
lazy val scalaAwsUtilsVersion = "1.6.1"

lazy val root = (project in file("."))
  .settings(
    name := "cloudflare-lambda",
    resolvers ++= Seq(
      Resolver.bintrayIvyRepo("dwolla", "maven"),
      Resolver.bintrayRepo("dwolla", "maven")
    ),
    libraryDependencies ++= {
      Seq(
        "com.dwolla" %% "scala-cloudformation-custom-resource" % "1.1.1" exclude ("com.dwolla", "scala-aws-utils_2.12") withSources(),
        "com.dwolla" %% "scala-aws-utils" % scalaAwsUtilsVersion withSources(),
        "com.dwolla" %% "cloudflare-api-client" % "1.3.0" withSources(),
        "com.amazonaws" % "aws-java-sdk-kms" % awsSdkVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "org.specs2" %% "specs2-core" % specs2Version % Test,
        "org.specs2" %% "specs2-mock" % specs2Version % Test,
        "org.specs2" %% "specs2-matcher-extra" % specs2Version % Test,
        "com.dwolla" %% "testutils" % "1.9.0" % Test
      )
    }
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
        "com.monsanto.arch" %% "cloud-formation-template-generator" % "3.8.1",
        "org.specs2" %% "specs2-core" % specs2Version % "test,it",
        "com.amazonaws" % "aws-java-sdk-cloudformation" % awsSdkVersion % IntegrationTest,
        "com.dwolla" %% "scala-aws-utils" % scalaAwsUtilsVersion % IntegrationTest withSources()
      )
    },
    stackName := (name in root).value,
    stackParameters := List(
      "S3Bucket" → (s3Bucket in root).value,
      "S3Key" → (s3Key in root).value
    ),
    awsAccountId := sys.props.get("AWS_ACCOUNT_ID"),
    awsRoleName := Option("cloudformation/deployer/cloudformation-deployer")
  )
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .enablePlugins(CloudFormationStack)
  .dependsOn(root)

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", _*) ⇒ MergeStrategy.discard
  case _ ⇒ MergeStrategy.first
}
test in assembly := {}

lazy val slf4jEarlyInit = sbt.Tests.Setup(cl ⇒
  cl.loadClass("org.slf4j.LoggerFactory")
    .getMethod("getLogger", cl.loadClass("java.lang.String"))
    .invoke(null, "ROOT")
)

testOptions += slf4jEarlyInit
