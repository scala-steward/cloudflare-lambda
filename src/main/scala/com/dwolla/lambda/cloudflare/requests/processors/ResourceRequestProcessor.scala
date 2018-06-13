package com.dwolla.lambda.cloudflare.requests.processors

import com.dwolla.cloudflare.FutureCloudflareApiExecutor
import com.dwolla.lambda.cloudformation.HandlerResponse
import org.json4s.JValue

import scala.concurrent.Future

trait ResourceRequestProcessor {
  def process(action: String, physicalResourceId: Option[String], properties: Map[String, JValue])
             (implicit executor: FutureCloudflareApiExecutor): Future[HandlerResponse]
}
