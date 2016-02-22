package images.protocol

import blobs.BlobId
import org.joda.time.DateTime

case class ImageRendered(width: Int, height: Int, mode: String, blobId: BlobId, size: Long, dateCreated: DateTime)
