package com.dwolla.cloudformation.cloudflare

import com.dwolla.lambda.cloudflare.CloudflareHandler
import com.monsanto.arch.cloudformation.model._
import com.monsanto.arch.cloudformation.model.resource._

object Stack {
  def template(): Template = {
    val role = `AWS::IAM::Role`("Role",
      AssumeRolePolicyDocument = PolicyDocument(Seq(
        PolicyStatement(
          Effect = "Allow",
          Principal = Option(DefinedPrincipal(Map("Service" → Seq("lambda.amazonaws.com")))),
          Action = Seq("sts:AssumeRole")
        )
      )),
      Policies = Option(Seq(
        Policy("Policy",
          PolicyDocument(Seq(
            PolicyStatement(
              Effect = "Allow",
              Action = Seq(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
              ),
              Resource = Option("arn:aws:logs:*:*:*")
            ),
            PolicyStatement(
              Effect = "Allow",
              Action = Seq(
                "route53:GetHostedZone"
              ),
              Resource = Option("*")
            )
          ))
        )
      ))
    )

    val s3Bucket = StringParameter("S3Bucket", "bucket where Lambda code can be found")
    val s3Key = StringParameter("S3Key", "key where Lambda code can be found")

    val key = `AWS::KMS::Key`("Key",
      Option("Encryption key protecting secrets for the Cloudflare lambda"),
      Enabled = Option(true),
      EnableKeyRotation = Option(true),
      KeyPolicy = PolicyDocument(
        Seq(
          PolicyStatement(
            Sid = Option("AllowDataEncrypterToEncrypt"),
            Effect = "Allow",
            Principal = Option(DefinedPrincipal(Map("AWS" → Seq(`Fn::Sub`("arn:aws:iam::${AWS::AccountId}:role/DataEncrypter"))))),
            Action = Seq(
              "kms:Encrypt",
              "kms:ReEncrypt",
              "kms:DescribeKey"
            ),
            Resource = Option("*")
          ),
          PolicyStatement(
            Sid = Option("AllowLambdaToDecrypt"),
            Effect = "Allow",
            Principal = Option(DefinedPrincipal(Map("AWS" → Seq(`Fn::GetAtt`(Seq(role.name, "Arn")))))),
            Action = Seq(
              "kms:Decrypt",
              "kms:DescribeKey"
            ),
            Resource = Option("*")
          ),
          PolicyStatement(
            Sid = Option("CloudFormationDeploymentRoleOwnsKey"),
            Effect = "Allow",
            Principal = Option(DefinedPrincipal(Map("AWS" → Seq(`Fn::Sub`("arn:aws:iam::${AWS::AccountId}:role/cloudformation/deployer/cloudformation-deployer"))))),
            Action = Seq(
              "kms:Create*",
              "kms:Describe*",
              "kms:Enable*",
              "kms:List*",
              "kms:Put*",
              "kms:Update*",
              "kms:Revoke*",
              "kms:Disable*",
              "kms:Get*",
              "kms:Delete*",
              "kms:ScheduleKeyDeletion",
              "kms:CancelKeyDeletion"
            ),
            Resource = Option("*")
          )
        )
      )
    )

    val alias = `AWS::KMS::Alias`("KeyAlias", AliasName = "alias/CloudflareKey", TargetKeyId = ResourceRef(key))

    val lambda = `AWS::Lambda::Function`("Function",
      Code = Code(
        S3Bucket = Option(s3Bucket),
        S3Key = Option(s3Key),
        S3ObjectVersion = None,
        ZipFile = None
      ),
      Description = Option("Manages settings at Cloudflare"),
      Handler = classOf[CloudflareHandler].getName,
      Runtime = Java8,
      MemorySize = Some(512),
      Role = `Fn::GetAtt`(Seq(role.name, "Arn")),
      Timeout = Some(60)
    )

    Template(
      Description = Some("cloudflare-lambda lambda function and supporting resources"),
      Parameters = Option(Seq(
        s3Bucket,
        s3Key
      )),
      Resources = Seq(role, lambda, key, alias),
      Conditions = None,
      Mappings = None,
      Routables = None,
      Outputs = Some(Seq(
        Output(
          "CloudflareLambda",
          "ARN of the Lambda that manages settings at Cloudflare",
          `Fn::GetAtt`(Seq(lambda.name, "Arn")),
          Some("CloudflareLambda")
        ),
        Output(
          "CloudflareKey",
          "KMS Key Alias for Cloudflare lambda",
          ResourceRef(alias)
        )
      ))
    )
  }
}
