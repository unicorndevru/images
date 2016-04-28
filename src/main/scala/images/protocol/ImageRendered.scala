package images.protocol

import blobs.BlobId
import org.joda.time.DateTime

case class ImageRendered(width: Int, height: Int, quality: Option[Int], mode: String, blobId: BlobId, size: Long, at: DateTime) {
  lazy val key = s"w${width}_h${height}_$mode${quality.fold("")(q ⇒ "_" + q)}"
}
