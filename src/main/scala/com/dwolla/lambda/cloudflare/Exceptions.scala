package com.dwolla.lambda.cloudflare

object Exceptions {
  case class UnsupportedResourceType(resourceType: String)
    extends RuntimeException(s"""Unsupported resource type of "$resourceType".""")

  case class MissingRoles(requestedRoles: List[String])
    extends RuntimeException(s"Matching roles could not be found in account: $requestedRoles")

  case class UnsupportedAction(action: String)
    extends RuntimeException(s"""Action "$action" not supported.""")
}
