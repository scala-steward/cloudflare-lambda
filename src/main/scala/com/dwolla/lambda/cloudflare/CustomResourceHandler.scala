package com.dwolla.lambda.cloudflare

import com.dwolla.lambda.cloudformation.AbstractCustomResourceHandler

import scala.concurrent.ExecutionContext

class CustomResourceHandler extends AbstractCustomResourceHandler {
  override def createParsedRequestHandler() = new CloudflareHandler

  override implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
}

