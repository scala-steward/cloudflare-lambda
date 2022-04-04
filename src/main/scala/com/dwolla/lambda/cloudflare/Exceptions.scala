package com.dwolla.lambda.cloudflare

import feral.lambda.cloudformation._

object Exceptions {
  case class MissingRoles(requestedRoles: List[String])
    extends RuntimeException(s"Matching roles could not be found in account: $requestedRoles")

  case class AccountIdMismatch(requestAccountId: String, physicalResourceId: Option[PhysicalResourceId])
    extends RuntimeException(s"Mismatched accounts: $requestAccountId does not match the Account ID in $physicalResourceId")

  case class AccountMemberNotFound(physicalResourceId: Option[PhysicalResourceId])
    extends RuntimeException(s"Account member not found at $physicalResourceId.")

  case object RefusingToChangeEmailAddress extends RuntimeException("Unable to update email address for account member")

  case class RateLimitNotFound(physicalResourceId: Option[String])
    extends RuntimeException(s"Rate limit not found at $physicalResourceId.")

  case class ZoneNotFound(domain: String)
    extends RuntimeException(s"Zone not found for domain $domain")

  case class InvalidCloudflareUri(physicalResourceId: Option[PhysicalResourceId])
    extends RuntimeException(s"The physical resource id $physicalResourceId does not match the URL pattern for a Cloudflare resource")

  case class UnsupportedResourceType(resourceType: ResourceType)
    extends RuntimeException(s"""Unsupported resource type of "$resourceType".""")

  case class UnexpectedPhysicalId(physicalId: PhysicalResourceId)
    extends RuntimeException(s"A physical ID must not be provided, but received $physicalId")

  case class MissingPhysicalId(requestType: CloudFormationRequestType)
    extends RuntimeException(s"A physical ID must be provided to execute $requestType")

  case class UriNotRepresentableAsPhysicalResourceId(uri: String)
    extends RuntimeException(s"the URI $uri is not a valid PhysicalResourceId")

  case class UnsupportedRequestType(requestType: CloudFormationRequestType)
    extends RuntimeException(s"""Request Type "$requestType" not supported.""")
}
