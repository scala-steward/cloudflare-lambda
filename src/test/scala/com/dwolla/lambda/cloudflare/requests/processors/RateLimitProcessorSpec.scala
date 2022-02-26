package com.dwolla.lambda.cloudflare.requests.processors

import java.time.Duration
import cats.effect._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.cloudflare.domain.model.ratelimits._
import com.dwolla.cloudflare.{RateLimitClient, ZoneClient}
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import fs2._
import _root_.io.circe.syntax._
import _root_.io.circe._
import com.dwolla.lambda.cloudflare.Exceptions._
import org.specs2.matcher.IOMatchers
import com.dwolla.circe._
import com.dwolla.cloudflare.domain.model.Exceptions.AccessDenied
import com.dwolla.lambda.cloudflare.JsonObjectMatchers
import feral.lambda.cloudformation.CloudFormationRequestType._
import feral.lambda.cloudformation._

//noinspection Specs2Matchers
class RateLimitProcessorSpec extends Specification with IOMatchers with JsonObjectMatchers {

  trait Setup extends Scope {
    val zoneId: ZoneId = "zone-id".asInstanceOf[ZoneId]
    val rateLimitId: RateLimitId = "rate-limit-id".asInstanceOf[RateLimitId]
    val rateLimit: RateLimit = RateLimit(
      id = Option(rateLimitId),
      disabled = Some(false),
      description = Some("Test rate limit"),
      threshold = 60,
      period = Duration.ofSeconds(300),
      `match` = RateLimitMatch(
        request = RateLimitMatchRequest(
          url = "http://test.com/test"
        )
      ),
      action = Challenge
    )

    def buildProcessor(fakeRateLimitClient: RateLimitClient[IO] = new FakeRateLimitClient,
                       fakeZoneClient: ZoneClient[IO] = new FakeZoneClient,
                      ): RateLimitProcessor[IO] =
      new RateLimitProcessor(fakeZoneClient, fakeRateLimitClient)(null)
  }

  "process Create/Update" should {
    "handle a Create action successfully" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient() {
        override def create(zoneId: ZoneId, rateLimit: RateLimit): Stream[IO, RateLimit] =
          Stream.emit(rateLimit.copy(id = Option(rateLimitId)))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeRateLimitClient, fakeZoneClient)
      private val rateLimitWithNoId = rateLimit.copy(id = None)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "RateLimit" -> rateLimitWithNoId.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString
          handlerResponse.data must haveKeyValuePair("created" -> rateLimit.copy(id = Option(rateLimitId)).asJson)
      })
    }

    "gracefully handle the case where the RateLimit returned by Cloudflare doesn't have an ID" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def create(zoneId: ZoneId, rateLimit: RateLimit): Stream[IO, RateLimit] =
          Stream.emit(rateLimit.copy(id = None))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeRateLimitClient, fakeZoneClient)
      private val rateLimitWithNoId = rateLimit.copy(id = None)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "RateLimit" -> rateLimitWithNoId.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== "Unknown RateLimit ID"
          handlerResponse.data must haveKeyValuePair("created" -> rateLimit.copy(id = None).asJson)
      })
    }

    "fail to create if a physical resource ID has already been specified" in new Setup {
      private val processor = buildProcessor()
      private val rateLimitWithNoId = rateLimit.copy(id = None)

      private val output = processor.process(CreateRequest, PhysicalResourceId("physical-resource-id"), JsonObject(
        "RateLimit" -> rateLimitWithNoId.asJson,
        "ZoneId" -> "zone-id".asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnexpectedPhysicalId(PhysicalResourceId.unsafeApply("physical-resource-id")))))
    }
  }

  "processing Updates" should {
    "handle a normal update request successfully" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def update(zoneId: ZoneId, rateLimit: RateLimit): Stream[IO, RateLimit] =
          Stream.emit(rateLimit)
      }
      private val processor = buildProcessor(fakeRateLimitClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId(fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString), JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString
          handlerResponse.data must haveKeyValuePair("updated" -> rateLimit.asJson)
      })
    }

    "return the existing rate limit ID if Cloudflare fails to return an ID for some reason" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def update(zoneId: ZoneId, rateLimit: RateLimit): Stream[IO, RateLimit] =
          Stream.emit(rateLimit)
      }
      private val processor = buildProcessor(fakeRateLimitClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId(fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString), JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString
          handlerResponse.data must haveKeyValuePair("updated" -> rateLimit.asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def parseUri(uri: String): Option[(ZoneId, RateLimitId)] = None
      }
      private val processor = buildProcessor(fakeRateLimitClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId("unparseable-value"), JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri(PhysicalResourceId("unparseable-value")))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(UpdateRequest, None, JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(UpdateRequest))))
    }
  }

  "processing Deletes" should {
    "return the deleted ID" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def delete(zoneId: ZoneId, rateLimitId: String): Stream[IO, RateLimitId] =
          Stream.emit(rateLimitId).map(shapeless.tag[RateLimitIdTag][String])
      }
      private val processor = buildProcessor(fakeRateLimitClient)

      private val output = processor.process(DeleteRequest, PhysicalResourceId(fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString), JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeRateLimitClient.buildUri(zoneId, rateLimitId).renderString
          handlerResponse.data must haveKeyValuePair("deleted" -> rateLimitId.asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeRateLimitClient = new FakeRateLimitClient {
        override def parseUri(uri: String): Option[(ZoneId, RateLimitId)] = None
      }
      private val processor = buildProcessor(fakeRateLimitClient)

      private val output = processor.process(DeleteRequest, PhysicalResourceId("unparseable-value"), JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri(PhysicalResourceId("unparseable-value")))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(DeleteRequest, None, JsonObject(
        "RateLimit" -> rateLimit.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(DeleteRequest))))
    }

    "processing other types of requests" should {
      "fail" in new Setup {
        private val processor = buildProcessor()

        private val output = processor.process(OtherRequestType("other-request-type"), None, JsonObject(
          "RateLimit" -> rateLimit.asJson,
        ))

        output.compile.toList.attempt must returnValue(equalTo(Left(UnsupportedRequestType(OtherRequestType("other-request-type")))))
      }
    }
  }
}

class FakeRateLimitClient extends RateLimitClient[IO] {
  override def list(zoneId: ZoneId): Stream[IO, RateLimit] = Stream.raiseError[IO](new NotImplementedError())
  override def getById(zoneId: ZoneId, rateLimitId: String): Stream[IO, RateLimit] = Stream.raiseError[IO](new NotImplementedError())
  override def create(zoneId: ZoneId, rateLimit: ratelimits.RateLimit): Stream[IO, RateLimit] = Stream.raiseError[IO](new NotImplementedError())
  override def update(zoneId: ZoneId, rateLimit: ratelimits.RateLimit): Stream[IO, RateLimit] = Stream.raiseError[IO](new NotImplementedError())
  override def delete(zoneId: ZoneId, rateLimitId: String): Stream[IO, RateLimitId] = Stream.raiseError[IO](new NotImplementedError())
}
