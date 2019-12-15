package com.dwolla.lambda.cloudflare.requests

import _root_.fs2._
import _root_.io.circe._
import cats.data._
import cats.effect._
import com.dwolla.cloudflare.StreamingCloudflareApiExecutor
import com.dwolla.fs2aws.kms._
import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory.ResourceRequestFactoryImpl
import com.dwolla.lambda.cloudflare.requests.processors._
import com.dwolla.lambda.cloudformation._
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.syntax.all._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.IOMatchers
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope


class ResourceRequestFactorySpec(implicit ee: ExecutionEnv) extends Specification with Mockito with IOMatchers {
  implicit val ioContextShift = IO.contextShift(ee.executionContext)
  
  trait Setup extends Scope {
    val mockExecutor = mock[StreamingCloudflareApiExecutor[IO]]

    val mockKms = new FakeKms(Map("CloudflareEmail" -> "cloudflare-account-email@dwollalabs.com", "CloudflareKey" -> "fake-key").transform((_, value) => value.getBytes("UTF-8")))

    val mockClient = Client.fromHttpApp(HttpRoutes.empty[IO].orNotFound)

    val customResourceType = "Custom::Tester".asInstanceOf[ResourceType]
  }

  "process" should {
    "decrypt credentials and send request to processor" in new Setup {
      val request = buildRequest("Custom::Tester".asInstanceOf[ResourceType], Some(JsonObject(
        "CloudflareEmail" -> Json.fromString("cloudflare-account-email@dwollalabs.com"),
        "CloudflareKey" -> Json.fromString("fake-key")
      )))

      private val response = HandlerResponse(tagPhysicalResourceId("1"))
      private val fakeProcessor = new ResourceRequestProcessor[IO] {
        override def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[IO, HandlerResponse] =
          if (action != request.RequestType || physicalResourceId != request.PhysicalResourceId || properties != request.ResourceProperties.get)
            Stream.raiseError[IO](new RuntimeException(s"unexpected arguments: ($action, $physicalResourceId, $properties)"))
          else
            Stream.emit(response)
      }

      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms) {
        override protected val processors = Map(
          customResourceType -> Reader(_ => fakeProcessor)
        )

      }

      private val output = factory.process(request)
      output must returnValue(response)
    }

    "throw exception if missing ResourceType" in new Setup {
      val request = buildRequest(customResourceType)

      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms) {
        override protected val processors = Map.empty
      }

      private val output = factory.process(request)

      output.unsafeToFuture() must throwA(UnsupportedResourceType(customResourceType)).await
    }

    "throw exception if request missing ResourceProperties" in new Setup {
      val request = buildRequest("Custom::Tester".asInstanceOf[ResourceType])

      val fakeProcessor = new ResourceRequestProcessor[IO] {
        override def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[IO, HandlerResponse] = ???
      }

      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms) {
        override protected val processors = Map(
          customResourceType -> Reader(_ => fakeProcessor)
        )
      }

      private val output = factory.process(request)

      output.unsafeToFuture() must throwA(MissingResourceProperties).await
    }

    "return a AccountMembership for the CloudflareAccountMembership custom type" in new Setup {
      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms)

      private val output = factory.processorFor("Custom::CloudflareAccountMembership".asInstanceOf[ResourceType]).map(_(mockExecutor))

      output must returnValue(haveClass[AccountMembership[IO]])
    }

    "return a PageRuleProcessor for the CloudflarePageRule custom type" in new Setup {
      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms)

      private val output = factory.processorFor("Custom::CloudflarePageRule".asInstanceOf[ResourceType]).map(_(mockExecutor))

      output must returnValue(haveClass[PageRuleProcessor[IO]])
    }

    "return a FirewallRuleProcessor for the CloudflareFirewallRule custom type" in new Setup {
      val factory = new ResourceRequestFactoryImpl[IO](mockClient, mockKms)

      private val output = factory.processorFor("Custom::CloudflareFirewallRule".asInstanceOf[ResourceType]).map(_(mockExecutor))

      output must returnValue(haveClass[FirewallRuleProcessor[IO]])
    }

  }

  private def buildRequest(resourceType: ResourceType, resourceProperties: Option[JsonObject] = None) =
    CloudFormationCustomResourceRequest(
      RequestType = CloudFormationRequestType.CreateRequest,
      ResponseURL = "",
      StackId = "".asInstanceOf[StackId],
      RequestId = "".asInstanceOf[RequestId],
      ResourceType = resourceType,
      LogicalResourceId = "".asInstanceOf[LogicalResourceId],
      PhysicalResourceId = Some("1:4").map(tagPhysicalResourceId),
      ResourceProperties = resourceProperties,
      OldResourceProperties = None
    )
}

class FakeKms(decrypted: Map[String, Array[Byte]]) extends KmsDecrypter[IO] {
  override def decryptBase64(cryptoTexts: (String, String)*): Stream[IO, Map[String, Array[Byte]]] = Stream.emit(decrypted)
  override def decrypt[A](transformer: Transform[A], cryptoText: A): IO[Array[Byte]] = ???
  override def decrypt[A](transform: Transform[A], cryptoTexts: (String, A)*): Stream[IO, Map[String, Array[Byte]]] = ???
}
