package com.dwolla.lambda.cloudflare

import com.dwolla.lambda.cloudformation.{CloudFormationCustomResourceRequest, HandlerResponse, ParsedCloudFormationCustomResourceRequestHandler}

import scala.concurrent.{ExecutionContext, Future}

class CloudflareHandler(implicit ec: ExecutionContext) extends ParsedCloudFormationCustomResourceRequestHandler {
  override def handleRequest(input: CloudFormationCustomResourceRequest): Future[HandlerResponse] = ???

  override def shutdown(): Unit = ???
}
