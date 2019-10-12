package setup

import domain._
import primitives._
import utils.{TypeMismatchException}
import Implicits._

import sangria.schema._
import sangria.parser.QueryParser
import sangria.ast.{Document}

import scala.util.{Success, Failure, Try}

import sys.process._
import scala.language.postfixOps
import java.io._
import sangria.ast.{ObjectTypeDefinition, NamedType, FieldDefinition}

case class Setup(
    syntaxTree: SyntaxTree,
) {

  val storage: Storage = ???

  def run() = {
    writeDockerComposeYaml()
    dockerComposeUp()
    storage.migrate()
  }

  def dockerComposeUp() =
    "docker-compose -f ./.heavenly-x/docker-compose.yml up -d" ! match {
      case 1 =>
        Failure(
          new Exception(
            "Error: Couldn't run docker-compose. Make sure docker and docker-compose are installed on your machine"
          )
        )
      case 0 => Success(())
    }

  def dockerComposeDown() =
    "docker-compose -f ./.heavenly-x/docker-compose.yml down" ! match {
      case 1 =>
        Failure(
          new Exception(
            "Error: Couldn't run docker-compose. Make sure docker and docker-compose are installed on your machine"
          )
        )
      case 0 => Success(())
    }

  def writeDockerComposeYaml() = {
    "mkdir .heavenly-x" ! match {
      case 1 =>
        throw new Exception(
          "Filesystem Error: Couldn't create .heavenlyx directory"
        )
      case 0 => {
        val pw = new PrintWriter(new File(".heavenly-x/docker-compose.yml"))
        pw.write(dockerComposeYaml())
        pw.close
      }
    }
  }

  // TODO: make this dynamic based on `storage.dockerContainerConfig`
  def dockerComposeYaml(): String = Setup.defaultDockerComposeYaml

  def buildApiSchema(): Document = ???

  def buildExecutor[Request](): QueryExecutor[Request] = ???
}

object Setup {
  def defaultDockerComposeYaml() =
    """
version: '3'
services:
  prisma:
    image: prismagraphql/prisma:1.34
    restart: always
    ports:
      - '4466:4466'
    environment:
      PRISMA_CONFIG: |
        port: 4466
        managementApiSecret:
        databases:
          default:
            connector: mongo
            uri: mongodb://prisma:prisma@mongo-db
  mongo-db:
    image: mongo:3.6
    restart: always
    environment:
      MONGO_INITDB_ROOT_USERNAME: prisma
      MONGO_INITDB_ROOT_PASSWORD: prisma
    ports:
      - '27017:27017'
    volumes:
      - mongo:/var/lib/mongo
volumes:
  mongo: ~
    """;
}
