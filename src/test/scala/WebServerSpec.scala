/**
  * Akka Http routes whitelisting PoC
  *
  * Assuming there is a [[WebServerSpec.someProtection]] directive, that protects its inner route,
  * the safety harness here ensures that no response will have way out of the server unless it is
  * 'protected' aka wrapped with the protection directive.
  *
  * The idea is to add intermediate http response header for each explicitly 'protected' route
  * (compare [[WebServerSpec.someProtection]] to [[WebServerSpec.safeProtection]]),
  * 'whitelisting' the route, and reject responses lacking this header at top-most common directive
  * (see [[SafetyHarness.dropNotWhitelistedResponses]]).
  */

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.Attributes.LogLevels
import org.scalatest.{Matchers, WordSpec}

import scala.collection.immutable

object SafetyHarness {

  /**
    * Signals that the response was rejected because the route it originated from is not whitelisted.
    * Rejection created by [[dropNotWhitelistedResponses]].
    */
  final case class RouteNotWhitelistedRejection(request: HttpRequest) extends Rejection

  val `Whitelisted` = RawHeader("Whitelisted", "yes")

  /**
    * Rejects response that is not whitelisted by [[whitelist]] directive.
    *
    * Removes [[`Whitelisted`]] header from the response.
    *
    * Uses [[RouteNotWhitelistedRejection]].
    */
  val dropNotWhitelistedResponses: Directive0 = extractRequestContext.flatMap { ctx ⇒
    mapRouteResultPF { case RouteResult.Complete(response) =>
      response.headers.groupBy(_.lowercaseName == `Whitelisted`.lowercaseName) match {
        case hs if hs.getOrElse(true, Seq()).nonEmpty =>
          val saneHeaders: immutable.Seq[HttpHeader] = hs.getOrElse(false, immutable.Seq())
          RouteResult.Complete(response.withHeaders(saneHeaders))
        case _ =>
          val rejections = immutable.Seq(RouteNotWhitelistedRejection(ctx.request))
          RouteResult.Rejected(rejections)
      }
    }
  }

  /**
    * Adds [[`Whitelisted`]] header to the response.
    *
    * By doing so, whitelists the response. See [[dropNotWhitelistedResponses]].
    */
  val whitelist: Directive0 = respondWithHeaders(`Whitelisted`)

  /**
    * Handles [[RouteNotWhitelistedRejection]] with 501 Not Implemented response.
    *
    * Logs rejected requests with ERROR level.
    */
  def myRejectionHandler: RejectionHandler =
    RejectionHandler.newBuilder()
      .handle { case SafetyHarness.RouteNotWhitelistedRejection(request) =>
        logRequest("Request not whitelisted, see [[SafetyHarness.whitelist]]", LogLevels.Error) {
          complete(HttpResponse(
            StatusCodes.NotImplemented,
            entity = "Request not whitelisted"
          ))
        }
      }
      .result()
}

class WebServerSpec extends WordSpec with Matchers with ScalatestRouteTest {

  val someProtection: Directive0 = Directive.Empty // Use proper protection directive here

  val safeProtection: Directive0 = SafetyHarness.whitelist & someProtection

  val safeRoute: Route = SafetyHarness.dropNotWhitelistedResponses {
    get {
      path("rogue") {
        complete("OK")
      } ~
        path("protected") {
          safeProtection {
            complete("OK")
          }
        }
    }
  }

  implicit val myRejectionHandler: RejectionHandler = SafetyHarness.myRejectionHandler

  "safeRoute" when {
    "route whitelisted" should {
      "allow to complete responses" in {
        Get("/protected") ~> safeRoute ~> check {
          status shouldEqual StatusCodes.OK
          headers shouldNot contain(SafetyHarness.`Whitelisted`)
        }
      }
    }

    "route not whitelisted" should {
      "reject responses" in {
        Get("/rogue") ~> safeRoute ~> check {
          rejection shouldBe a[SafetyHarness.RouteNotWhitelistedRejection]
        }
      }

      "handle RouteNotWhitelistedRejection rejections" in {
        Get("/rogue") ~> Route.seal(safeRoute) ~> check {
          status shouldEqual StatusCodes.NotImplemented
          responseAs[String] shouldEqual "Request not whitelisted"
        }
      }
    }
  }
}
