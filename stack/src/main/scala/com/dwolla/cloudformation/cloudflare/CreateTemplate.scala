package com.dwolla.cloudformation.cloudflare

import spray.json._

import scala.language.implicitConversions


object CreateTemplate extends App {
  val template = Stack.template()
  println(template.toJson.prettyPrint)
}
