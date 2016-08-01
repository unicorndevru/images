package images.protocol

import java.time.Instant

import blobs.BlobId

case class ImageRendered(width: Int, height: Int, quality: Option[Int], mode: String, blobId: BlobId, size: Long, at: Instant) {
  lazy val key = s"w${width}_h${height}_$mode${quality.fold("")(q ⇒ "_" + q)}"
}
