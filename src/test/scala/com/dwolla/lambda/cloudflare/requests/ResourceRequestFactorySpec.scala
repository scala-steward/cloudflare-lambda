package com.dwolla.lambda.cloudflare.requests

import cats.data._
import cats.effect._
import com.dwolla.cloudflare.StreamingCloudflareApiExecutor
import com.dwolla.fs2aws.kms._
import com.dwolla.lambda.cloudflare.requests.processors._
import com.dwolla.lambda.cloudformation._
import io.circe._
import _root_.fs2._
import org.http4s.HttpService
import org.http4s.client.Client
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class ResourceRequestFactorySpec(implicit ee: ExecutionEnv) extends Specification with Mockito {
  trait Setup extends Scope {
    val mockExecutor = mock[StreamingCloudflareApiExecutor[IO]]

    val mockKms = new FakeKms(Map("CloudflareEmail" -> "cloudflare-account-email@dwollalabs.com", "CloudflareKey" -> "fake-key").transform((_, value) => value.getBytes("UTF-8")))

    val mockClient = Client.fromHttpService(HttpService.empty[IO])

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
            Stream.raiseError(new RuntimeException(s"unexpected arguments: ($action, $physicalResourceId, $properties)"))
          else
            Stream.emit(response)
      }

      val factory = new ResourceRequestFactory[IO](Stream.emit(mockClient), Stream.emit(mockKms)) {
        override protected val processors = Map(
          customResourceType -> Reader(_ => fakeProcessor)
        )

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = mockExecutor
      }

      private val output = factory.process(request)
      output.compile.last.unsafeToFuture() must beSome(response).await
    }

    "throw exception if missing ResourceType" in new Setup {
      val request = buildRequest(customResourceType)

      val factory = new ResourceRequestFactory[IO](Stream.empty, Stream.empty) {
        override protected val processors = Map.empty

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = {
          mockExecutor
        }
      }

      private val output = factory.process(request)

      output.compile.last.unsafeToFuture() must throwA(UnsupportedResourceType(customResourceType)).await
    }

    "throw exception if request missing ResourceProperties" in new Setup {
      val request = buildRequest("Custom::Tester".asInstanceOf[ResourceType])

      val fakeProcessor = new ResourceRequestProcessor[IO] {
        override def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[IO, HandlerResponse] = ???
      }

      val factory = new ResourceRequestFactory[IO](Stream.empty, Stream.empty) {
        override protected val processors = Map(
          customResourceType -> Reader(_ => fakeProcessor)
        )

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = {
          mockExecutor
        }
      }

      private val output = factory.process(request)

      output.compile.last.unsafeToFuture() must throwA(MissingResourceProperties).await
    }

    "return a AccountMembership for the CloudflareAccountMembership custom type" in new Setup {
      val factory = new ResourceRequestFactory[IO](Stream.empty, Stream.empty) {
        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = mockExecutor
      }

      private val output = factory.processorFor("Custom::CloudflareAccountMembership".asInstanceOf[ResourceType]).map(_(mockExecutor))

      output.compile.last.unsafeToFuture() must beSome[ResourceRequestProcessor[IO]].like {
        case _: AccountMembership[IO] => success
      }.await
    }

    "return a PageRuleProcessor for the CloudflarePageRule custom type" in new Setup {
      val factory = new ResourceRequestFactory[IO](Stream.empty, Stream.empty) {
        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = mockExecutor
      }

      private val output = factory.processorFor("Custom::CloudflarePageRule".asInstanceOf[ResourceType]).map(_(mockExecutor))

      output.compile.last.unsafeToFuture() must beSome[ResourceRequestProcessor[IO]].like {
        case _: PageRuleProcessor[IO] => success
      }.await
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
