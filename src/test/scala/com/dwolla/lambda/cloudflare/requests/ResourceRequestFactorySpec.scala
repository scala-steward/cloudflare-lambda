package com.dwolla.lambda.cloudflare.requests

import cats.data.Reader
import cats.effect.IO
import com.dwolla.cloudflare.StreamingCloudflareApiExecutor
import com.dwolla.fs2aws.kms._
import com.dwolla.lambda.cloudflare.Exceptions.UnsupportedResourceType
import com.dwolla.lambda.cloudflare.requests.processors.ResourceRequestProcessor
import com.dwolla.lambda.cloudformation._
import io.circe.Json
import fs2._
import org.http4s.HttpService
import org.http4s.client.Client
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

class ResourceRequestFactorySpec(implicit ee: ExecutionEnv) extends Specification with Mockito {
  trait Setup extends Scope {
    val mockExecutor = mock[StreamingCloudflareApiExecutor[IO]]

    val mockKms = new FakeKms(Map("CloudflareEmail" → "cloudflare-account-email@dwollalabs.com", "CloudflareKey" → "fake-key").transform((_, value) ⇒ value.getBytes("UTF-8")))

    val mockClient = Client.fromHttpService(HttpService.empty[IO])
  }

  "process" should {
    "decrypt credentials and send request to processor" in new Setup {
      val request = buildRequest("Custom::Tester", Some(Map(
        "CloudflareEmail" → Json.fromString("cloudflare-account-email@dwollalabs.com"),
        "CloudflareKey" → Json.fromString("fake-key")
      )))

      val response = HandlerResponse("1")
      val fakeProcessor = new ResourceRequestProcessor {
        override val executor: StreamingCloudflareApiExecutor[IO] = mockExecutor
        override def process(action: String, physicalResourceId: Option[String], properties: Map[String, Json]): Stream[IO, HandlerResponse] =
          if (action != request.RequestType.toUpperCase || physicalResourceId != request.PhysicalResourceId || properties != request.ResourceProperties.get)
            Stream.raiseError(new RuntimeException(s"unexpected arguments: ($action, $physicalResourceId, $properties)"))
          else
            Stream.emit(response)
      }

      val factory = new ResourceRequestFactory(Stream.emit(mockClient), Stream.emit(mockKms)) {
        override protected val processors = Map(
          "Custom::Tester" → Reader(_ ⇒ fakeProcessor)
        )

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = mockExecutor
      }

      private val output = factory.process(request)
      output.compile.last.unsafeToFuture() must beSome(response).await
    }

    "throw exception if missing ResourceType" in new Setup {
      val request = buildRequest("Custom::MyType")

      val factory = new ResourceRequestFactory(Stream.empty, Stream.empty) {
        override protected val processors = Map.empty

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = {
          mockExecutor
        }
      }

      private val output = factory.process(request)

      output.compile.last.unsafeToFuture() must throwA[UnsupportedResourceType].like {
        case ex ⇒ ex.getMessage must_==
          """Unsupported resource type of "Custom::MyType"."""
      }.await
    }

    "throw exception if request missing ResourceProperties" in new Setup {
      val request = buildRequest("Custom::Tester")

      val fakeProcessor = new ResourceRequestProcessor {
        override val executor: StreamingCloudflareApiExecutor[IO] = mockExecutor
        override def process(action: String, physicalResourceId: Option[String], properties: Map[String, Json]): Stream[IO, HandlerResponse] = ???
      }

      val factory = new ResourceRequestFactory(Stream.empty, Stream.empty) {
        override protected val processors = Map(
          "Custom::Tester" → Reader(_ ⇒ fakeProcessor)
        )

        override def cloudflareExecutor(httpClient: Client[IO], email: String, key: String): StreamingCloudflareApiExecutor[IO] = {
          mockExecutor
        }
      }

      private val output = factory.process(request)

      output.compile.last.unsafeToFuture() must throwA(MissingResourceProperties).await
    }
  }

  private def buildRequest(resourceType: String, resourceProperties: Option[Map[String, Json]] = None) =
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

class FakeKms(decrypted: Map[String, Array[Byte]]) extends KmsDecrypter[IO] {
  override def decryptBase64(cryptoTexts: (String, String)*): Stream[IO, Map[String, Array[Byte]]] = Stream.emit(decrypted)
  override def decrypt[A](transformer: Transform[A], cryptoText: A): IO[Array[Byte]] = ???
  override def decrypt[A](transform: Transform[A], cryptoTexts: (String, A)*): Stream[IO, Map[String, Array[Byte]]] = ???
}
