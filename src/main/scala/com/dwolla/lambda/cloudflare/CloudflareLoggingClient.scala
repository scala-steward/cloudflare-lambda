package com.dwolla.lambda.cloudflare

import cats.effect._
import org.http4s._
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.http4s.syntax.all._

object CloudflareLoggingClient {
  def apply[F[_]: Concurrent]: Client[F] => Client[F] =
    Logger(logHeaders = true, logBody = true, (Headers.SensitiveHeaders + "X-Auth-Key".ci).contains)
}
