package images.protocol

import play.api.libs.json.{ Json, OWrites, Writes }

trait ImagesJsonFormats {
  implicit val imageWrites: Writes[Image] = OWrites{ im ⇒
    Json.obj(
      "id" → im.id,
      "userId" → im.userId,
      "width" → im.width,
      "height" → im.height,
      "dateCreated" → im.dateCreated
    )
  }
}
