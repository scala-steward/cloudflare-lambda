package com.dwolla.lambda.cloudflare

import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse}
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Future

class CloudflareHandlerSpec extends Specification with Mockito {
  implicit val ee: ExecutionEnv = ExecutionEnv.fromGlobalExecutionContext

  trait Setup extends Scope {
    val mockFactory = mock[ResourceRequestFactory]


    val handler = new CloudflareHandler() {
      override protected lazy val resourceRequestFactory = mockFactory
    }
  }

  "handleRequest" should {
    "send to ResourceRequestFactory to process" in new Setup {
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
      mockFactory.process(request) returns Future.apply(response)

      handler.handleRequest(request) must be_==(response).await
    }
  }

  "shutdown" should {
    "call shutdown on ResourceRequestFactory" in new Setup {
      handler.shutdown()

      there was one(mockFactory).shutdown()
    }
  }
}
