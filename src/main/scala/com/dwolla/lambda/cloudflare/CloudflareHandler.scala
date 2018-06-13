package com.dwolla.lambda.cloudflare

import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse, ParsedCloudFormationCustomResourceRequestHandler}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, implicitConversions, postfixOps, reflectiveCalls}

class CloudflareHandler(implicit ec: ExecutionContext) extends ParsedCloudFormationCustomResourceRequestHandler {
  protected lazy val resourceRequestFactory = new ResourceRequestFactory()

  override def handleRequest(input: CloudFormationCustomResourceRequest): Future[HandlerResponse] = {
    resourceRequestFactory.process(input)
  }

  override def shutdown(): Unit = {
    resourceRequestFactory.shutdown()
  }
}
