package com.dwolla.lambda.cloudflare.requests

import _root_.io.circe._
import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import com.dwolla.cats._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.fs2aws.kms.KmsDecrypter
import com.dwolla.lambda.cloudflare.CloudflareLoggingClient
import com.dwolla.lambda.cloudflare.requests.processors._
import com.dwolla.lambda.cloudformation._
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import shapeless.tag._

import scala.concurrent.ExecutionContext

trait ResourceRequestFactory[F[_]] {
  def process: CloudFormationCustomResourceRequest => F[HandlerResponse]
}

object ResourceRequestFactory {
  def resource[F[_] : ConcurrentEffect : Parallel](executionContext: ExecutionContext): Resource[F, ResourceRequestFactory[F]] =
    for {
      http <- BlazeClientBuilder[F](executionContext).resource.map(CloudflareLoggingClient[F])
      kms <- KmsDecrypter.resource[F]()
    } yield new ResourceRequestFactoryImpl[F](http, kms)

  class ResourceRequestFactoryImpl[F[_] : Concurrent : Parallel](httpClientResource: Client[F],
                                                                 kmsClientResource: KmsDecrypter[F]) extends ResourceRequestFactory[F] {

    protected type ProcessorReader = Reader[StreamingCloudflareApiExecutor[F], ResourceRequestProcessor[F]]

    protected val processors: Map[String, ProcessorReader] = Map(
      "Custom::CloudflareAccountMembership" -> Reader(new AccountMembership(_)),
      "Custom::CloudflarePageRule" -> Reader(new PageRuleProcessor(_)),
      "Custom::CloudflareRateLimit" -> Reader(new RateLimitProcessor(_)),
      "Custom::CloudflareFirewallRule" -> Reader(new FirewallRuleProcessor(_))
    )

    val processorFor: Kleisli[F, ResourceType, ProcessorReader] = Kleisli { resourceType =>
      processors.get(resourceType).toRight(UnsupportedResourceType(resourceType)).liftTo[F]
    }

    private def findResourceProperty[T: Decoder](name: String): Kleisli[F, JsonObject, T] =
      Kleisli(_.apply(name).toRight(MissingResourceProperty(name)).flatMap(_.as[T]).liftTo[F])

    private val extractResourceProperties: Kleisli[F, CloudFormationCustomResourceRequest, JsonObject] =
      Kleisli(_.ResourceProperties.toRight(MissingResourceProperties).leftWiden[Throwable].liftTo[F])

    private val executorFromResourceProperties: Kleisli[F, JsonObject, StreamingCloudflareApiExecutor[F]] = {
      val decrypt: Kleisli[F, (EncryptedEmail, EncryptedKey), (Email, Key)] = Kleisli { case (emailCryptoText, keyCryptoText) =>
        kmsClientResource
          .decryptBase64("CloudflareEmail" -> emailCryptoText, "CloudflareKey" -> keyCryptoText)
          .map(_.mapValues(new String(_, "UTF-8")))
          .map(plaintextMap => (plaintextMap("CloudflareEmail"), plaintextMap("CloudflareKey")))
          .compile
          .lastOrError
          .map(_.bimap(shapeless.tag[EmailTag][String](_), shapeless.tag[KeyTag][String](_)))
      }

      val credsToAuthorization: ((Id[Email], Id[Key])) => CloudflareAuthorization =
        _.mapN(CloudflareAuthorization)

      val executorFromAuth: Kleisli[F, CloudflareAuthorization, StreamingCloudflareApiExecutor[F]] =
        Kleisli(new StreamingCloudflareApiExecutor[F](httpClientResource, _).pure[F])

      val extractEncryptedCredsFromInput: Kleisli[F, JsonObject, (EncryptedEmail, EncryptedKey)] =
        Kleisli.ask[F, JsonObject]
          .andThen {
            ("CloudflareEmail", "CloudflareKey")
              .bimap(findResourceProperty[EncryptedEmail], findResourceProperty[EncryptedKey])
              .parMapN((x, y) => (x, y))
          }

      val buildStreamingCloudflareApiExecutor: Kleisli[F, (EncryptedEmail, EncryptedKey), StreamingCloudflareApiExecutor[F]] =
        decrypt.map(credsToAuthorization).andThen(executorFromAuth)

      extractEncryptedCredsFromInput andThen buildStreamingCloudflareApiExecutor
    }

    override val process: CloudFormationCustomResourceRequest => F[HandlerResponse] =
      (for {
        resourceProcessor <- processorFor.local[CloudFormationCustomResourceRequest](_.ResourceType)
        (resourceProperties, executor) <- extractResourceProperties andThen executorFromResourceProperties.tapWithIdentity()
        output <- Kleisli.ask[F, CloudFormationCustomResourceRequest].flatMapF { input =>
          resourceProcessor(executor).process(input.RequestType, input.PhysicalResourceId, resourceProperties).compile.lastOrError
        }
      } yield output).run

  }

  private type EncryptedEmail = String @@ EncryptedEmailTag
  private type EncryptedKey = String @@ EncryptedKeyTag
  private type Email = String @@ EmailTag
  private type Key = String @@ KeyTag

  private trait EncryptedEmailTag
  private trait EncryptedKeyTag
  private trait EmailTag
  private trait KeyTag
}

case class MissingResourceProperty(name: String) extends RuntimeException(s"Expected to find resource property $name, but it was not found in the input.")
