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
import images.protocol.{ ImagesError, ImagesFilter }
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
        .map(_.filter(_.startsWith("Bearer ")).map(_.stripPrefix("Bearer "))).filter(_.nonEmpty)

    def userStringIdRequired: Directive1[String] = userAware.flatMap {
      case Some(id) ⇒ provide(id)
      case None     ⇒ failWith(AuthorizationFailedError)
    }
  }.route)

  s"Image handler should" should {
    "reject upload if user id is empty" in {
      val image = new File(getClass.getResource("/picture.jpg").toURI)
      val entity = createRequestEntityWithFile(image)

      Post("/images").withEntity(entity) ~> route ~> check {
        status should be(StatusCodes.NotFound)
      }
    }

    "upload image and delete it after even if user id smaller then 6 chars" in {

      val tokenHeader = Authorization(OAuth2BearerToken("user"))
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
        contentType should be(ContentType(MediaTypes.`image/jpeg`))
      }

      Get(s"/images/$imageId?w=12.34&h=23.32").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.OK)
        contentType should be(ContentType(MediaTypes.`image/png`))
      }
      Get(s"/images/$imageId?w=12.34&h=23.32&q=12").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.OK)
        contentType should be(ContentType(MediaTypes.`image/jpeg`))
      }

      Delete(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      Get(s"/images/$imageId").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NotFound)
      }

      imagesService.getImage(imageId).failed.futureValue shouldBe ImagesError.NotFound
    }

    "handle multiple files uploading and deleting" in {

      val tokenHeader = Authorization(OAuth2BearerToken("user"))
      val tokenHeader2 = Authorization(OAuth2BearerToken("user2"))
      val image = new File(getClass.getResource("/picture.jpg").toURI)
      val image2 = new File(getClass.getResource("/picture2.png").toURI)
      val entity = createRequestEntityWithFile(image)
      val entity2 = createRequestEntityWithFile(image2)

      val imageJson1 = Post("/images").withEntity(entity).withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt1 = (imageJson1 \ "id").asOpt[String]
      imageIdOpt1 shouldBe defined
      val imageId1 = (imageJson1 \ "id").as[String]

      val imageJson2 = Post("/images").withEntity(entity2).withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt2 = (imageJson2 \ "id").asOpt[String]
      imageIdOpt2 shouldBe defined
      val imageId2 = (imageJson2 \ "id").as[String]

      Get(s"/images/$imageId2") ~> route ~> check {
        status should be(StatusCodes.OK)
        contentType should be(ContentType(MediaTypes.`image/png`))
      }
      Get(s"/images/$imageId2?q=12") ~> route ~> check {
        status should be(StatusCodes.OK)
        contentType should be(ContentType(MediaTypes.`image/jpeg`))
      }

      val imageJson3 = Post("/images").withEntity(entity).withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt3 = (imageJson3 \ "id").asOpt[String]
      imageIdOpt3 shouldBe defined
      val imageId3 = (imageJson3 \ "id").as[String]

      val imageJson4 = Post("/images").withEntity(entity2).withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.Created)
        responseAs[JsObject]
      }

      val imageIdOpt4 = (imageJson4 \ "id").asOpt[String]
      imageIdOpt4 shouldBe defined
      val imageId4 = (imageJson4 \ "id").as[String]

      //already post this image
      val imageJson5 = Post("/images").withEntity(entity2).withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.OK)
        responseAs[JsObject]
      }

      val imageIdOpt5 = (imageJson5 \ "id").asOpt[String]
      imageIdOpt5 shouldBe defined
      val imageId5 = (imageJson5 \ "id").as[String]

      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user"))).futureValue shouldEqual 2
      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user1"))).futureValue shouldEqual 0
      ImagesDataStorageInMemory.count(ImagesFilter()).futureValue shouldEqual 4

      Delete(s"/images/$imageId1").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user"))).futureValue shouldEqual 1
      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user1"))).futureValue shouldEqual 0
      ImagesDataStorageInMemory.count(ImagesFilter()).futureValue shouldEqual 3

      Delete(s"/images/$imageId2").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user"))).futureValue shouldEqual 0
      ImagesDataStorageInMemory.count(ImagesFilter(userId = Some("user1"))).futureValue shouldEqual 0
      ImagesDataStorageInMemory.count(ImagesFilter()).futureValue shouldEqual 2

      Delete(s"/images/$imageId3").withHeaders(tokenHeader) ~> route ~> check {
        status should be(StatusCodes.Forbidden)
      }

      Delete(s"/images/$imageId3").withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      Delete(s"/images/$imageId4").withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.NoContent)
      }

      //duplicate of imageId3
      Delete(s"/images/$imageId5").withHeaders(tokenHeader2) ~> route ~> check {
        status should be(StatusCodes.NotFound)
      }

      imagesService.getImage(imageId1).failed.futureValue shouldBe ImagesError.NotFound
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
