import java.util.UUID

import blobs.BlobId
import images.ImagesDataStorage
import images.protocol.Image.Id
import images.protocol.{Image, ImageRendered, ImagesFilter}

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

object ImagesDataStorageInMemory extends ImagesDataStorage {
  private val storage: TrieMap[Id, Image] = TrieMap.empty[Id,Image]


  private def getFilter(filter: ImagesFilter): Image => Boolean = {
    val blobFilter: Image => Boolean = filter.blobId match {
      case Some(blobId) => _.blobId == blobId
      case None => _ => true
    }

    val userIdFilter: Image => Boolean = filter.userId match {
      case Some(userId) => _.userId == userId
      case None => _ => true
    }

    image: Image => blobFilter(image) && userIdFilter(image)
  }

  override def get(imageId: Id): Future[Image] = storage.get(imageId) match {
    case Some(image) => Future.successful(image)
    case None => Future.failed(new NoSuchElementException)
  }

  override def count(filter: ImagesFilter): Future[Int] = Future.successful(storage.values.count(getFilter(filter)))

  override def delete(imageId: Id): Future[Boolean] = {
    storage.remove(imageId) match {
      case Some(x) => Future.successful(true)
      case None => Future.successful(false)
    }
  }

  override def save(image: Image): Future[Image] = storage.put(UUID.randomUUID().toString, image) match {
    case Some(im) => Future.successful(im)
    case None => Future.failed(new Exception)
  }

  override def query(filter: ImagesFilter, offset: Int, limit: Int): Future[Seq[Image]] =
    Future.successful(storage.values.filter(getFilter(filter)).slice(offset, offset + limit).toSeq)

  //Does nothing.
  override def rendered(imageId: Id, rendered: ImageRendered): Future[Image] = get(imageId)
}
