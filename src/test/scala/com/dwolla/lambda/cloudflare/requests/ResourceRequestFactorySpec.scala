package com.dwolla.lambda.cloudflare.requests

import com.dwolla.awssdk.kms.KmsDecrypter
import com.dwolla.cloudflare.FutureCloudflareApiExecutor
import com.dwolla.lambda.cloudflare.Exceptions.UnsupportedResourceType
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor
import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse, MissingResourceProperties}
import org.json4s.JValue
import org.json4s.JsonAST.JString
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import scala.concurrent.Future

class ResourceRequestFactorySpec extends Specification with Mockito {
  implicit val ee: ExecutionEnv = ExecutionEnv.fromGlobalExecutionContext

  trait Setup extends Scope {
    implicit val mockCloudflareApiExecutor = mock[FutureCloudflareApiExecutor]
    val mockKmsDecrypter = mock[KmsDecrypter]
    val mockProcessor = mock[ResourceRequestProcessor]

    mockKmsDecrypter.decryptBase64("CloudflareEmail" → "cloudflare-account-email@dwollalabs.com", "CloudflareKey" → "fake-key") returns Future.successful(Map("CloudflareEmail" → "cloudflare-account-email@dwollalabs.com", "CloudflareKey" → "fake-key").transform((_, value) ⇒ value.getBytes("UTF-8")))

    val factory = new ResourceRequestFactory() {
      override protected def cloudflareApiExecutor(email: String, key: String): Future[FutureCloudflareApiExecutor] = {
        if (!promisedCloudflareApiExecutor.isCompleted)
          promisedCloudflareApiExecutor.success(mockCloudflareApiExecutor)

        promisedCloudflareApiExecutor.future
      }

      override protected lazy val kmsDecrypter: KmsDecrypter = mockKmsDecrypter

      override protected val processors: Map[String, ResourceRequestProcessor] = Map(
        "Custom::Tester" → mockProcessor
      )
    }
  }

  "process" should {
    "decrypt credentials and send request to processor" in new Setup {
      val request = buildRequest("Custom::Tester", Some(Map(
        "CloudflareEmail" → JString("cloudflare-account-email@dwollalabs.com"),
        "CloudflareKey" → JString("fake-key")
      )))

      val response = HandlerResponse("1")
      mockProcessor.process(request.RequestType.toUpperCase(), request.PhysicalResourceId, request.ResourceProperties.get) returns Future.apply(response)

      val output = factory.process(request)
      output must be_==(response).await
    }

    "throw exception if missing ResourceType" in new Setup {
      val request = buildRequest("Custom::MyType")

      val output = factory.process(request)
      output must throwA(UnsupportedResourceType(request.ResourceType)).await
    }

    "throw exception if request missing ResourceProperties" in new Setup {
      val request = buildRequest("Custom::Tester")

      val output = factory.process(request)
      output must throwA(MissingResourceProperties).await
    }
  }

  private def buildRequest(resourceType: String, resourceProperties: Option[Map[String, JValue]] = None) =
    CloudFormationCustomResourceRequest(
      RequestType = "CREATE",
      ResponseURL = "",
      StackId = "",
      RequestId = "",
      ResourceType = resourceType,
      LogicalResourceId = "",
      PhysicalResourceId = Some("1:4"),
      ResourceProperties = resourceProperties,
      OldResourceProperties = None
    )
}
