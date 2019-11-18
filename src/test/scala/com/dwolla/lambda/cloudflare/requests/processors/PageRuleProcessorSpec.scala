package com.dwolla.lambda.cloudflare.requests.processors

import java.time.Instant

import cats.effect._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.cloudflare.domain.model.pagerules._
import com.dwolla.cloudflare.{PageRuleClient, ZoneClient}
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import fs2._
import _root_.io.circe.syntax._
import _root_.io.circe._
import com.dwolla.lambda.cloudflare.Exceptions._
import com.dwolla.lambda.cloudformation._
import org.specs2.matcher.IOMatchers
import com.dwolla.circe._
import com.dwolla.cloudflare.domain.model.Exceptions.AccessDenied
import com.dwolla.lambda.cloudflare.JsonObjectMatchers
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._

//noinspection Specs2Matchers
class PageRuleProcessorSpec extends Specification with IOMatchers with JsonObjectMatchers {

  trait Setup extends Scope {
    val zoneId = "zone-id".asInstanceOf[ZoneId]
    val pageRuleId = "page-rule-id".asInstanceOf[PageRuleId]

    def buildProcessor(fakePageRuleClient: PageRuleClient[IO] = new FakePageRuleClient,
                       fakeZoneClient: ZoneClient[IO] = new FakeZoneClient,
                      ): PageRuleProcessor[IO] =
      new PageRuleProcessor[IO](fakeZoneClient, fakePageRuleClient)(null)
  }

  "processing Creates" should {
    "handle a Create request successfully" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def create(zoneId: ZoneId, pageRule: PageRule): Stream[IO, PageRule] =
          Stream.emit(pageRule.copy(id = Option(pageRuleId)))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError(AccessDenied())
          }
      }
      private val processor = buildProcessor(fakePageRuleClient, fakeZoneClient)
      private val pageRule = PageRule(None, List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "PageRule" -> pageRule.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakePageRuleClient.buildUri(zoneId, pageRuleId)
          handlerResponse.data must haveKeyValuePair("created" -> pageRule.copy(id = Option(pageRuleId)).asJson)
      })
    }

    "gracefully handle the case where the PageRule returned by Cloudflare doesn't have an ID" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def create(zoneId: ZoneId, pageRule: PageRule): Stream[IO, PageRule] =
          Stream.emit(pageRule.copy(id = None))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError(AccessDenied())
          }
      }
      private val processor = buildProcessor(fakePageRuleClient, fakeZoneClient)
      private val pageRule = PageRule(None, List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "PageRule" -> pageRule.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== "Unknown PageRule ID"
          handlerResponse.data must haveKeyValuePair("created" -> pageRule.copy(id = None).asJson)
      })
    }

    "fail to create if a physical resource ID has already been specified" in new Setup {
      private val processor = buildProcessor()
      private val pageRule = PageRule(None, List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(CreateRequest, Option("physical-resource-id").map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
        "ZoneId" -> "zone-id".asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnexpectedPhysicalId("physical-resource-id"))))
    }
  }

  "processing Updates" should {
    "handle a normal update request successfully" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def update(zoneId: ZoneId, pageRule: PageRule): Stream[IO, PageRule] =
          Stream.emit(pageRule.copy(modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)))
      }
      private val processor = buildProcessor(fakePageRuleClient)
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(UpdateRequest, Option(fakePageRuleClient.buildUri(zoneId, pageRuleId)).map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakePageRuleClient.buildUri(zoneId, pageRuleId)
          handlerResponse.data must haveKeyValuePair("updated" -> pageRule.copy(modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)).asJson)
      })
    }

    "return the existing page rule ID if Cloudflare fails to return an ID for some reason" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def update(zoneId: ZoneId, pageRule: PageRule): Stream[IO, PageRule] =
          Stream.emit(pageRule.copy(id = None, modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)))
      }
      private val processor = buildProcessor(fakePageRuleClient)
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(UpdateRequest, Option(fakePageRuleClient.buildUri(zoneId, pageRuleId)).map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakePageRuleClient.buildUri(zoneId, pageRuleId)
          handlerResponse.data must haveKeyValuePair("updated" -> pageRule.copy(id = None, modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)).asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def parseUri(uri: String): Option[(ZoneId, PageRuleId)] = None
      }
      private val processor = buildProcessor(fakePageRuleClient)
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(UpdateRequest, Option("unparseable-value").map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri("unparseable-value"))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(UpdateRequest, None, JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(UpdateRequest))))
    }
  }

  "processing Deletes" should {
    "return the deleted ID" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def delete(zoneId: ZoneId, pageRuleId: String): Stream[IO, PageRuleId] =
          Stream.emit(pageRuleId).map(shapeless.tag[PageRuleIdTag][String])
      }
      private val processor = buildProcessor(fakePageRuleClient)
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(DeleteRequest, Option(fakePageRuleClient.buildUri(zoneId, pageRuleId)).map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakePageRuleClient.buildUri(zoneId, pageRuleId)
          handlerResponse.data must haveKeyValuePair("deleted" -> pageRuleId.asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakePageRuleClient = new FakePageRuleClient {
        override def parseUri(uri: String): Option[(ZoneId, PageRuleId)] = None
      }
      private val processor = buildProcessor(fakePageRuleClient)
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(DeleteRequest, Option("unparseable-value").map(tagPhysicalResourceId), JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri("unparseable-value"))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(DeleteRequest, None, JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(DeleteRequest))))
    }
  }

  "processing other types of requests" should {
    "fail" in new Setup {
      private val processor = buildProcessor()
      private val pageRule = PageRule(Option(pageRuleId), List.empty, List.empty, 1, PageRuleStatus.Active)

      private val output = processor.process(OtherRequestType("other-request-type"), None, JsonObject(
        "PageRule" -> pageRule.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnsupportedRequestType(OtherRequestType("other-request-type")))))
    }
  }

}

class FakePageRuleClient extends PageRuleClient[IO] {
  override def list(zoneId: ZoneId): Stream[IO, pagerules.PageRule] = Stream.raiseError(new NotImplementedError())
  override def getById(zoneId: ZoneId, pageRuleId: String): Stream[IO, pagerules.PageRule] = Stream.raiseError(new NotImplementedError())
  override def create(zoneId: ZoneId, pageRule: pagerules.PageRule): Stream[IO, pagerules.PageRule] = Stream.raiseError(new NotImplementedError())
  override def update(zoneId: ZoneId, pageRule: pagerules.PageRule): Stream[IO, pagerules.PageRule] = Stream.raiseError(new NotImplementedError())
  override def delete(zoneId: ZoneId, pageRuleId: String): Stream[IO, PageRuleId] = Stream.raiseError(new NotImplementedError())
}

class FakeZoneClient extends ZoneClient[IO] {
  override def getZoneId(domain: String): Stream[IO, ZoneId] = Stream.raiseError(new NotImplementedError())
}
