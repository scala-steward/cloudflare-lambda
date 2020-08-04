package com.dwolla.lambda.cloudflare.requests.processors

import java.time.Instant

import cats.effect._
import com.dwolla.cloudflare.domain.model._
import com.dwolla.cloudflare.domain.model.firewallrules._
import com.dwolla.cloudflare.domain.model.filters._
import com.dwolla.cloudflare.{FirewallRuleClient, ZoneClient}
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
class FirewallRuleProcessorSpec extends Specification with IOMatchers with JsonObjectMatchers {

  trait Setup extends Scope {
    val zoneId = "zone-id".asInstanceOf[ZoneId]
    val firewallRuleId = "firewall-rule-id".asInstanceOf[FirewallRuleId]
    val filterId = "filter-id".asInstanceOf[FilterId]

    val firewallRule = FirewallRule(
      id = None,
      filter = FirewallRuleFilter(
        id = None,
        expression = Option(shapeless.tag[FilterExpressionTag][String]("(cf.bot_management.score lt 30)")),
        paused = Option(false)),
      action = Action.Log,
      priority = shapeless.tag[FirewallRulePriorityTag][Int](1),
      paused = false
    )

    def buildProcessor(fakeFirewallRuleClient: FirewallRuleClient[IO] = new FakeFirewallRuleClient,
                       fakeZoneClient: ZoneClient[IO] = new FakeZoneClient,
                      ): FirewallRuleProcessor[IO] =
      new FirewallRuleProcessor[IO](fakeZoneClient, fakeFirewallRuleClient)(null)
  }

  "processing Creates" should {
    "handle a Create request successfully" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def create(zoneId: ZoneId, firewallRule: FirewallRule): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(id = Option(firewallRuleId)))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeFirewallRuleClient, fakeZoneClient)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "FirewallRule" -> firewallRule.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)
          handlerResponse.data must haveKeyValuePair("created" -> firewallRule.copy(id = Option(firewallRuleId)).asJson)
      })
    }

    "gracefully handle the case where the FirewallRule returned by Cloudflare doesn't have an ID" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def create(zoneId: ZoneId, firewallRule: FirewallRule): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(id = None))
      }
      private val fakeZoneClient = new FakeZoneClient {
        override def getZoneId(domain: String): Stream[IO, ZoneId] =
          domain match {
            case "zone" => Stream.emit("zone-id").map(shapeless.tag[ZoneIdTag][String])
            case _ => Stream.raiseError[IO](AccessDenied())
          }
      }
      private val processor = buildProcessor(fakeFirewallRuleClient, fakeZoneClient)

      private val output = processor.process(CreateRequest, None, JsonObject(
        "FirewallRule" -> firewallRule.asJson,
        "Zone" -> "zone".asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== "Unknown FirewallRule ID"
          handlerResponse.data must haveKeyValuePair("created" -> firewallRule.copy(id = None).asJson)
      })
    }

    "fail to create if a physical resource ID has already been specified" in new Setup {
      private val processor = buildProcessor()

      private val output = processor.process(CreateRequest, Option("physical-resource-id").map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRule.asJson,
        "ZoneId" -> "zone-id".asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnexpectedPhysicalId("physical-resource-id"))))
    }
  }

  "processing Updates" should {
    "handle a normal update request successfully when the request's filter contains an id" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def update(zoneId: ZoneId, firewallRule: FirewallRule): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)))
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(
        id = Option(firewallRuleId),
        filter = firewallRule.filter.copy(id = Option(filterId))
      )

      private val output = processor.process(UpdateRequest, Option(fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)).map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)
          handlerResponse.data must haveKeyValuePair("updated" -> firewallRuleWithId.copy(modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)).asJson)
      })
    }

    "handle a normal update request successfully when the request's filter contains no id" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def getById(zoneId: ZoneId, firewallRuleId: String): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(filter = firewallRule.filter.copy(id = Option(filterId))))
        override def update(zoneId: ZoneId, firewallRule: FirewallRule): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)))
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(UpdateRequest, Option(fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)).map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)
          handlerResponse.data must haveKeyValuePair("updated" -> firewallRuleWithId.copy(
            filter = firewallRuleWithId.filter.copy(id = Option(filterId)),
            modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)).asJson)
      })
    }

    "return the existing firewall rule ID if Cloudflare fails to return an ID for some reason" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def update(zoneId: ZoneId, firewallRule: FirewallRule): Stream[IO, FirewallRule] =
          Stream.emit(firewallRule.copy(id = None, modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)))
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(
        id = Option(firewallRuleId),
        filter = firewallRule.filter.copy(id = Option(filterId))
      )

      private val output = processor.process(UpdateRequest, Option(fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)).map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)
          handlerResponse.data must haveKeyValuePair("updated" -> firewallRuleWithId.copy(id = None, modified_on = Option("2019-01-24T11:09:11.000000Z").map(Instant.parse)).asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def parseUri(uri: String): Option[(ZoneId, FirewallRuleId)] = None
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(UpdateRequest, Option("unparseable-value").map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri("unparseable-value"))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(UpdateRequest, None, JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(UpdateRequest))))
    }
  }

  "processing Deletes" should {
    "return the deleted ID" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def delete(zoneId: ZoneId, firewallRuleId: String): Stream[IO, FirewallRuleId] =
          Stream.emit(firewallRuleId).map(shapeless.tag[FirewallRuleIdTag][String])
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(DeleteRequest, Option(fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)).map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.last must returnValue(beSome[HandlerResponse].like {
        case handlerResponse =>
          handlerResponse.physicalId must_== fakeFirewallRuleClient.buildUri(zoneId, firewallRuleId)
          handlerResponse.data must haveKeyValuePair("deleted" -> firewallRuleId.asJson)
      })
    }

    "raise an error when the physical resource id cannot be parsed" in new Setup {
      private val fakeFirewallRuleClient = new FakeFirewallRuleClient {
        override def parseUri(uri: String): Option[(ZoneId, FirewallRuleId)] = None
      }
      private val processor = buildProcessor(fakeFirewallRuleClient)
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(DeleteRequest, Option("unparseable-value").map(tagPhysicalResourceId), JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(InvalidCloudflareUri("unparseable-value"))))
    }

    "raise an error when the physical resource id is missing" in new Setup {
      private val processor = buildProcessor()
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(DeleteRequest, None, JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(MissingPhysicalId(DeleteRequest))))
    }
  }

  "processing other types of requests" should {
    "fail" in new Setup {
      private val processor = buildProcessor()
      private val firewallRuleWithId = firewallRule.copy(id = Option(firewallRuleId))

      private val output = processor.process(OtherRequestType("other-request-type"), None, JsonObject(
        "FirewallRule" -> firewallRuleWithId.asJson,
      ))

      output.compile.toList.attempt must returnValue(equalTo(Left(UnsupportedRequestType(OtherRequestType("other-request-type")))))
    }
  }

}

class FakeFirewallRuleClient extends FirewallRuleClient[IO] {
  override def list(zoneId: ZoneId): Stream[IO, firewallrules.FirewallRule] = Stream.raiseError[IO](new NotImplementedError())
  override def getById(zoneId: ZoneId, firewallRuleId: String): Stream[IO, firewallrules.FirewallRule] = Stream.raiseError[IO](new NotImplementedError())
  override def create(zoneId: ZoneId, firewallRule: firewallrules.FirewallRule): Stream[IO, firewallrules.FirewallRule] = Stream.raiseError[IO](new NotImplementedError())
  override def update(zoneId: ZoneId, firewallRule: firewallrules.FirewallRule): Stream[IO, firewallrules.FirewallRule] = Stream.raiseError[IO](new NotImplementedError())
  override def delete(zoneId: ZoneId, firewallRuleId: String): Stream[IO, FirewallRuleId] = Stream.raiseError[IO](new NotImplementedError())
}
