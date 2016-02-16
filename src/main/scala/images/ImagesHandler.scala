package images

import java.io.File

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ EntityTag, Accept }
import akka.http.scaladsl.server.{ Directive, Directive1 }
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.FileIO
import images.protocol.{ ImagesError, ImagesJsonFormats, Image }
import utils.http.json.PlayJsonSupport

import scala.concurrent.ExecutionContext

abstract class ImagesHandler(imagesService: ImagesService)(implicit ctx: ExecutionContext) extends PlayJsonSupport with ImagesJsonFormats {
  def userStringIdRequired: Directive1[String]

  val NoContent = StatusCodes.NoContent → HttpEntity.empty(ContentTypes.NoContentType)

  private def md = java.security.MessageDigest.getInstance("MD5")

  private def md5Hex(str: String) = {
    md.digest(str.getBytes).map("%02x" format _).mkString
  }

  def getImageFile(image: Image): Directive[(MediaType.Binary, File)] =
    (parameters('w.as[Int], 'h.as[Int], 'm.as[String].?).tmap {
      case (w, h, m) ⇒
        imagesService.getModifiedImageFile(image, w, h, m)
    } | provide(imagesService.getImageFile(image))).flatMap(f ⇒ onSuccess(f))

  val modifyKeys = Set("w", "h", "m")

  val route = (pathPrefix("images") & extractJsonMarshallingContext) { implicit jsonCtx ⇒
    pathEndOrSingleSlash {
      (post & userStringIdRequired) { userId ⇒
        uploadedFile("data") {
          case (fileinfo, file) ⇒
            if (fileinfo.contentType.mediaType.isImage) {
              onSuccess(imagesService.save(userId, fileinfo, file)) {
                case (created, image) ⇒
                  complete((if (created) StatusCodes.Created else StatusCodes.OK) → image)
              }
            } else {
              failWith(ImagesError.NotAnImage)
            }
        }
      }
    } ~ path(Segment / Segment) { (o, i) ⇒
      val imageId = s"$o/$i"
      (get & optionalHeaderValueByType(classOf[Accept])) {
        case Some(a) if a.mediaRanges.forall(_.matches(MediaTypes.`application/json`)) ⇒
          conditional(EntityTag(md5Hex("json" + imageId))) {
            onSuccess(imagesService.getImage(imageId)) { image ⇒
              complete(image)
            }
          }
        case Some(a) if a.mediaRanges.forall(_.matches(MediaTypes.`text/html`)) ⇒
          conditional(EntityTag(md5Hex("html" + imageId))) {
            onSuccess(imagesService.getImage(imageId)) { image ⇒
              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<img src=\"data:image/png;base64," + image.preload + "\">"))
            }
          }
        case _ ⇒
          parameterMap { params ⇒
            val ps = params.filterKeys(modifyKeys).toSeq.map(kv ⇒ kv._1 + kv._2).sorted.mkString
            conditional(EntityTag(md5Hex(imageId + ps))) {
              onSuccess(imagesService.getImage(imageId)) { image ⇒
                getImageFile(image){ (m, file) ⇒
                  complete(HttpEntity(
                    m,
                    file.length,
                    FileIO.fromFile(file, chunkSize = 262144)
                  ))
                }
              }
            }
          }

      } ~ (delete & userStringIdRequired) { userId ⇒
        onSuccess(imagesService.getImage(imageId)) { image ⇒
          if (image.userId == userId) {
            onSuccess(imagesService.deleteImage(image)) { _ ⇒
              complete(NoContent)
            }
          } else {
            failWith(ImagesError.Forbidden)
          }
        }
      }
    }
  }
}