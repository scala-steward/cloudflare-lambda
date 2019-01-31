package com.dwolla.lambda.cloudflare
package requests.processors

import _root_.fs2._
import _root_.io.circe._
import cats._
import com.dwolla.lambda.cloudformation._

abstract class ResourceRequestProcessor[F[_]](implicit AE: ApplicativeError[F, Throwable]) {
  def process(action: CloudFormationRequestType, physicalResourceId: Option[PhysicalResourceId], properties: JsonObject): Stream[F, HandlerResponse]

  protected def parseRecordFrom[T : Decoder](resourceProperties: JsonObject, key: String): Stream[F, T] =
    Stream.fromEither[F](resourceProperties(key).toRight(MissingResourcePropertiesKey(key)).flatMap(_.as[T]))

}

case class MissingResourcePropertiesKey(key: String) extends RuntimeException(s"Tried to find the key $key in the request's ResourceProperties, but the key was missing")
