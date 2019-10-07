package com.dwolla.lambda.cloudflare

object Exceptions {
  case class MissingRoles(requestedRoles: List[String])
    extends RuntimeException(s"Matching roles could not be found in account: $requestedRoles")

  case class AccountIdMismatch(requestAccountId: String, physicalResourceId: String)
    extends RuntimeException(s"Mismatched accounts: $requestAccountId does not match the Account ID in $physicalResourceId")

  case class AccountMemberNotFound(physicalResourceId: Option[String])
    extends RuntimeException(s"Account member not found at $physicalResourceId.")

  case object RefusingToChangeEmailAddress extends RuntimeException("Unable to update email address for account member")

  case class RateLimitNotFound(physicalResourceId: Option[String])
    extends RuntimeException(s"Rate limit not found at $physicalResourceId.")

  case class ZoneNotFound(domain: String)
    extends RuntimeException(s"Zone not found for domain $domain")

  case class InvalidCloudflareUri(physicalResourceId: String)
    extends RuntimeException(s"The physical resource id $physicalResourceId does not match the URL pattern for a Cloudflare resource")
}
