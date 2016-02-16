package images

import java.io.File
import java.nio.file.Files
import java.util.Base64

import akka.http.scaladsl.model.{ MediaType, MediaTypes }
import akka.http.scaladsl.server.directives.FileInfo
import blobs.BlobsService
import com.sksamuel.scrimage.filter.SharpenFilter
import com.sksamuel.scrimage.nio.PngWriter
import com.sksamuel.scrimage.{ Color, Image ⇒ ScrImage, ScaleMethod }
import images.protocol.{ Image, ImagesError, ImagesFilter }
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
    val (w, h) = scri.dimensions
    val scriStream = scri.fit(PreloadSize, PreloadSize, Color.White, ScaleMethod.Bicubic).autocrop(Color.White).stream(PngWriter.MaxCompression)
    val preload = Base64.getEncoder.encodeToString(IOUtils.toByteArray(scriStream))
    blobsService.storeFile(info, file).flatMap { bid ⇒
      val imageId = bid.hash.substring(0, 6) + userId.substring(0, 6) + "/" + bid.filename.take(64)
      dataStorage.save(Image(
        id = imageId,
        blobId = bid,
        userId = userId,
        width = w,
        height = h,
        preload = preload,
        mediaType = info.contentType.mediaType.toString(),
        dateCreated = DateTime.now()
      )).map(true → _).recoverWith {
        case e ⇒
          getImage(imageId).map(false → _)
      }
    }
  }

  def getImageFile(im: Image): Future[(MediaType.Binary, File)] =
    blobsService.retrieveFile(im.blobId).map { f ⇒
      (MediaTypes.forExtension(im.blobId.extension) match {
        case mt: MediaType.Binary ⇒
          mt
        case _ ⇒
          MediaTypes.`application/octet-stream`
      }) → f
    }

  def getModifiedImageFile(im: Image, width: Int, heigth: Int, mode: Option[String]): Future[(MediaType.Binary, File)] =
    getImageFile(im).map {
      case (m, file) ⇒
        if (im.width <= width && im.height <= heigth) {
          (m, file)
        } else {
          val tmp = Files.createTempFile("mod-" + im.blobId.filename, "tmp")
          val img = ScrImage.fromFile(file)
          val imgSized = mode match {
            case Some("fit") ⇒
              img.fit(width, heigth, Color.White, ScaleMethod.Bicubic).autocrop(Color.White)
            case _ ⇒
              img.cover(width, heigth, ScaleMethod.Bicubic)
          }
          MediaTypes.`image/png` → imgSized
            .filter(SharpenFilter)
            .output(tmp)(PngWriter.MinCompression).toFile
        }
    }

  def deleteImage(im: Image): Future[Boolean] = {
    dataStorage.delete(im.id) zip dataStorage.count(ImagesFilter(blobId = Some(im.blobId))) flatMap {
      case (b, c) ⇒
        if (c > 0) {
          Future.successful(b)
        } else blobsService.delete(im.blobId)
    }
  }
}
