package running.pipeline.functions

import running.pipeline._
import domain._, Implicits.StringMethods
import domain.utils.InternalException
import domain.utils.AuthorizationError
import spray.json._
import setup.storage.Storage
import running.pipeline.Operations.ReadOperation
import sangria.ast._
import running.JwtPaylod
import domain.utils.UserError
import scala.util._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class Authorizer(
    syntaxTree: SyntaxTree,
    storage: Storage,
    devModeOn: Boolean = true
) {
  import Authorizer._

  def apply(request: Request): Future[(List[AuthorizationError], Request)] =
    (syntaxTree.permissions, request.user) match {
      case (None, _) =>
        Future.failed(
          UserError.fromAuthErrors(AuthorizationError("Access Denied") :: Nil)
        )
      case (Some(permissions), None) => {
        val reqOps = Operations.operationsFrom(request)(syntaxTree)
        opsPass(reqOps.values.flatten.toVector, JsNull) match {
          case Right(true) => Future((Nil, request))
          case Right(false) =>
            Future.failed(
              UserError
                .fromAuthErrors(AuthorizationError("Access Denied") :: Nil)
            )
          case Left(errors) =>
            Future.failed(UserError.fromAuthErrors(errors.toList))
        }
      }
      case (Some(permissions), Some(jwt)) => {
        val userModel = syntaxTree.models.find(_.id == jwt.role)

        if (!userModel.isDefined)
          return Future.failed(
            InternalException(
              s"Request has role `${jwt.role}` that doesn't exist"
            )
          )
        val user = storage.run(
          userQuery(userModel.get, jwt.userId),
          Map(None -> Vector(userReadOperation(userModel.get, jwt)))
        )

        user.map {
          case Left(userJson) => {
            val reqOps = Operations.operationsFrom(request)(syntaxTree)
            opsPass(reqOps.values.flatten.toVector, userJson) match {
              case Right(true) => (Nil, request)
              case Right(false) =>
                throw AuthorizationError("Unauthorized request")
              case Left(errors) => throw UserError.fromAuthErrors(errors.toList)
            }
          }
          case Right(_) =>
            throw InternalException(
              "User object retrieved from storage must be an object"
            )
        }
      }
    }

  def opsPass(
      ops: Vector[Operation],
      user: JsValue
  ): Either[Vector[AuthorizationError], Boolean] = {
    val results = for {
      op <- ops
      opRules = releventRules(op)
      (allows, denies) = opRules.partition(_.ruleKind == Allow)
      denyExists = denies.exists(ruleMatchesOp(_, op, user))
      allowExists = allows.exists(ruleMatchesOp(_, op, user))
    } yield (denyExists, allowExists, denies, allows)

    val allOpsAllowed = results.forall {
      case (denyExists, allowExists, _, _) => !denyExists && allowExists
    }

    if (!devModeOn) Right(allOpsAllowed)
    else {
      val errors = results.collect {
        case (false, true, _, _) => Nil
        case (true, _, denies, _) =>
          denies
            .filter(deny => ops.exists(ruleMatchesOp(deny, _, user)))
            .map { deny =>
              AuthorizationError(
                s"Request denied because of rule `$deny`",
                suggestion = Some(
                  "If you think that this request should be authorized, try removing the `deny` rule"
                ),
                position = deny.position
              )
            }
            .toVector
        case (false, false, _, allows) =>
          ops
            .filterNot(op => allows.exists(ruleMatchesOp(_, op, user)))
            .map { op =>
              AuthorizationError(
                "Access Denied",
                cause = Some(
                  s"No `allow` rule exists to authorize `${op.event}` on `${op.targetModel.id}`"
                ),
                suggestion = Some(
                  s"Try adding `allow ${op.event} ${op.targetModel.id}` to your access rules for this request to be authorized"
                )
              )
            }
      }.flatten

      if (errors.isEmpty) Right(true)
      else Left(errors)
    }
  }

  /** Returns all the rules that can match */
  def releventRules(op: Operation): List[AccessRule] = {
    val globalRules = syntaxTree.permissions match {
      case None              => Nil
      case Some(permissions) => permissions.globalTenant.rules
    }
    val roleSpecificRules = op.role match {
      case None => Nil
      case Some(role) =>
        syntaxTree.permissions
          .flatMap {
            _.globalTenant.roles
              .find(_.user.id == op.targetModel.id)
              .map(_.rules)
          }
          .getOrElse(Nil)
    }

    globalRules.filter(ruleCanMatch(_, op)) :::
      roleSpecificRules.filter(ruleCanMatch(_, op))
  }

}
object Authorizer {

  def ruleCanMatch(rule: AccessRule, op: Operation): Boolean =
    (op.targetModel.id == rule.resourcePath._1.id) &&
      (op.event match {
        case _: ReadEvent => rule.actions.contains(Read)
        case _: CreateEvent =>
          rule.actions.exists(p => p == Create || p == SetOnCreate)
        case _: UpdateEvent =>
          rule.actions.exists {
            case Update | PushTo | RemoveFrom | Mutate => true
            case _                                           => false
          }
        case _: DeleteEvent => rule.actions.contains(Delete)
        case event          => rule.actions.contains(event)
      }) &&
      (rule.resourcePath._2 match {
        case None => true
        case Some(ruleField) =>
          op.innerReadOps.exists(_.targetField.field.id == ruleField.id)
      })

  /**
    * Returns the boolean result of the user
    * predicate or the error thrown by the user
    */
  def userPredicateResult(
      rule: AccessRule,
      argument: JsValue
  ): Either[AuthorizationError, Boolean] =
    rule.predicate match {
      case None => Right(true)
      case Some(predicate) => {
        val predicateResult = predicate
          .execute(argument)
          .map {
            case JsTrue => true
            case _      => false
          }
        predicateResult match {
          case Success(value) => Right(value)
          case Failure(err)   => Left(AuthorizationError(err.getMessage()))
        }
      }
    }

  def ruleMatchesOp(
      rule: AccessRule,
      op: Operation,
      predicateArg: JsValue
  ): Boolean =
    ((rule.resourcePath._2, op.event) match {
      case (Some(ruleField), Read | ReadMany) =>
        ruleMatchesOneInnerReadOp(rule, op.innerReadOps, predicateArg)
      case (Some(ruleField), Update) =>
        op.opArguments
          .find(_.name == op.targetModel.id.small)
          .map(_.value)
          .flatMap {
            case ObjectValue(fields, _, _) =>
              Some {
                val opsToCheck = op.innerReadOps.filterNot { innerOp =>
                  fields.exists(_.name == innerOp.targetField.field.id)
                }
                ruleMatchesOneInnerReadOp(rule, opsToCheck, predicateArg)
              }
            case _ => None
          }
          .getOrElse(
            throw InternalException(
              "Argument passed to `update` is not an object. Something must've went wrond duing query validation or schema generation"
            )
          )
      case (Some(ruleField), UpdateMany) =>
        op.opArguments
          .find(_.name == "items")
          .map(_.value match {
            case ListValue(values, _, _) =>
              values.exists { value =>
                value.isInstanceOf[ObjectValue] &&
                value
                  .asInstanceOf[ObjectValue]
                  .fields
                  .exists(_.name == ruleField.id)
              }
            case _ => true
          })
          .getOrElse(true)
      case (Some(ruleField), Create) if rule.actions.contains(SetOnCreate) =>
        op.opArguments
          .find(_.name == op.targetModel.id.small)
          .map(_.value match {
            case ObjectValue(fields, _, _) =>
              fields.exists(_.name == ruleField.id)
            case _ => true
          })
          .getOrElse(true)
      case (None, Delete | DeleteMany) => rule.actions.contains(Delete)
      case (None, Login)               => rule.actions.contains(Login)
      case _                           => false
    }) && (userPredicateResult(rule, predicateArg) match {
      case Right(value) => value
      case _            => false
    })

  /** True if the rule matches one or more inner operations */
  def ruleMatchesOneInnerReadOp(
      rule: AccessRule,
      innerOps: Vector[InnerOperation],
      predicateArg: JsValue
  ): Boolean =
    rule.actions.contains(Read) &&
      (rule.resourcePath._2 match {
        case None =>
          innerOps.exists { innerOp =>
            innerOp.operation.targetModel.id == rule.resourcePath._1.id ||
            ruleMatchesOneInnerReadOp(
              rule,
              innerOp.operation.innerReadOps,
              predicateArg
            )
          }
        case Some(ruleField) =>
          innerOps.exists { innerOp =>
            innerOp.operation.targetModel.id == rule.resourcePath._1.id &&
            innerOp.targetField.field.id == ruleField.id ||
            ruleMatchesOneInnerReadOp(
              rule,
              innerOp.operation.innerReadOps,
              predicateArg
            )
          }
      }) &&
      (userPredicateResult(rule, predicateArg) match {
        case Right(value) => value
        case _            => false
      })

  def userReadOperation(role: PModel, jwt: JwtPaylod) =
    Operation(
      opKind = ReadOperation,
      gqlOpKind = sangria.ast.OperationType.Query,
      opArguments =
        Vector(Argument(role.primaryField.id, StringValue(jwt.userId))),
      directives = Vector.empty,
      event = Read,
      targetModel = role,
      role = Some(role),
      user = Some(jwt),
      crudHooks = Nil,
      alias = None,
      innerReadOps = Vector.empty
    )

  /** Construct the query to get the user to be authorized from storage */
  def userQuery(role: PModel, userId: String) =
    Document(
      Vector(
        OperationDefinition(
          OperationType.Query,
          None,
          Vector.empty,
          Vector.empty,
          Vector(
            Field(
              None,
              role.id,
              Vector.empty,
              Vector.empty,
              Vector(
                Field(
                  None,
                  "read",
                  Vector(
                    Argument(
                      role.primaryField.id,
                      StringValue(userId)
                    )
                  ),
                  Vector.empty,
                  role.fields.map { pfield =>
                    Field(
                      None,
                      pfield.id,
                      Vector.empty,
                      Vector.empty,
                      Vector.empty
                    )
                  }.toVector,
                  Vector.empty,
                  Vector.empty
                )
              )
            )
          )
        )
      )
    )
}