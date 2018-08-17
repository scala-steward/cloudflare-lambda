# cloudflare-lambda

[![Travis](https://img.shields.io/travis/Dwolla/cloudflare-lambda.svg?style=flat-square)](https://travis-ci.org/Dwolla/cloudflare-lambda)
![license](https://img.shields.io/github/license/Dwolla/cloudflare-lambda.svg?style=flat-square)

CloudFormation custom resource Lambda to manage Cloudflare settings

## Deploy

To deploy the stack, ensure the required IAM roles exist (`DataEncrypter` and `cloudformation/deployer/cloudformation-deployer`), then deploy with `sbt`:

```ShellSession
sbt -DAWS_ACCOUNT_ID={your-account-id} publish stack/deploy
```

The `publish` task comes from [Dwolla’s S3 sbt plugin](https://github.com/Dwolla/sbt-s3-publisher), and the stack/deploy task comes from [Dwolla’s CloudFormation sbt plugin](https://github.com/Dwolla/sbt-cloudformation-stack).
