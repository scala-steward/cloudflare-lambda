package com.dwolla.lambda

import _root_.io.circe._
import cats._

package object cloudflare {

  implicit class ParseJsonAs(json: Json) {
    def parseAs[F[_], A: Decoder](implicit F: ApplicativeError[F, Throwable]) = F.fromEither(json.as[A])
  }

}
