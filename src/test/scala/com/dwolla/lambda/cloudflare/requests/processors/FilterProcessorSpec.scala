package com.dwolla.lambda.cloudflare.requests.processors

import fs2._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.cloudflare.domain.model.filters._
import _root_.io.circe._
import _root_.io.circe.syntax._
import cats.effect._
import cats.effect.testing.specs2.CatsEffect
import com.dwolla.circe._
import com.dwolla.cloudflare.domain.model.Exceptions.AccessDenied
import com.dwolla.cloudflare.{FilterClient, ZoneClient}
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudflare.JsonObjectMatchers
import feral.lambda.cloudformation._
import feral.lambda.cloudformation.CloudFormationRequestType._
import org.specs2.matcher.IOMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

//noinspection Specs2Matchers
class FilterProcessorSpec extends Specification with IOMatchers with JsonObjectMatchers with CatsEffect {

  trait Setup extends Scope {
    val zoneId = "zone-id".asInstanceOf[ZoneId]
    val filterId = "filter-id".asInstanceOf[FilterId]
    val filter = Filter(
      id = Option(filterId),
      expression = shapeless.tag[FilterExpressionTag][String]("(cf.bot_management.verified_bot)"),
      paused = false
    )

    def buildProcessor(fakeFilterClient: FilterClient[IO] = new FakeFilterClient,
                       fakeZoneClient: ZoneClient[IO] = new FakeZoneClient,
                      ): FilterProcessor[IO] =
      new FilterProcessor[IO](fakeZoneClient, fakeFilterClient)(null)
  }

  "processing Creates" should {
    "handle a Create request successfully" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def create(zoneId: ZoneId, filter: Filter): Stream[IO, Filter] =
          Stream.emit(filter.copy(id = Option(filterId)))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeFilterClient, fakeZoneClient)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "Filter" -> filter.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFilterClient.buildUri(zoneId, filterId).renderString
          handlerResponse.data must haveKeyValuePair("created" -> filter.copy(id = Option(filterId)).asJson)
      })
    }

    "gracefully handle the case where the Filter returned by Cloudflare doesn't have an ID" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def create(zoneId: ZoneId, filter: Filter): Stream[IO, Filter] =
          Stream.emit(filter.copy(id = None))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeFilterClient, fakeZoneClient)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "Filter" -> filter.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== "Unknown Filter ID"
          handlerResponse.data must haveKeyValuePair("created" -> filter.copy(id = None).asJson)
      })
    }

    "fail to create if a physical resource ID has already been specified" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(CreateRequest, PhysicalResourceId("physical-resource-id"), JsonObject(
        "Filter" -> filter.asJson,
        "ZoneId" -> "zone-id".asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnexpectedPhysicalId(PhysicalResourceId.unsafeApply("physical-resource-id")))))
    }
  }

  "processing Updates" should {
    "handle a normal update request successfully" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def update(zoneId: ZoneId, filter: Filter): Stream[IO, Filter] =
          Stream.emit(filter)
      }
      private val processor = buildProcessor(fakeFilterClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId(fakeFilterClient.buildUri(zoneId, filterId).renderString), JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFilterClient.buildUri(zoneId, filterId).renderString
          handlerResponse.data must haveKeyValuePair( "updated" -> filter.asJson)
      })
    }

    "return the existing filter ID if Cloudflare fails to return an ID for some reason" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def update(zoneId: ZoneId, filter: Filter): Stream[IO, Filter] =
          Stream.emit(filter.copy(id = None))
      }
      private val processor = buildProcessor(fakeFilterClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId(fakeFilterClient.buildUri(zoneId, filterId).renderString), JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFilterClient.buildUri(zoneId, filterId).renderString
          handlerResponse.data must haveKeyValuePair("updated" -> filter.copy(id = None).asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def parseUri(uri: String): Option[(ZoneId, FilterId)] = None
      }
      private val processor = buildProcessor(fakeFilterClient)

      private val output = processor.process(UpdateRequest, PhysicalResourceId("unparseable-value"), JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri(PhysicalResourceId("unparseable-value")))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(UpdateRequest, None, JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(UpdateRequest))))
    }
  }

  "processing Deletes" should {
    "return the deleted ID" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def delete(zoneId: ZoneId, filterId: String): Stream[IO, FilterId] =
          Stream.emit(filterId).map(shapeless.tag[FilterIdTag][String])
      }
      private val processor = buildProcessor(fakeFilterClient)

      private val output = processor.process(DeleteRequest, PhysicalResourceId(fakeFilterClient.buildUri(zoneId, filterId).renderString), JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse[Json]].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFilterClient.buildUri(zoneId, filterId).renderString
          handlerResponse.data must haveKeyValuePair("deleted" -> filterId.asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeFilterClient = new FakeFilterClient {
        override def parseUri(uri: String): Option[(ZoneId, FilterId)] = None
      }
      private val processor = buildProcessor(fakeFilterClient)

      private val output = processor.process(DeleteRequest, PhysicalResourceId("unparseable-value"), JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri(PhysicalResourceId("unparseable-value")))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(DeleteRequest, None, JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(DeleteRequest))))
    }
  }

  "processing other types of requests" should {
    "fail" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(OtherRequestType("other-request-type"), None, JsonObject(
        "Filter" -> filter.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnsupportedRequestType(OtherRequestType("other-request-type")))))
    }
  }

}

class FakeFilterClient extends FilterClient[IO] {
  override def list(zoneId: ZoneId): Stream[IO, filters.Filter] = Stream.raiseError[IO](new NotImplementedError())
  override def getById(zoneId: ZoneId, filterId: String): Stream[IO, filters.Filter] = Stream.raiseError[IO](new NotImplementedError())
  override def create(zoneId: ZoneId, filter: filters.Filter): Stream[IO, filters.Filter] = Stream.raiseError[IO](new NotImplementedError())
  override def update(zoneId: ZoneId, filter: filters.Filter): Stream[IO, filters.Filter] = Stream.raiseError[IO](new NotImplementedError())
  override def delete(zoneId: ZoneId, filterId: String): Stream[IO, FilterId] = Stream.raiseError[IO](new NotImplementedError())
}
