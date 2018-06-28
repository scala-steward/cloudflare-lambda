package com.dwolla.lambda.cloudflare

import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation._
import fs2._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class CloudflareHandlerSpec(implicit ee: ExecutionEnv) extends Specification with Mockito {
  "handleRequest" should {
    "send to ResourceRequestFactory to process" >> {
      val request = CloudFormationCustomResourceRequest(
        RequestType = "CREATE",
        ResponseURL = "",
        StackId = "",
        RequestId = "",
        ResourceType = "Custom::CloudflareAccountMembership",
        LogicalResourceId = "",
        PhysicalResourceId = None,
        ResourceProperties = None,
        OldResourceProperties = None
      )

      val response = HandlerResponse("1")
      val mockFactory = new ResourceRequestFactory(Stream.empty, Stream.empty) {
        override def process(input: CloudFormationCustomResourceRequest) = Stream.emit(response)
      }

      val handler = new CloudflareHandler() {
        override protected lazy val resourceRequestFactory = mockFactory
      }

      val output = handler.handleRequest(request)
      output.unsafeToFuture() must be_==(response).await
    }
  }
}
