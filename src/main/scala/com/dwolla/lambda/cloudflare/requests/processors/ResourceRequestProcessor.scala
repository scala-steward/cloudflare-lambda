package com.dwolla.lambda.cloudflare.requests.processors

import cats.effect.IO
import com.dwolla.cloudflare.StreamingCloudflareApiExecutor
import com.dwolla.lambda.cloudformation.HandlerResponse
import io.circe.Json
import fs2._

trait ResourceRequestProcessor {
  val executor: StreamingCloudflareApiExecutor[IO]
  def process(action: String, physicalResourceId: Option[String], properties: Map[String, Json]): Stream[IO, HandlerResponse]
}
