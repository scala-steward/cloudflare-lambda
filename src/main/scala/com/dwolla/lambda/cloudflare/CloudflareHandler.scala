package com.dwolla.lambda.cloudflare

import cats.data._
import cats.effect._
import com.dwolla.fs2aws.kms.KmsDecrypter
import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation.{CatsAbstractCustomResourceHandler, CloudFormationCustomResourceRequest, HandlerResponse}
import fs2.Stream
import org.http4s.client.Client
import org.http4s.client.blaze.Http1Client

import scala.concurrent.ExecutionContext.Implicits.global

class CloudflareHandler(httpClientStream: Stream[IO, Client[IO]], kmsClientStream: Stream[IO, KmsDecrypter[IO]]) extends CatsAbstractCustomResourceHandler[IO] {
  def this() = this(Http1Client.stream[IO](), KmsDecrypter.stream[IO]())

  protected lazy val resourceRequestFactory = new ResourceRequestFactory(httpClientStream, kmsClientStream)

  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    OptionT(resourceRequestFactory.process(req).compile.last)
      .getOrElseF(IO.raiseError(new RuntimeException("no response was created by the handler")))

}
