package blobs

case class BlobId(
  hash:      String,
  filename:  String,
  extension: String,
  dir:       Option[String]
)
