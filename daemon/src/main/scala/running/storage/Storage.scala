package running.storage

import running.operations._
import spray.json._
import pragma.domain._
import cats.Monad
import running.utils._

class Storage[S, M[_]: Monad](
    val queryEngine: QueryEngine[S, M],
    val migrationEngine: MigrationEngine[S, M]
) {

  def run(
      operations: Operations.OperationsMap
  ): M[queryEngine.TransactionResultMap] =
    queryEngine.run(operations)

  def migrate(mode: Mode, codeToPersist: String): M[Unit] =
    migrationEngine.migrate(mode, codeToPersist)

}

trait MigrationEngine[S, M[_]] {
  def migrate(mode: Mode, codeToPersist: String): M[Unit]
}

case class MigrationError(step: MigrationStep) extends Exception

abstract class QueryEngine[S, M[_]: Monad] {
  type Query[_]

  /** Succeeds only if all operations do */
  final type TransactionResultMap =
    Vector[
      (
          Option[Operations.OperationGroupName],
          Vector[
            (Operations.ModelSelectionName, Vector[(Operation, JsValue)])
          ]
      )
    ]

  def run(
      operations: Operations.OperationsMap
  ): M[TransactionResultMap]

  def query(op: Operation): Query[JsValue]

  def runQuery[A](query: Query[A]): M[A]

  def createManyRecords(
      model: PModel,
      records: Vector[JsObject],
      innerReadOps: Vector[InnerOperation]
  ): Query[Vector[JsObject]]

  def createOneRecord(
      model: PModel,
      record: JsObject,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsObject]

  def updateManyRecords(
      model: PModel,
      recordsWithIds: Vector[ObjectWithId],
      innerReadOps: Vector[InnerOperation]
  ): Query[JsArray]

  def updateOneRecord(
      model: PModel,
      primaryKeyValue: JsValue,
      newRecord: JsObject,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsObject]

  def deleteManyRecords(
      model: PModel,
      primaryKeyValues: Vector[JsValue],
      innerReadOps: Vector[InnerOperation]
  ): Query[JsArray]

  def deleteOneRecord(
      model: PModel,
      primaryKeyValue: JsValue,
      innerReadOps: Vector[InnerOperation],
      cascade: Boolean
  ): Query[JsObject]

  /** Returns the pushed values */
  def pushManyTo(
      model: PModel,
      field: PModelField,
      items: Vector[JsValue],
      primaryKeyValue: JsValue,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsArray]

  def pushOneTo(
      model: PModel,
      field: PModelField,
      item: JsValue,
      sourceId: JsValue,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsValue]

  /** Returns the removed values */
  def removeManyFrom(
      model: PModel,
      arrayField: PModelField,
      sourcePkValue: JsValue,
      targetPkValues: Vector[JsValue],
      innerReadOps: Vector[InnerOperation]
  ): Query[JsArray]

  /** Returns the removed value */
  def removeOneFrom(
      model: PModel,
      arrayField: PModelField,
      sourcePkValue: JsValue,
      targetPkValue: JsValue,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsValue]

  def readManyRecords(
      model: PModel,
      agg: ModelAgg,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsArray]

  def readOneRecord(
      model: PModel,
      primaryKeyValue: JsValue,
      innerReadOps: Vector[InnerOperation]
  ): Query[JsObject]

  def login(
      model: PModel,
      publicCredentialField: PModelField,
      publicCredentialValue: JsValue,
      secretCredentialValue: Option[String]
  ): Query[JsString]

}
