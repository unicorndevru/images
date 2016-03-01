package blobs

import java.io.{ File, FileInputStream }
import java.nio.file.{ FileSystems, Files, Paths, StandardCopyOption }
import java.security.MessageDigest

import akka.http.scaladsl.server.directives.FileInfo
import com.ibm.icu.text.Normalizer2
import images.protocol.ImagesError

import scala.concurrent.Future

trait BlobsService {
  def storeFile(info: FileInfo, file: File): Future[BlobId]

  def storeFile(filename: String, file: File): Future[BlobId]

  def retrieveFile(id: BlobId): Future[File]

  def delete(id: BlobId): Future[Boolean]
}

class FilesService(val baseDir: String, val dispersion: Int = 16) extends BlobsService {
  private lazy val Separator = FileSystems.getDefault.getSeparator
  private val DefaultExtension = "blob"

  private def location(id: BlobId) =
    Seq(
      baseDir,
      id.hash.take(2).toString,
      id.hash,
      id.filename.take(64) + "." + id.extension
    ).mkString(Separator)

  def md5file(file: File): String = {
    val bis = new FileInputStream(file)
    val md5 = MessageDigest.getInstance("MD5")
    var buf = new Array[Byte](262144)
    Stream.continually(bis.read(buf)).takeWhile(_ != -1).foreach(md5.update(buf, 0, _))
    md5.digest().map(0xFF & _).map {
      "%02x".format(_)
    }.foldLeft("") {
      _ + _
    }
  }

  override def storeFile(fileinfo: FileInfo, file: File) = {

    val lastDot = fileinfo.fileName.lastIndexOf('.')
    val ext = if (lastDot != -1) {
      fileinfo.fileName.substring(lastDot + 1)
    } else fileinfo.contentType.mediaType.fileExtensions.headOption.getOrElse(DefaultExtension)

    val filename = Normalizer2.getNFKCInstance.normalize(fileinfo.fileName.stripSuffix("." + ext))
      .replaceAll("\\s+", "-").replaceAll("[^-_a-zA-Z0-9]", "").toLowerCase

    val id0 = BlobId(hash = md5file(file), filename = filename, extension = ext)

    store(id0, filename, file)
  }

  override def storeFile(filename: String, file: File) = {
    val lastDot = filename.lastIndexOf('.')
    val ext = if (lastDot != -1) {
      filename.substring(lastDot + 1)
    } else DefaultExtension

    val fn = Normalizer2.getNFKCInstance.normalize(filename.stripSuffix("." + ext))
      .replaceAll("\\s+", "-").replaceAll("[^-_a-zA-Z0-9]", "").toLowerCase

    val id0 = BlobId(hash = md5file(file), filename = fn, extension = ext)

    store(id0, filename, file)
  }

  private def store(id0: BlobId, filename: String, file: File): Future[BlobId] = {
    val p0 = Paths.get(location(id0))

    if (Files.exists(p0) && md5file(p0.toFile) == id0.hash) {
      Future.successful(id0)
    } else {
      def store(i: Int): Future[BlobId] = {
        val id = id0.copy(filename = s"${i}_$filename")

        val destination = Paths.get(location(id))

        if (Files.exists(destination)) {
          if (md5file(destination.toFile) == id0.hash) {
            Future.successful(id)
          } else {
            store(i + 1)
          }
        } else {
          Files.createDirectories(destination.getParent)
          Files.copy(file.toPath, destination, StandardCopyOption.REPLACE_EXISTING)
          Future.successful(id)
        }
      }

      store(0)
    }
  }

  override def retrieveFile(id: BlobId) = {
    val path = Paths.get(location(id))
    if (Files.exists(path)) {
      Future.successful(path.toFile)
    } else {
      Future.failed(ImagesError.FileNotFound)
    }
  }

  override def delete(id: BlobId) = {
    val dest = Paths.get(location(id))
    Future.successful(Files.deleteIfExists(dest))
  }
}