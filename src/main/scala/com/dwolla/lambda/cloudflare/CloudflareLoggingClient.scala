package com.dwolla.lambda.cloudflare

import cats.effect._
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.typelevel.ci._

object CloudflareLoggingClient {
  def apply[F[_]: Async]: Client[F] => Client[F] =
    Logger(logHeaders = true, logBody = true, (Headers.SensitiveHeaders + ci"X-Auth-Key").contains)
}
