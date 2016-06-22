package blobs

import java.io.FileInputStream
import java.nio.file._
import java.security.MessageDigest

import akka.http.scaladsl.server.directives.FileInfo
import com.ibm.icu.text.{ Normalizer2, Transliterator }
import images.protocol.ImagesError

import scala.concurrent.Future
import scala.util.Try

trait BlobsService {
  def storeFile(info: FileInfo, file: Path, dir: String): Future[BlobId]

  def storeFile(filename: String, file: Path, dir: String): Future[BlobId]

  def storeFile(id0: BlobId, filename: String, file: Path): Future[BlobId]

  def moveFile(src: BlobId, dest: BlobId): Future[BlobId]

  def retrieveFile(id: BlobId): Future[Path]

  def delete(id: BlobId): Future[Boolean]
}

class FilesService(val baseDir: String, val dispersion: Int = 16) extends BlobsService {
  private lazy val Separator = FileSystems.getDefault.getSeparator
  private val DefaultExtension = "blob"

  private def location(id: BlobId) =
    (id.dir.fold(Seq(baseDir))(dir ⇒ Seq(baseDir, dir)) ++
      Seq(
        id.hash.take(2).toString,
        id.hash,
        id.filename.take(64) + "." + id.extension
      )).mkString(Separator)

  def md5file(file: Path): String = {
    val bis = new FileInputStream(file.toFile)
    val md5 = MessageDigest.getInstance("MD5")
    var buf = new Array[Byte](262144)

    Stream.continually(bis.read(buf)).takeWhile(_ != -1).foreach(md5.update(buf, 0, _))
    md5.digest().map(0xFF & _).map {
      "%02x".format(_)
    }.foldLeft("") {
      _ + _
    }
  }

  private val normalize = (n: String) ⇒ Normalizer2.getNFDInstance.normalize(n).replaceAll("\\s+", "-").replaceAll("[^-_a-zA-Z0-9]", "").toLowerCase
  private val translit = (n: String) ⇒ Transliterator.getInstance("Any-Latin; NFD").transform(n)

  override def storeFile(fileinfo: FileInfo, file: Path, dir: String) = {

    val lastDot = fileinfo.fileName.lastIndexOf('.')
    val ext = if (lastDot != -1) {
      fileinfo.fileName.substring(lastDot + 1)
    } else fileinfo.contentType.mediaType.fileExtensions.headOption.getOrElse(DefaultExtension)

    val filename = (translit andThen normalize) (fileinfo.fileName.stripSuffix("." + ext)).toLowerCase

    val id0 = BlobId(hash = md5file(file), filename = filename, extension = ext, dir = Option(dir).filter(_.nonEmpty))

    storeFile(id0, filename, file)
  }

  override def storeFile(filename: String, file: Path, dir: String) = {
    val lastDot = filename.lastIndexOf('.')
    val ext = if (lastDot != -1) {
      filename.substring(lastDot + 1)
    } else DefaultExtension

    val fn = (translit andThen normalize) (filename.stripSuffix("." + ext)).toLowerCase

    val id0 = BlobId(hash = md5file(file), filename = fn, extension = ext, dir = Option(dir).filter(_.nonEmpty))

    storeFile(id0, filename, file)
  }

  def onFileStored(id: BlobId, path: Path): Unit = ()

  override def storeFile(id0: BlobId, filename: String, file: Path): Future[BlobId] = {
    val p0 = Paths.get(location(id0))

    if (Files.exists(p0) && md5file(p0) == id0.hash) {
      Future.successful(id0)
    } else {
      def store(i: Int): Future[BlobId] = {
        val id = id0.copy(filename = s"${i}_$filename")

        val destination = Paths.get(location(id))

        if (Files.exists(destination)) {
          if (md5file(destination) == id0.hash) {
            Future.successful(id)
          } else {
            store(i + 1)
          }
        } else {
          Files.createDirectories(destination.getParent)
          Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
          onFileStored(id, destination)
          Future.successful(id)
        }
      }

      store(0)
    }
  }

  override def moveFile(src: BlobId, dest: BlobId) = {
    val srcPath = Paths.get(location(src))
    if (Files.exists(srcPath)) {
      Future fromTry Try {
        val destPath = Paths.get(location(dest))
        if (Files.exists(destPath) && md5file(destPath) == md5file(srcPath)) {
          Files.delete(srcPath)
        } else {
          Files.move(srcPath, destPath, StandardCopyOption.REPLACE_EXISTING)
        }
        dest
      }
    } else {
      Future.failed(ImagesError.FileNotFound)
    }
  }

  override def retrieveFile(id: BlobId) = {
    val path = Paths.get(location(id))
    if (Files.exists(path)) {
      Future.successful(path)
    } else {
      Future.failed(ImagesError.FileNotFound)
    }
  }

  override def delete(id: BlobId) = {
    val dest = Paths.get(location(id))
    Future.successful(Files.deleteIfExists(dest))
  }
}