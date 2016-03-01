package images.protocol

import utils.http.protocol.ApiError

object ImagesError {
  case object FileNotFound extends ApiError("image.notFound", "Image file not found", 404)
  case object NotFound extends ApiError("image.notFound", "Image not found", 404)
  case object Forbidden extends ApiError("image.forbidden", "Image action forbidden", 403)
  case object NotAnImage extends ApiError("image.not", "Unsupported Media Type: not an image", 415)
}