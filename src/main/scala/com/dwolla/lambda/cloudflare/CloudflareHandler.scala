package com.dwolla.lambda.cloudflare

import cats._
import cats.data._
import cats.effect._
import cats.effect.std.Random
import cats.effect.syntax.all._
import cats.syntax.all._
import cats.tagless.syntax.all._
import com.dwolla.lambda.cloudflare.CloudflareHandler.handleRequest
import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import feral.lambda._
import feral.lambda.cloudformation._
import io.circe.{Json, JsonObject}
import natchez._
import natchez.http4s.NatchezMiddleware
import natchez.xray._
import org.http4s.client.{Client, middleware}
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object CloudflareHandler {
  def handleRequest[F[_] : Monad](resourceRequestFactory: ResourceRequestFactory[F])
                                 (implicit L: LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]]): CloudFormationCustomResource[F, JsonObject, Json] = new CloudFormationCustomResource[F, JsonObject, Json] {
    private def handle: F[HandlerResponse[Json]] =
      for {
        event <- L.event
        resp <- resourceRequestFactory.process(event)
      } yield resp

    override def createResource(input: JsonObject): F[HandlerResponse[Json]] = handle
    override def updateResource(input: JsonObject): F[HandlerResponse[Json]] = handle
    override def deleteResource(input: JsonObject): F[HandlerResponse[Json]] = handle
  }
}

class CloudflareHandler[F[_] : Async] {
  def handler: Resource[F, LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]] => F[Option[INothing]]] =
    for {
      implicit0(logger: Logger[F]) <- Slf4jLogger.create[F].toResource
      implicit0(random: Random[F]) <- Random.scalaUtilRandom[F].toResource
      entryPoint <- XRayEnvironment[Resource[F, *]].daemonAddress.flatMap {
        case Some(addr) => XRay.entryPoint(addr)
        case None => XRay.entryPoint[F]()
      }
      client <- httpClient
      resourceRequestFactory <- resourceRequestFactoryResource
    } yield { implicit env: LambdaEnv[F, CloudFormationCustomResourceRequest[JsonObject]] =>
      TracedHandler(entryPoint, Kleisli { (span: Span[F]) =>
        CloudFormationCustomResource[Kleisli[F, Span[F], *], JsonObject, Json](tracedHttpClient(client, span), handleRequest(resourceRequestFactory.mapK(Kleisli.liftK))).run(span)
      })
    }

  protected def resourceRequestFactoryResource(implicit L: Logger[F]): Resource[F, ResourceRequestFactory[F]] =
    ResourceRequestFactory.resource[F]

  protected def httpClient: Resource[F, Client[F]] =
    EmberClientBuilder
      .default[F]
      .build
      .map(middleware.Logger[F](logHeaders = true, logBody = true))

  private def tracedHttpClient(client: Client[F], span: Span[F]): Client[Kleisli[F, Span[F], *]] =
    NatchezMiddleware.client(client.translate(Kleisli.liftK[F, Span[F]])(Kleisli.applyK(span)))

  /**
   * The XRay kernel comes from environment variables, so we don't need to extract anything from the incoming event
   */
  private implicit def kernelSource[Event]: KernelSource[Event] = KernelSource.emptyKernelSource


}

class Handler extends IOLambda[CloudFormationCustomResourceRequest[JsonObject], INothing] {
  override def handler: Resource[IO, LambdaEnv[IO, CloudFormationCustomResourceRequest[JsonObject]] => IO[Option[INothing]]] =
    new CloudflareHandler[IO].handler
}
