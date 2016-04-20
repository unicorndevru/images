import java.nio.file.{FileSystems, Paths}

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import blobs.FilesService
import images.{ImagesHandler, ImagesService}
import utils.http.protocol.ApiError.AuthorizationFailedError

class ImagesHandlerSpec {

  import utils.http.ApiErrorHandler._


  private val imagesService = new ImagesService(ImagesDataStorageInMemory, new FilesService(System.getProperty("java.io.tmpdir")))

  lazy val route = Route.seal(new ImagesHandler(imagesService){
    val userAware: Directive1[Option[String]] =
      optionalHeaderValueByName("Authorization")
        .map(_.filter(_.startsWith("Id ")).map(_.stripPrefix("Id ")))


    def userStringIdRequired: Directive1[String] = userAware.flatMap {
      case Some(id) => provide(id)
      case None => failWith(AuthorizationFailedError)
    }
  }.route)

}
