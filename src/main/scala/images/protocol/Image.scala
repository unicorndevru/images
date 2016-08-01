package images.protocol

import java.time.Instant

import blobs.BlobId

case class Image(
  id:          Image.Id,
  blobId:      BlobId,
  userId:      String,
  mediaType:   String,
  width:       Int,
  height:      Int,
  preload:     String,
  rendered:    Set[ImageRendered],
  size:        Long,
  dateCreated: Instant
)

object Image {
  type Id = String
}