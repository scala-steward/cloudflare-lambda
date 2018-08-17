package com.dwolla.lambda

import _root_.fs2._
import _root_.io.circe._
import cats._
import cats.effect._

package object cloudflare {

  implicit class StreamFromEither(s: Stream.type) {
    def fromEither[F[_]] = new PartiallyAppliedFromEither[F]
  }

  implicit class ParseJsonAs(json: Json) {
    def parseAs[A](implicit d: Decoder[A]): IO[A] = IO.fromEither(json.as[A])
  }

}

package cloudflare {
  class PartiallyAppliedFromEither[F[_]] {
    def apply[A](either: Either[Throwable, A])
                (implicit ev: ApplicativeError[F, Throwable]): Stream[F, A] = {
      val _ = ev  // the evidence is only here to constrain F, but the compiler complains it's not used
      either.fold(Stream.raiseError[A], Stream.emit(_))
    }
  }
}
