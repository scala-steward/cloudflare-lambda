package com.dwolla.lambda.cloudflare

import cats.effect._
import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation._

class CloudflareHandler extends IOCustomResourceHandler {
  protected lazy val resourceRequestFactory: Resource[IO, ResourceRequestFactory[IO]] =
    ResourceRequestFactory.resource[IO](scala.concurrent.ExecutionContext.global)

  override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
    resourceRequestFactory.use(_.process(req))
      .handleErrorWith {
        case ex: NoSuchElementException => IO.raiseError(new RuntimeException("no response was created by the handler", ex))
      }

}
