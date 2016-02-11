package images

import images.protocol.{ ImagesFilter, Image }

import scala.concurrent.Future

trait ImagesDataStorage {
  def get(imageId: Image.Id): Future[Image]
  def save(image: Image): Future[Image]
  def delete(imageId: Image.Id): Future[Boolean]
  def query(filter: ImagesFilter, offset: Int, limit: Int): Future[Seq[Image]]
  def count(filter: ImagesFilter): Future[Int]
}
