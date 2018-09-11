package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect._
import com.dwolla.cloudflare._
import com.dwolla.cloudflare.domain.model.ZoneId
import com.dwolla.cloudflare.domain.model.ratelimits.{RateLimit, RateLimitId}
import com.dwolla.lambda.cloudformation._
import com.dwolla.lambda.cloudformation.CloudFormationRequestType._
import fs2.Stream
import io.circe._
import io.circe.syntax._
import com.dwolla.lambda.cloudflare.Exceptions._
import io.circe.literal._
import com.dwolla.circe._

class RateLimitProcessor[F[_] : Sync](zoneClient: ZoneClient[F], rateLimitClient: RateLimitClient[F]) extends ResourceRequestProcessor[F] {

  import RateLimitProcessor._

  def this(executor: StreamingCloudflareApiExecutor[F]) = this(ZoneClient(executor), RateLimitClient(executor))

  override def process(action: CloudFormationRequestType,
                       physicalResourceId: Option[PhysicalResourceId],
                       properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      request <- parseRecordFrom[WrappedRateLimit](properties, "RateLimit")
      resp <- handleAction(action, request, physicalResourceId, properties)
    } yield resp

  private def handleAction(action: CloudFormationRequestType,
                           request: RateLimit,
                           physicalResourceId: Option[PhysicalResourceId],
                           properties: JsonObject,
                          ): Stream[F, HandlerResponse] = (action, physicalResourceId) match {
    case (CreateRequest, None) => handleCreate(request, properties)
    case (UpdateRequest, PhysicalResourceId(zid, prid)) => handleUpdate(zid, prid, request)
    case (DeleteRequest, PhysicalResourceId(zid, prid)) => handleDelete(zid, prid)
    case (CreateRequest, Some(id)) => Stream.raiseError(UnexpectedPhysicalId(id))
    case (_, Some(id)) => Stream.raiseError(InvalidCloudflareUri(id))
    case (UpdateRequest, None) | (DeleteRequest, None) => Stream.raiseError(MissingPhysicalId(action))
    case (OtherRequestType(_), _) ⇒ Stream.raiseError(UnsupportedRequestType(action))
  }

  private def handleCreate(request: RateLimit, properties: JsonObject): Stream[F, HandlerResponse] =
    for {
      zone <- parseRecordFrom[String](properties, "Zone")
      zoneId <- zoneClient.getZoneId(zone)
      resp <- rateLimitClient.create(zoneId, request)
    } yield HandlerResponse(tagPhysicalResourceId(resp.id.fold("Unknown RateLimit ID")(id => rateLimitClient.buildUri(zoneId, id))), JsonObject(
      "created" -> resp.asJson
    ))

  private def handleUpdate(zoneId: ZoneId, rateLimitId: RateLimitId, request: RateLimit): Stream[F, HandlerResponse] =
    for {
      resp <- rateLimitClient.update(zoneId, request.copy(id = Option(rateLimitId)))
    } yield HandlerResponse(tagPhysicalResourceId(rateLimitClient.buildUri(zoneId, resp.id.getOrElse(rateLimitId))), JsonObject(
      "updated" -> resp.asJson
    ))

  private def handleDelete(zoneId: ZoneId, rateLimitId: RateLimitId): Stream[F, HandlerResponse] =
    for {
      resp <- rateLimitClient.delete(zoneId, rateLimitId)
    } yield HandlerResponse(tagPhysicalResourceId(rateLimitClient.buildUri(zoneId, resp)), JsonObject(
      "deleted" -> resp.asJson
    ))

  private object PhysicalResourceId {
    def unapply(arg: Option[PhysicalResourceId]): Option[(ZoneId, RateLimitId)] =
      arg.flatMap(rateLimitClient.parseUri)
  }

  object RateLimitProcessor {

    case class WrappedRateLimit(rateLimit: RateLimit)

    object WrappedRateLimit {

      import com.dwolla.cloudflare.domain.model.ratelimits.RateLimit

      // Troposphere by default outputs booleans as strings in its resulting JSON output.
      // Because of this, we need to update the RateLimit circe decoder so the disabled field
      // can be decoded from strings in addition to normal boolean values.
      private def convertFieldToBoolean(cursor: ACursor, key: String) = {
        cursor.downField(key).withFocus(_.withString {
          case "true" => true.asJson
          case "false" => false.asJson
          case x => x.asJson
        }).up
      }

      private val rateLimitDecoder: Decoder[RateLimit] = RateLimit.rateLimitDecoder.instance
        .prepare(convertFieldToBoolean(_, "disabled"))

      implicit val wrappedRateLimitDecoder: Decoder[WrappedRateLimit] = c => {
        for {
          rateLimit <- rateLimitDecoder(c)
        } yield WrappedRateLimit(rateLimit)
      }

      implicit final def wrappedRateLimit2RateLimit(w: WrappedRateLimit): RateLimit = w.rateLimit
    }

  }
}