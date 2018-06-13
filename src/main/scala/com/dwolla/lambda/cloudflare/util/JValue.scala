package com.dwolla.lambda.cloudflare.util

import org.json4s.{Formats, JValue}

import scala.language.implicitConversions

object JValue {
  implicit def jvalueToString(jvalue: JValue)(implicit formats: Formats): String = jvalue.extract[String]
}
