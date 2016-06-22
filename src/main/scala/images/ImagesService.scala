package images

import java.nio.file.{ Files, Path }
import java.util.Base64

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.directives.FileInfo
import akka.stream.{ IOResult, Materializer }
import akka.stream.scaladsl.{ FileIO, Sink, Source }
import blobs.{ BlobId, BlobsService }
import com.sksamuel.scrimage.filter.SharpenFilter
import com.sksamuel.scrimage.nio.{ ImageWriter, JpegWriter, PngWriter }
import com.sksamuel.scrimage.{ Color, ScaleMethod, Image ⇒ ScrImage }
import images.protocol.{ Image, ImageRendered, ImagesError, ImagesFilter }
import org.apache.commons.io.IOUtils
import org.joda.time.DateTime

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

class ImagesService(dataStorage: ImagesDataStorage, blobsService: BlobsService, otherHosts: Set[String] = Set.empty) {
  val PreloadSize = 32

  def getImage(imageId: Image.Id): Future[Image] =
    dataStorage.get(imageId).recoverWith {
      case _ ⇒ Future.failed(ImagesError.NotFound)
    }

  def save(userId: String, info: FileInfo, file: Path): Future[(Boolean, Image)] = {
    val scri = ScrImage.fromPath(file)
    val userIdPart = userId match {
      case id: String if id.length > 5 ⇒ id.substring(0, 6)
      case _                           ⇒ userId
    }
    val (w, h) = scri.dimensions
    val scriStream = scri.fit(PreloadSize, PreloadSize, Color.White, ScaleMethod.Bicubic).autocrop(Color.White).stream(PngWriter.MaxCompression)
    val preload = Base64.getEncoder.encodeToString(IOUtils.toByteArray(scriStream))

    blobsService.storeFile(info, file, "original").flatMap { bid ⇒
      val imageId = bid.hash.substring(0, 6) + userIdPart + "/" + bid.filename.take(64)
      dataStorage.save(Image(
        id = imageId,
        blobId = bid,
        userId = userId,
        width = w,
        height = h,
        preload = preload,
        rendered = Set.empty,
        size = Files.size(file),
        mediaType = info.contentType.mediaType.toString(),
        dateCreated = DateTime.now()
      )).map(true → _).recoverWith {
        case e ⇒
          getImage(imageId).map(false → _)
      }
    }
  }

  def getImageFile(im: Image): Future[(MediaType.Binary, Path)] =
    getFile(im.blobId)

  def checkImageExists(imageId: Image.Id): Future[Unit] =
    dataStorage.get(imageId).flatMap {
      im ⇒
        getFile(im.blobId).map(_ ⇒ ())
    }.recoverWith {
      case _ ⇒ Future.failed(ImagesError.NotFound)
    }

  protected def getFile(blobId: BlobId): Future[(MediaType.Binary, Path)] =
    blobsService.retrieveFile(blobId).map { f ⇒
      (MediaTypes.forExtension(blobId.extension) match {
        case mt: MediaType.Binary ⇒
          mt
        case _ ⇒
          MediaTypes.`application/octet-stream`
      }) → f
    }

  def getModifiedImageFile(im: Image, width: Int, height: Int, quality: Option[Int], mode: String): Future[(MediaType.Binary, Path)] = {
    val q = quality.filter(_ < 100).filter(_ > 0)
    (im.rendered.find(r ⇒ r.width == width && r.height == height && r.mode == mode && r.quality == q) match {
      case Some(r) ⇒
        getFile(r.blobId).map(v ⇒ Option(v)).recoverWith {
          case _ ⇒
            Future.successful(None)
        }
      case None ⇒
        Future.successful(None)
    }).flatMap {
      case Some(v) ⇒ Future.successful(v)
      case None ⇒
        getImageFile(im).flatMap {
          case (m, file) ⇒
            if (im.width <= width && im.height <= height && q.isEmpty) {
              Future.successful(m, file)
            } else {
              val tmp = Files.createTempFile("mod-" + im.blobId.filename, "tmp")
              val img = ScrImage.fromPath(file)
              val imgSized = mode match {
                case "fit" ⇒
                  img.fit(width, height, Color.White, ScaleMethod.Bicubic).autocrop(Color.White)
                case _ ⇒
                  img.cover(width, height, ScaleMethod.Bicubic)
              }
              val f = imgSized
                .filter(SharpenFilter)
                .output(tmp)(q.fold[ImageWriter](PngWriter.MaxCompression)(JpegWriter(_, progressive = false)))

              val ext = q.fold("png")(_ ⇒ "jpg")

              blobsService.storeFile(s"w${width}_h${height}_$mode${q.fold("")("_" + _)}_" + im.blobId.filename + "." + ext, f, "modified").flatMap { bid ⇒
                val r = ImageRendered(width = width, height = height, quality = q, mode = mode, blobId = bid, size = Files.size(f), at = DateTime.now())
                dataStorage.rendered(im.id, r).map(_ ⇒
                  quality.fold(MediaTypes.`image/png`)(_ ⇒ MediaTypes.`image/jpeg`) → f)
              }
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

object ImagesService {
  class Distributed(dataStorage: ImagesDataStorage, blobsService: BlobsService, otherSources: Set[String])(implicit system: ActorSystem, mat: Materializer) extends ImagesService(dataStorage, blobsService) {
    private val httpDownloadFlow = Http().superPool[Unit]()

    private def responseOrFail[T](in: (Try[HttpResponse], T)): (HttpResponse, T) = in match {
      case (responseTry, context) ⇒ (responseTry.get, context)
    }

    override def getImageFile(im: Image): Future[(MediaType.Binary, Path)] =
      getFile(im.blobId).recoverWith {
        case ImagesError.FileNotFound ⇒
          // find a host where image exists, or fail
          Future.traverse(otherSources.map(h ⇒ Uri(h + im.id)).map(uri ⇒ HttpRequest(uri = uri, method = HttpMethods.HEAD)))(req ⇒
            Http().singleRequest(req).map(resp ⇒ req.uri → resp.status.isSuccess())).map(_.find(_._2).map(_._1)).flatMap {
            case Some(uri) ⇒
              // for the first host with image, make request
              val file = Files.createTempFile("orig-" + im.blobId.filename, "tmp")
              def writeFile(httpResponse: HttpResponse): Future[IOResult] = {
                httpResponse.entity.dataBytes.runWith(FileIO.toPath(file))
              }
              val request = HttpRequest(uri = uri)
              val source = Source.single((request, ()))
              source.via(httpDownloadFlow)
                .map(responseOrFail)
                .map(_._1)
                .mapAsync(2)(writeFile)
                .runWith(Sink.ignore).flatMap {
                  _ ⇒
                    // save file with current blob id
                    blobsService.storeFile(im.blobId, im.blobId.filename, file).flatMap{ _ ⇒
                      getFile(im.blobId)
                    }
                }
            case None ⇒
              Future.failed(ImagesError.FileNotFound)
          }
      }
  }
}
