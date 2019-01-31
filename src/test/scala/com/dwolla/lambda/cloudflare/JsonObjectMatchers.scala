package com.dwolla.lambda.cloudflare

import io.circe.{Json, JsonObject}
import org.specs2.matcher.{MapMatchers, Matcher}

trait JsonObjectMatchers { self: MapMatchers =>
  def haveKeyValuePair(pairs: (String, Json)*): Matcher[JsonObject] = havePairs(pairs:_*) ^^ ((_: JsonObject).toMap)
}
