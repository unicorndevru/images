package images

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{ Directives, ExceptionHandler }
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import images.protocol.ImagesError
import play.api.libs.json.{ Json, OWrites, Writes }

object ImagesExceptionHandler extends PlayJsonSupport {
  implicit val w: Writes[ImagesError] = OWrites[ImagesError]{ e ⇒
    Json.obj(
      "code" → e.code,
      "desc" → e.desc
    )
  }

  val generic = ExceptionHandler {
    case e: ImagesError ⇒
      Directives.complete(StatusCodes.custom(e.status, e.desc) → e)
  }
}
