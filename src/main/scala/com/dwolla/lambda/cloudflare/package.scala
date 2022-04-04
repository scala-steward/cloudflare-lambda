package com.dwolla.lambda

import fs2.Stream
import org.typelevel.log4cats.Logger

package object cloudflare {
  implicit def streamLogger[F[_] : Logger]: Logger[Stream[F, *]] = Logger[F].mapK(Stream.functionKInstance)
}
