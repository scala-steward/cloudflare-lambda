package com.dwolla.lambda.cloudflare.requests

import cats.data._
import cats.implicits._
import com.dwolla.awssdk.kms.KmsDecrypter
import com.dwolla.cloudflare.{CloudflareAuthorization, FutureCloudflareApiExecutor}
import com.dwolla.lambda.cloudflare.Exceptions.UnsupportedResourceType
import com.dwolla.lambda.cloudflare.requests.processors.{AccountMembership, ResourceRequestProcessor}
import com.dwolla.lambda.cloudflare.util.JValue._
import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse, MissingResourceProperties}
import org.json4s.{DefaultFormats, Formats, JValue}

import scala.concurrent.{ExecutionContext, Future, Promise}

class ResourceRequestFactory(implicit ec: ExecutionContext) {
  private[requests] val promisedCloudflareApiExecutor = Promise[FutureCloudflareApiExecutor]

  protected val processors: Map[String, ResourceRequestProcessor] = Map(
    "Custom::CloudflareAccountMembership" → new AccountMembership
  )

  protected lazy val kmsDecrypter = new KmsDecrypter

  protected implicit val formats: Formats = DefaultFormats ++ org.json4s.ext.JodaTimeSerializers.all

  protected def cloudflareApiExecutor(email: String, key: String): Future[FutureCloudflareApiExecutor] = {
    promisedCloudflareApiExecutor.success(new FutureCloudflareApiExecutor(CloudflareAuthorization(email, key)))
    promisedCloudflareApiExecutor.future
  }

  def process(input: CloudFormationCustomResourceRequest): Future[HandlerResponse] =
    (for {
      resourceProcessor ← EitherT.fromOption[Future](processors.get(input.ResourceType), UnsupportedResourceType(input.ResourceType))
      resourceProperties ← EitherT.fromOption[Future](input.ResourceProperties, MissingResourceProperties)
      executor ← decryptCloudflareCredentials(resourceProperties)
      output ← EitherT.right[Throwable](resourceProcessor.process(input.RequestType.toUpperCase(), input.PhysicalResourceId, resourceProperties)(executor))
    } yield output).valueOrF(Future.failed(_))

  def shutdown(): Unit = {
    promisedCloudflareApiExecutor.future.map(_.close())
  }

  private def decryptCloudflareCredentials(resourceProperties: Map[String, JValue]): EitherT[Future, Throwable, FutureCloudflareApiExecutor] = {
    val email = "CloudflareEmail"
    val key = "CloudflareKey"

    for {
      byteArrays ← EitherT.right[Throwable](kmsDecrypter.decryptBase64(email → resourceProperties(email), key → resourceProperties(key)))
      cfCredentials = byteArrays.mapValues(new String(_, "UTF-8"))
      executor ← EitherT.right[Throwable](cloudflareApiExecutor(cfCredentials(email), cfCredentials(key)))
    } yield executor
  }
}
