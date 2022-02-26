package com.dwolla.lambda.cloudflare.requests

import _root_.io.circe._
import cats.Invariant.catsInstancesForId
import cats._
import cats.data._
import cats.effect._
import cats.effect.instances.all._
import cats.syntax.all._
import cats.tagless.Derive
import cats.tagless.aop.Instrument
import com.dwolla.cats._
import com.dwolla.circe._
import com.dwolla.cloudflare._
import com.dwolla.fs2aws.kms.KmsAlg
import com.dwolla.lambda.cloudflare.CloudflareLoggingClient
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare.requests.processors._
import feral.lambda.cloudformation._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import shapeless.tag._

trait ResourceRequestFactory[F[_]] {
  def process(req: CloudFormationCustomResourceRequest[JsonObject]): F[HandlerResponse[Json]]
}

object ResourceRequestFactory {
  implicit val resourceRequestFactoryInstrument: Instrument[ResourceRequestFactory] = Derive.instrument

  def resource[F[_] : Async : Logger]: Resource[F, ResourceRequestFactory[F]] =
    for {
      http <- EmberClientBuilder.default[F].build.map(CloudflareLoggingClient[F])
      kms <- KmsAlg.resource[F]
    } yield new ResourceRequestFactoryImpl[F](http, kms)

  class ResourceRequestFactoryImpl[F[_] : Concurrent : Logger](httpClientResource: Client[F],
                                                               kmsClientResource: KmsAlg[F]) extends ResourceRequestFactory[F] {

    protected type ProcessorReader = Reader[StreamingCloudflareApiExecutor[F], ResourceRequestProcessor[F]]

    protected val processors: Map[ResourceType, ProcessorReader] = Map(
      ResourceType("Custom::CloudflareAccountMembership") -> Reader(new AccountMembership(_)),
      ResourceType("Custom::CloudflarePageRule") -> Reader(new PageRuleProcessor(_)),
      ResourceType("Custom::CloudflareRateLimit") -> Reader(new RateLimitProcessor(_)),
      ResourceType("Custom::CloudflareFirewallRule") -> Reader(new FirewallRuleProcessor(_)),
      ResourceType("Custom::CloudflareFilter") -> Reader(new FilterProcessor(_))
    )

    val processorFor: Kleisli[F, ResourceType, ProcessorReader] = Kleisli { resourceType =>
      processors.get(resourceType).toRight(UnsupportedResourceType(resourceType)).liftTo[F]
    }

    private def findResourceProperty[T: Decoder](name: String): Kleisli[F, JsonObject, T] =
      Kleisli(_.apply(name).toRight(MissingResourceProperty(name)).flatMap(_.as[T]).liftTo[F])

    private val extractResourceProperties: Kleisli[F, CloudFormationCustomResourceRequest[JsonObject], JsonObject] =
      Kleisli.ask[F, CloudFormationCustomResourceRequest[JsonObject]].map(_.ResourceProperties)

    private def decryptAs[Tag](s: String): F[String @@ Tag] =
      kmsClientResource.decrypt(s).map(shapeless.tag[Tag][String](_))

    private val executorFromResourceProperties: Kleisli[F, JsonObject, StreamingCloudflareApiExecutor[F]] = {
      val decrypt: Kleisli[F, (EncryptedEmail, EncryptedKey), (Email, Key)] = Kleisli {
        _
          .bimap(decryptAs[EmailTag], decryptAs[KeyTag])
          .parTupled
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

    override def process(req: CloudFormationCustomResourceRequest[JsonObject]): F[HandlerResponse[Json]] =
      (for {
        resourceProcessor <- processorFor.local[CloudFormationCustomResourceRequest[JsonObject]](_.ResourceType)
        (resourceProperties, executor) <- extractResourceProperties andThen executorFromResourceProperties.tapWithIdentity()
        output <- Kleisli.ask[F, CloudFormationCustomResourceRequest[JsonObject]].flatMapF { input =>
          resourceProcessor(executor).process(input.RequestType, input.PhysicalResourceId, resourceProperties).compile.lastOrError
        }
      } yield output).run(req)

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
