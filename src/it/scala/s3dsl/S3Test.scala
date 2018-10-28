package s3dsl

import java.util.concurrent.Executors

import Dsl.S3Dsl._
import s3dsl.domain.S3._
import s3dsl.Gens._
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import eu.timepit.refined.cats.syntax._
import org.specs2.mutable.Specification
import fs2.Stream
import org.specs2.ScalaCheck
import org.specs2.matcher.IOMatchers

import scala.concurrent.ExecutionContext
import scala.util.Random
import cats.syntax.all._
import cats.instances.all._

object S3Test extends Specification with ScalaCheck with IOMatchers {
  import cats.effect.IO

  val ecBlocking = ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  private val config = S3Config(
    creds = new BasicAWSCredentials("BQKN8G6V2DQ83DH3AHPN", "GPD7MUZqy6XGtTz7h2QPyJbggGkQfigwDnaJNrgF"),
    endpoint = new EndpointConfiguration("http://localhost:9000", "us-east-1"),
    blockingEc = ecBlocking
  )

  private val cs = IO.contextShift(ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3)))
  private val s3 = interpreter(config)(IO.ioConcurrentEffect(cs), cs)
  private implicit val par = IO.ioParallel(cs)

  "Bucket" in {

    "listBuckets" should {
      "succeed" in {
        withBucket(_ => s3.listBuckets) should returnValue { l: List[BucketName] => l should not(beEmpty)}
      }
    }

    "create, delete, doesBucketExist" should {

      "succeed" in {
        val prog = for {
          name <- bucketName
          _ <- s3.createBucket(name)
          exists1 <- s3.doesBucketExist(name)
          _ <- s3.deleteBucket(name)
          exists2 <- s3.doesBucketExist(name)
        } yield (exists1, exists2)

        prog should returnValue((true, false))
      }
    }

  }

  "Object" in {

    "put, doesObjectExist and delete" should {

      "succeed" in {
        prop { (key: Key, blob: String) =>

          val prog: TestProg[(Boolean, Boolean)] = bucketPath => for {
            path <- IO(Path(bucketPath.bucket, key))
            bytes = blob.getBytes
            _ <- Stream.emits(bytes).covary[IO].to(s3.putObject(path, bytes.length.longValue)).compile.drain
            exists1 <- s3.doesObjectExist(path)
            _ <- s3.deleteObject(path)
            exists2 <- s3.doesObjectExist(path)
          } yield (exists1, exists2)

          withBucket(prog) should returnValue((true, false))
        }
      }.set(minTestsOk = 3, maxSize = 5).setGen2(Gens.blobGen)

    }

    "listObjects" should {

      "succeed" in {
        val keys = List(Key("a.txt"), Key("b.txt"), Key("c.txt"))

        val prog: TestProg[List[ObjectSummary]] = bucketPath => for {
          _ <- keys.parTraverse(k =>
            Stream.emits(k.value.getBytes).covary[IO]
              .to(s3.putObject(bucketPath.copy(key = k), k.value.getBytes.length.longValue))
              .compile.drain
          )
          list <- s3.listObjects(bucketPath).compile.toList
          _ <- list.traverse(os => s3.deleteObject(os.path))
        } yield list

        withBucket(prog) should returnValue{(l: List[ObjectSummary]) =>
          l.map(_.path.key) should be_==(keys)
        }
      }

    }

    "getObject" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[Option[Object[IO]]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          obj <- s3.getObject(path, 1024)
          _ <- s3.deleteObject(path)
        } yield obj

        withBucket(prog) should returnValue { objO: Option[Object[IO]] =>
          objO should beSome{ obj: Object[IO] =>
            val bytes = obj.stream.compile.toList

            bytes should returnValue { l: List[Byte] =>
              l should haveSize(blobSize)
            }
            obj.meta.contentLength should be_>=(blobSize.longValue)
            obj.meta.contentType aka "ContentType" should beSome
          }
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[Object[IO]]] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            obj <- s3.getObject(path, 1024)
          } yield obj

          withBucket(prog) should returnValue(None)
        }.set(minTestsOk = 3, maxSize = 5)
      }

    }

    "getObjectMetadata" should {

      "succeed" in {
        val key = Key("a/b/c.txt")
        val blob = "testtesttest"
        val blobSize = blob.getBytes.length

        val prog: TestProg[Option[ObjectMetadata]] = bucketPath => for {
          path <- IO(bucketPath.copy(key = key))
          _ <- Stream.emits(blob.getBytes).covary[IO].to(s3.putObject(path, blobSize.longValue)).compile.drain
          meta <- s3.getObjectMetadata(path)
          _ <- s3.deleteObject(path)
        } yield meta

        withBucket(prog) should returnValue { metaO: Option[ObjectMetadata] =>
          metaO should beSome
        }
      }

      "return None if Object does not exist" in {
        prop { key: Key =>
          val prog: TestProg[Option[ObjectMetadata]] = bucketPath => for {
            path <- IO(bucketPath.copy(key = key))
            meta <- s3.getObjectMetadata(path)
          } yield meta

          withBucket(prog) should returnValue(None)
        }.set(minTestsOk = 3, maxSize = 5)
      }
    }

  }

  private type TestProg[X] = Path => IO[X]

  private def withBucket[X](f: TestProg[X]): IO[X] = for {
    bucketPath <- bucketName.map(Path(_, Key.empty))
    x <- s3.createBucket(bucketPath.bucket).bracket(_ => f(bucketPath))(_ => s3.deleteBucket(bucketPath.bucket))
  } yield x

  private def bucketName = IO(
    BucketName.validate(s"test-${System.currentTimeMillis}-${Random.nextInt(9999999).toString}")
      .fold(_ => sys.error("err"), identity)
  )

}
