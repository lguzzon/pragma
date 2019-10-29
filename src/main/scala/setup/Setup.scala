package setup

import domain._
import primitives._
import running.QueryExecutor

import sangria.ast.{Document}

import Implicits._
import java.io._
import scala.util.{Success, Failure}

case class Setup(
    syntaxTree: SyntaxTree
) {

  val storage: Storage = PrismaMongo(syntaxTree)

  def setup() = {
    writeDockerComposeYaml()
    dockerComposeUp()
    storage.migrate()
  }

  def dockerComposeUp() =
    "docker-compose -f ./.heavenly-x/docker-compose.yml up -d" $
      "Error: Couldn't run docker-compose. Make sure docker and docker-compose are installed on your machine"

  def dockerComposeDown() =
    "docker-compose -f ./.heavenly-x/docker-compose.yml down" $
      "Error: Couldn't run docker-compose. Make sure docker and docker-compose are installed on your machine"

  def writeDockerComposeYaml() =
    "mkdir .heavenly-x" $ "Filesystem Error: Couldn't create .heavenlyx directory"

  def build(): (Document, QueryExecutor) = (buildApiSchema, buildExecutor)

  def buildApiSchema(): Document = ???

  def buildExecutor(): QueryExecutor = QueryExecutor(syntaxTree, storage)
}
