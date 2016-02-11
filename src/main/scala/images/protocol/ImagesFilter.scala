package images.protocol

import blobs.BlobId

case class ImagesFilter(userId: Option[String] = None, blobId: Option[BlobId] = None)