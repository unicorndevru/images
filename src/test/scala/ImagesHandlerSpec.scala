import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{ MediaTypes, _ }
import akka.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{ RouteTestTimeout, ScalatestRouteTest }
import akka.stream.scaladsl.Source
import blobs.FilesService
import images.protocol.ImagesError
import images.{ ImagesHandler, ImagesService }
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest._
import org.scalatest.time.{ Seconds, Span }
import play.api.libs.json.JsObject
import utils.http.json.PlayJsonSupport
import utils.http.protocol.ApiError.AuthorizationFailedError

import scala.concurrent.duration.FiniteDuration

class ImagesHandlerSpec extends WordSpec with ScalatestRouteTest with Matchers with ScalaFutures with TryValues
    with OptionValues with BeforeAndAfter with Eventually with PlayJsonSupport {

  implicit override val patienceConfig = PatienceConfig(timeout = Span(5000, Seconds))
  implicit val routeTimeout = RouteTestTimeout(FiniteDuration(5000, TimeUnit.SECONDS))

  import utils.http.ApiErrorHandler._

  private val imagesService = new ImagesService(ImagesDataStorageInMemory, new FilesService(System.getProperty("java.io.tmpdir")))

  lazy val route = Route.seal(new ImagesHandler(imagesService) {
    val userAware: Directive1[Option[String]] =
      optionalHeaderValueByName("Authorization")
        .map(_.filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer ")))

    def userStringIdRequired: Directive1[String] = userAware.flatMap {
      case Some(id) ⇒ provide(id)
      case None     ⇒ failWith(AuthorizationFailedError)
    }
  }.route)

  s"Image handler should" should {
    "upload image and delete it after" in {

      val tokenHeader = Authorization(OAuth2BearerToken("userSuperPuperUser"))
      val image = new File(getClass.getResource("/picture.jpg").toURI)
      val entity = createRequestEntityWithFile(image)

      val imageJson = Post("/images").withEntity(entity).withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt = (imageJson \ "id").asOpt[String]
      imageIdOpt shouldBe defined
      val imageId = (imageJson \ "id").as[String]

      Delete(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      imagesService.getImage(imageId).failed.futureValue shouldBe ImagesError.NotFound
    }

    "show 404 if image already deleted" in {

      val tokenHeader = Authorization(OAuth2BearerToken("userSuperPuperUser"))
      val image = new File(getClass.getResource("/picture.jpg").toURI)
      val entity = createRequestEntityWithFile(image)

      val imageJson = Post("/images").withEntity(entity).withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt = (imageJson \ "id").asOpt[String]
      imageIdOpt shouldBe defined
      val imageId = (imageJson \ "id").as[String]

      Get(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.OK)
      }

      Delete(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      Get(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NotFound)
      }

      imagesService.getImage(imageId).failed.futureValue shouldBe ImagesError.NotFound
    }
  }

  private def createRequestEntityWithFile(file: File): RequestEntity = {
    require(file.exists())
    val formData =
      Multipart.FormData(
        Source.single(
          Multipart.FormData.BodyPart(
            "data",
            HttpEntity(ContentType(MediaTypes.`image/jpeg`), Files.readAllBytes(file.toPath)),
            Map("filename" → file.getName)
          )
        )
      )
    Marshal(formData).to[RequestEntity].futureValue
  }

}
