package com.dwolla.lambda.cloudflare

import cats.effect._
import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation._
import io.circe.syntax._
import org.specs2.matcher.IOMatchers
import org.specs2.mutable.Specification

class CloudflareHandlerSpec extends Specification with IOMatchers {
  "handleRequest" should {
    "send to ResourceRequestFactory to process" >> {
      val request = CloudFormationCustomResourceRequest(
        RequestType = CloudFormationRequestType.CreateRequest,
        ResponseURL = "",
        StackId = "".asInstanceOf[StackId],
        RequestId = "".asInstanceOf[RequestId],
        ResourceType = "Custom::CloudflareAccountMembership".asInstanceOf[ResourceType],
        LogicalResourceId = "".asInstanceOf[LogicalResourceId],
        PhysicalResourceId = None,
        ResourceProperties = None,
        OldResourceProperties = None
      )

      val response = HandlerResponse(tagPhysicalResourceId("1"))
      val mockFactory: ResourceRequestFactory[IO] = new ResourceRequestFactory[IO] {
        override def process: CloudFormationCustomResourceRequest => IO[HandlerResponse] = input =>
          IO.pure(response.copy(data = input.asJsonObject))
      }

      val handler = new CloudflareHandler() {
        override protected lazy val resourceRequestFactory: Resource[IO, ResourceRequestFactory[IO]] = Resource.pure(mockFactory)
      }

      val output = handler.handleRequest(request)
      output should returnValue(response.copy(data = request.asJsonObject))
    }
  }
}
