package com.dwolla.lambda.cloudflare.requests

import cats.data._
import cats.effect._
import com.dwolla.cloudflare._
import com.dwolla.fs2aws.kms.KmsDecrypter
import com.dwolla.lambda.cloudflare.Exceptions.UnsupportedResourceType
import com.dwolla.lambda.cloudflare.requests.processors._
import com.dwolla.lambda.cloudformation._
import io.circe.Json
import io.circe.fs2.decoder
import fs2._
import org.http4s.client.Client

class ResourceRequestFactory(httpClientStream: Stream[IO, Client[IO]], kmsClientStream: Stream[IO, KmsDecrypter[IO]]) {
  protected val processors: Map[String, Reader[StreamingCloudflareApiExecutor[IO], ResourceRequestProcessor]] = Map(
    "Custom::CloudflareAccountMembership" → Reader(new AccountMembership(_)),
  )

  protected def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] =
    new StreamingCloudflareApiExecutor[IO](httpClient, CloudflareAuthorization(email, key))

  private def constructCloudflareExecutor(resourceProperties: Map[String, Json]): Stream[IO, StreamingCloudflareApiExecutor[IO]] =
    for {
      (email, key) ← decryptSensitiveProperties(resourceProperties)
      httpClient: Client[IO] ← httpClientStream
    } yield cloudflareExecutor(httpClient, email, key)

  private def decryptSensitiveProperties(resourceProperties: Map[String, Json]): Stream[IO, (String, String)] =
    for {
      kmsClient ← kmsClientStream
      emailCryptoText ← Stream.emit(resourceProperties("CloudflareEmail")).covary[IO].through(decoder[IO, String])
      keyCryptoText ← Stream.emit(resourceProperties("CloudflareKey")).covary[IO].through(decoder[IO, String])
      plaintextMap ← kmsClient.decryptBase64("CloudflareEmail" → emailCryptoText, "CloudflareKey" → keyCryptoText).map(_.mapValues(new String(_, "UTF-8")))
      emailPlaintext = plaintextMap("CloudflareEmail")
      keyPlaintext = plaintextMap("CloudflareKey")
    } yield (emailPlaintext, keyPlaintext)

  def process(input: CloudFormationCustomResourceRequest): Stream[IO, HandlerResponse] =
    for {
      resourceProcessor ← Stream.fromEither[IO](processors.get(input.ResourceType).toRight(UnsupportedResourceType(input.ResourceType)))
      resourceProperties ← Stream.fromEither[IO](input.ResourceProperties.toRight(MissingResourceProperties))
      executor ← constructCloudflareExecutor(resourceProperties)
      res ← resourceProcessor(executor).process(input.RequestType.toUpperCase(), input.PhysicalResourceId, resourceProperties)
    } yield res
}
