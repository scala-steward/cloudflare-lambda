//package com.dwolla.lambda.cloudflare
//
//import cats.effect._
//import cats.syntax.all._
//import com.dwolla.lambda.cloudflare.requests.ResourceRequestFactory
//import feral.lambda.cloudformation._
//import io.circe.{Json, JsonObject}
//import io.circe.syntax._
//import org.specs2.matcher.IOMatchers
//import org.specs2.mutable.Specification
//import org.typelevel.log4cats.Logger
//
//class CloudflareHandlerSpec extends Specification with IOMatchers {
//  "handleRequest" should {
//    "send to ResourceRequestFactory to process" >> {
//      val request = CloudFormationCustomResourceRequest(
//        RequestType = CloudFormationRequestType.CreateRequest,
//        ResponseURL = "",
//        StackId = "".asInstanceOf[StackId],
//        RequestId = "".asInstanceOf[RequestId],
//        ResourceType = "Custom::CloudflareAccountMembership".asInstanceOf[ResourceType],
//        LogicalResourceId = "".asInstanceOf[LogicalResourceId],
//        PhysicalResourceId = None,
//        ResourceProperties = None,
//        OldResourceProperties = None
//      )
//
//      val response = HandlerResponse(PhysicalResourceId.unsafeApply("1"), None)
//      val mockFactory: ResourceRequestFactory[IO] = new ResourceRequestFactory[IO] {
//        override def process(req: CloudFormationCustomResourceRequest[JsonObject]): IO[HandlerResponse[Json]] =
//          IO.pure(response.copy(data = req.asJson.some))
//
//      }
//
//      val handler = new CloudflareHandler[IO] {
//        override protected def resourceRequestFactoryResource(implicit L: Logger[IO]): Resource[IO, ResourceRequestFactory[IO]] =
//          Resource.pure(mockFactory)
//      }
//
//      val output = handler.handleRequest(request)
//      output should returnValue(response.copy(data = request.asJsonObject))
//    }
//  }
//}
