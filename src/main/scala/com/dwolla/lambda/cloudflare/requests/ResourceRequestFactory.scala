package com.dwolla.lambda.cloudflare.requests

import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.cloudflare._
import com.dwolla.fs2aws.kms.KmsDecrypter
import com.dwolla.lambda.cloudflare.requests.processors._
import com.dwolla.lambda.cloudformation._
import io.circe._
import io.circe.fs2.decoder
import _root_.fs2._
import org.http4s.client.Client

class ResourceRequestFactory[F[_] : Sync](httpClientStream: Stream[F, Client[F]], kmsClientStream: Stream[F, KmsDecrypter[F]]) {
  protected type ProcessorReader = Reader[StreamingCloudflareApiExecutor[F], ResourceRequestProcessor[F]]

  protected val processors: Map[String, ProcessorReader] = Map(
    "Custom::CloudflareAccountMembership" → Reader(new AccountMembership(_)),
    "Custom::CloudflarePageRule" -> Reader(new PageRuleProcessor(_)),
  )

  def processorFor(resourceType: ResourceType): Stream[F, Reader[StreamingCloudflareApiExecutor[F], ResourceRequestProcessor[F]]] =
    Stream.fromEither[F](processors.get(resourceType).toRight(UnsupportedResourceType(resourceType)))

  protected def cloudflareExecutor(httpClient: Client[F], email: String, key: String): StreamingCloudflareApiExecutor[F] =
    new StreamingCloudflareApiExecutor[F](httpClient, CloudflareAuthorization(email, key))

  private def constructCloudflareExecutor(resourceProperties: JsonObject): Stream[F, StreamingCloudflareApiExecutor[F]] =
    for {
      (email, key) ← decryptSensitiveProperties(resourceProperties)
      httpClient: Client[F] ← httpClientStream
    } yield cloudflareExecutor(httpClient, email, key)

  private def decryptSensitiveProperties(resourceProperties: JsonObject): Stream[F, (String, String)] =
    for {
      kmsClient ← kmsClientStream
      emailCryptoText ← Stream.emits(resourceProperties("CloudflareEmail").toSeq).covary[F].through(decoder[F, String])
      keyCryptoText ← Stream.emits(resourceProperties("CloudflareKey").toSeq).covary[F].through(decoder[F, String])
      plaintextMap ← kmsClient.decryptBase64("CloudflareEmail" → emailCryptoText, "CloudflareKey" → keyCryptoText).map(_.mapValues(new String(_, "UTF-8")))
      emailPlaintext = plaintextMap("CloudflareEmail")
      keyPlaintext = plaintextMap("CloudflareKey")
    } yield (emailPlaintext, keyPlaintext)

  def process(input: CloudFormationCustomResourceRequest): Stream[F, HandlerResponse] =
    for {
      resourceProcessor ← processorFor(input.ResourceType)
      resourceProperties ← Stream.fromEither[F](input.ResourceProperties.toRight(MissingResourceProperties))
      executor ← constructCloudflareExecutor(resourceProperties)
      res ← resourceProcessor(executor).process(input.RequestType, input.PhysicalResourceId, resourceProperties)
    } yield res
}
