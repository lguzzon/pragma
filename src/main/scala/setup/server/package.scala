package setup.server

import running.storage._, setup.schemaGenerator.ApiSchemaGenerator
import domain._, DomainImplicits._
import domain.utils.UserError
import cats.Monad

import scala.util._, scala.io.StdIn
import domain._, domain.utils._
import org.parboiled2.Position
import running._
import org.parboiled2.ParseError
import cats.effect._, cats.implicits._
import doobie._, doobie.hikari._
import running.storage.postgres._
import cli._, cli.RunMode._
import org.http4s.HttpRoutes
import cats.effect._
import org.http4s._, org.http4s.dsl.io._
import org.http4s.server.blaze._, org.http4s.server.Router
import org.http4s.util._, org.http4s.implicits._, org.http4s.server.middleware._
import scala.concurrent.ExecutionContext.global
import spray.json._
import setup.server.implicits._

object SetupServer extends IOApp {

  def routes(db: DeamonDB) = CORS {
    GZip {
      HttpRoutes.of[IO] {
        // Setup phase
        case req @ POST -> Root / "project" / "create" => {
          val project = req.bodyText.map(_.parseJson.convertTo[ProjectInput])
          val res =
            project.map(project => db.createProject(project)).compile.drain

          res.map { _ =>
            Response[IO](
              Status.Ok,
              HttpVersion.`HTTP/1.1`,
              Headers(List(Header("Content-Type", "application/json")))
            )
          }
        }
        case req @ POST -> Root / "project" / "migrate"            => ???
        case req @ GET -> Root / "project" / "run" / projectId     => ???
        case req @ GET -> Root / "project" / "restart" / projectId => ???

        // Running phase
        case req @ POST -> Root / "project" / projectId / "graphql" => ???
        case req @ GET -> Root / "project" / projectId / "graphql"  => ???
      }
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val DEAMON_PG_USER = sys.env.get("DEAMON_PG_USER")
    val DEAMON_PG_URI = sys.env.get("DEAMON_PG_URI")
    val DEAMON_PG_PASSWORD = sys.env.get("DEAMON_PG_PASSWORD")

    val missingEnvVars =
      List(
        ("DEAMON_PG_URI", DEAMON_PG_URI, "Your PostgreSQL DB URL"),
        ("DEAMON_PG_USER", DEAMON_PG_USER, "Your PostgreSQL DB username"),
        (
          "DEAMON_PG_PASSWORD",
          DEAMON_PG_PASSWORD,
          "Your PostgreSQL DB password"
        )
      ).collect {
        case (name, None, desc) => name -> desc
      }.toList

    val transactor = (DEAMON_PG_USER, DEAMON_PG_URI, DEAMON_PG_PASSWORD) match {
      case (Some(pgUser), Some(pgUri), Some(pgPassword)) => {
        val t = for {
          exCtx <- ExecutionContexts.fixedThreadPool[IO](
            Runtime.getRuntime.availableProcessors * 10
          )
          blocker <- Blocker[IO]
          transactor <- HikariTransactor.newHikariTransactor[IO](
            "org.postgresql.Driver",
            pgUri,
            pgUser,
            pgPassword,
            exCtx,
            blocker
          )
        } yield transactor
        IO(t)
      }

      case _ =>
        IO {
          val isPlural = missingEnvVars.length > 1
          val `variable/s` = if (isPlural) "variables" else "variable"
          val `is/are` = if (isPlural) "are" else "is"
          val missingVarNames = missingEnvVars map (_._1)
          val renderedVarsWithDescription =
            missingEnvVars.map(v => s"${v._1}=<${v._2}>").mkString(" ")
          val renderedVarNames = missingVarNames.mkString(", ")
          val renderedCliArgs = args.mkString(" ")
          val errMsg =
            s"""
              |Environment ${`variable/s`} $renderedVarNames ${`is/are`} must be specified when in production mode.
              |Try: $renderedVarsWithDescription pragma $renderedCliArgs
              """.stripMargin

          println(errMsg)
          sys.exit(1)
        }
    }

    for {
      t <- transactor
      db <- t.use(tx => IO(new DeamonDB(tx)))
      runServer <- BlazeServerBuilder[IO](global)
        .bindHttp(3030, "localhost")
        .withHttpApp(Router("/" -> routes(db)).orNotFound)
        .serve
        .compile
        .drain
        .as(ExitCode.Success)
    } yield runServer
  }
}
