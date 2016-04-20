package images

import java.io.File
import java.nio.file.Files
import java.util.Base64

import akka.http.scaladsl.model.{ MediaType, MediaTypes }
import akka.http.scaladsl.server.directives.FileInfo
import blobs.{ BlobId, BlobsService }
import com.sksamuel.scrimage.filter.SharpenFilter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.{ Color, ScaleMethod, Image ⇒ ScrImage }
import images.protocol.{ Image, ImageRendered, ImagesError, ImagesFilter }
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ImagesService(dataStorage: ImagesDataStorage, blobsService: BlobsService) {
  val PreloadSize = 32

  def getImage(imageId: Image.Id): Future[Image] =
    dataStorage.get(imageId).recoverWith {
      case _ ⇒ Future.failed(ImagesError.NotFound)
    }

  def save(userId: String, info: FileInfo, file: File): Future[(Boolean, Image)] = {
    val scri = ScrImage.fromFile(file)
    val userIdPart = userId match {
      case id: String if id.length > 5 ⇒ id.substring(0, 6)
      case _                           ⇒ userId
    }
    val (w, h) = scri.dimensions
    val scriStream = scri.fit(PreloadSize, PreloadSize, Color.White, ScaleMethod.Bicubic).autocrop(Color.White).stream(PngWriter.MaxCompression)
    val preload = Base64.getEncoder.encodeToString(IOUtils.toByteArray(scriStream))

    blobsService.storeFile(info, file).flatMap { bid ⇒
      val imageId = bid.hash.substring(0, 6) + userIdPart + "/" + bid.filename.take(64)
      dataStorage.save(Image(
        id = imageId,
        blobId = bid,
        userId = userId,
        width = w,
        height = h,
        preload = preload,
        rendered = Set.empty,
        size = file.length(),
        mediaType = info.contentType.mediaType.toString(),
        dateCreated = DateTime.now()
      )).map(true → _).recoverWith {
        case e ⇒
          getImage(imageId).map(false → _)
      }
    }
  }

  def getImageFile(im: Image): Future[(MediaType.Binary, File)] =
    getFile(im.blobId)

  private def getFile(blobId: BlobId): Future[(MediaType.Binary, File)] =
    blobsService.retrieveFile(blobId).map { f ⇒
      (MediaTypes.forExtension(blobId.extension) match {
        case mt: MediaType.Binary ⇒
          mt
        case _ ⇒
          MediaTypes.`application/octet-stream`
      }) → f
    }

  def getModifiedImageFile(im: Image, width: Int, height: Int, mode: String): Future[(MediaType.Binary, File)] =
    im.rendered.find(r ⇒ r.width == width && r.height == height && r.mode == mode) match {
      case Some(r) ⇒
        getFile(r.blobId)
      case None ⇒
        getImageFile(im).flatMap {
          case (m, file) ⇒
            if (im.width <= width && im.height <= height) {
              Future.successful(m, file)
            } else {
              val tmp = Files.createTempFile("mod-" + im.blobId.filename, "tmp")
              val img = ScrImage.fromFile(file)
              val imgSized = mode match {
                case "fit" ⇒
                  img.fit(width, height, Color.White, ScaleMethod.Bicubic).autocrop(Color.White)
                case _ ⇒
                  img.cover(width, height, ScaleMethod.Bicubic)
              }
              val f = imgSized
                .filter(SharpenFilter)
                .output(tmp)(PngWriter.MaxCompression).toFile

              blobsService.storeFile(s"w${width}_h${height}_${mode}_" + im.blobId.filename, f).flatMap { bid ⇒
                val r = ImageRendered(width = width, height = height, mode = mode, blobId = bid, size = f.length(), at = DateTime.now())
                dataStorage.rendered(im.id, r).map(_ ⇒
                  MediaTypes.`image/png` → f)
              }
            }
        }
    }

  def deleteImage(im: Image): Future[Boolean] = {
    dataStorage.delete(im.id) zip dataStorage.count(ImagesFilter(blobId = Some(im.blobId))) flatMap {
      case (b, c) ⇒
        if (c > 0) {
          Future.successful(b)
        } else Future.traverse(im.rendered.map(_.blobId) + im.blobId)(blobsService.delete).map(_.forall(identity))
    }
  }
}
