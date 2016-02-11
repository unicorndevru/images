package images.protocol

import blobs.BlobId
import org.joda.time.DateTime

case class Image(
  id:          Image.Id,
  blobId:      BlobId,
  userId:      String,
  mediaType:   String,
  width:       Int,
  height:      Int,
  dateCreated: DateTime
)

object Image {
  type Id = String
}