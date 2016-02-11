package images.protocol

class ImagesError(val code: String, val desc: String, val status: Int = 500) extends Throwable

object ImagesError {
  case object NotFound extends ImagesError("image.notFound", "Image not found", 404)
  case object Forbidden extends ImagesError("image.forbidden", "Image action forbidden", 403)
  case object NotAnImage extends ImagesError("image.not", "Unsupported Media Type: not an image", 415)
}