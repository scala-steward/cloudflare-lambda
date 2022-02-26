package com.dwolla.lambda.cloudflare

import io.circe.Json
import org.specs2.matcher.{MapMatchers, Matcher}

trait JsonObjectMatchers { self: MapMatchers =>
  def haveKeyValuePair(pairs: (String, Json)*): Matcher[Option[Json]] = (havePairs(pairs:_*) ^^ ((_: Json).asObject.map(_.toMap).getOrElse(Map.empty))) ^^ ((_: Option[Json]).getOrElse(Json.obj()))
}
