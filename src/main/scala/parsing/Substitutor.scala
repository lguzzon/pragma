package parsing

import domain._, primitives._, utils._, HeavenlyParser.Reference
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._
import scala.collection.immutable.ListMap
import org.graalvm.polyglot._

object Substitutor {

  def substitute(st: SyntaxTree): Try[SyntaxTree] = {
    val graalCtx = Context.create()
    val ctx = getContext(st.imports, graalCtx) match {
      case Success(ctx) => ctx
      case Failure(err) => return Failure(err)
    }
    val substitutedModels =
      st.models.map(substituteDirectiveFunctionRefs(_, ctx))
    val modelErrors = substitutedModels.collect {
      case Failure(err: UserError) => err.errors
    }.flatten
    val substitutedPermissions = st.permissions.map { permissions =>
      for {
        withResources <- substituteAccessRuleResourceRefs(permissions, st)
        withPredicates <- substitutePredicateRefs(withResources, ctx.value)
      } yield withPredicates
    }
    val permissionsErrors = substitutedPermissions match {
      case Some(Success(_))              => Nil
      case None                          => Nil
      case Some(Failure(err: UserError)) => err.errors
      case Some(Failure(err))            => (err.getMessage, None) :: Nil
    }
    val allErrors = modelErrors ::: permissionsErrors
    if (allErrors.isEmpty)
      Success(
        addDefaultPrimaryFields(
          st.copy(
            models = substitutedModels.map(_.get),
            permissions = substitutedPermissions.map(_.get)
          )
        )
      )
    else
      Failure(new UserError(allErrors))
  }

  // Gets all  imported functions as a single object
  def getContext(
      imports: List[HImport],
      graalCtx: Context
  ): Try[HInterfaceValue] = {
    val importedObjects = imports zip imports.map(
      readGraalFunctionsIntoContext(_, graalCtx)
    )
    val importErrors = importedObjects.collect {
      case (imp, Failure(exception)) => (exception.getMessage, imp.position)
    }
    if (!importErrors.isEmpty) Failure(new UserError(importErrors))
    else
      Success {
        HInterfaceValue(
          ListMap.from(importedObjects.map(imp => (imp._1.id, imp._2.get))),
          HInterface(
            "context",
            importedObjects.map(
              imp => HInterfaceField(imp._1.id, HAny, None)
            ),
            None
          )
        )
      }
  }

  /** Reads code into the passed `graalCtx` and returns the
    * definitions in the import
    */
  def readGraalFunctionsIntoContext(
      himport: HImport,
      graalCtx: Context
  ): Try[HInterfaceValue] = Try {
    val file = new java.io.File(himport.filePath)
    val languageId = Source.findLanguage(file)
    val source = Source.newBuilder(languageId, file).build()
    graalCtx.eval(source)
    val throwawayCtx = Context.create(languageId)
    throwawayCtx.eval(source)
    val defKeys = throwawayCtx.getBindings(languageId).getMemberKeys()
    val hobj = ListMap.from(
      defKeys.asScala.map { defId =>
        defId -> GraalFunction(
          id = defId,
          htype = HFunction(ListMap.empty, HAny),
          filePath = himport.filePath,
          graalCtx,
          languageId
        )
      }
    )
    val ctxHtype = HInterface(
      himport.id,
      hobj.keys
        .map(k => HInterfaceField(k, HFunction(ListMap.empty, HAny), None))
        .toList,
      None
    )
    HInterfaceValue(hobj, ctxHtype)
  }

  def getReferencedFunction(
      ref: Reference,
      ctx: HObject
  ): Option[GraalFunction] =
    ctx.get(ref.path.head) match {
      case Some(f: GraalFunction) if ref.path.length == 1 => Some(f)
      case Some(HInterfaceValue(hobj, _)) if ref.path.length > 1 =>
        getReferencedFunction(ref.copy(path = ref.path.tail), hobj)
      case _ => None
    }

  def substituteDirectiveFunctionRefs(
      model: HModel,
      ctx: HInterfaceValue
  ): Try[HModel] = {
    val newModelLevel = model.directives.map(substituteDirectiveRefArgs(ctx))
    val newFieldLevel = model.fields.map { field =>
      field.directives.map(substituteDirectiveRefArgs(ctx))
    }
    val errors = newModelLevel ::: newFieldLevel.flatten collect {
      case Failure(e: UserError) => e
    }
    if (errors.length > 0) Failure(new UserError(errors.flatMap(_.errors)))
    else
      Success {
        val newFields = model.fields.zip(newFieldLevel).map {
          case (f, dirs) =>
            HModelField(
              f.id,
              f.htype,
              f.defaultValue,
              dirs.map(_.get),
              f.position
            )
        }
        HModel(
          model.id,
          newFields,
          newModelLevel.map(_.get),
          model.position
        )
      }
  }

  def substituteDirectiveRefArgs(
      ctx: HInterfaceValue
  )(
      dir: Directive
  ): Try[Directive] = {
    val newArgs = dir.args.value.map {
      case (argId, ref: Reference) =>
        (argId, getReferencedFunction(ref, ctx.value))
      case (argId, v) => (argId, Some(v))
    }
    val argErrors = newArgs.collect {
      case (name, None) =>
        (
          s"Argument `${dir.args.value(name).toString}` is not defined",
          dir.position
        )
    }
    if (!argErrors.isEmpty) Failure(new UserError(argErrors.toList))
    else
      Success(
        dir.copy(
          args =
            HInterfaceValue(newArgs.map(p => (p._1, p._2.get)), dir.args.htype)
        )
      )
  }

  def substituteAccessRulePredicate(
      rule: AccessRule,
      ctx: HObject
  ): Try[AccessRule] = {
    val newPredicate = rule.predicate match {
      case None => (None, rule.position)
      case Some(ref: Reference) =>
        (getReferencedFunction(ref, ctx), ref.position)
      case Some(f: HFunctionValue[_, _]) =>
        (Some(f), rule.position)
    }
    if (newPredicate._1.isDefined)
      Success {
        rule.copy(predicate = newPredicate._1)
      } else
      Failure {
        new UserError(
          (
            s"Predicate `${rule.predicate.get}` is not defined",
            newPredicate._2
          ) :: Nil
        )
      }
  }

  def substituteTenant(t: Tenant, ctx: HObject): Try[Tenant] = {
    val newGlobalRules = t.rules.map(substituteAccessRulePredicate(_, ctx))
    val newRoles = t.roles.map { role =>
      (role, role.rules.map(substituteAccessRulePredicate(_, ctx)))
    }
    val errors = (newGlobalRules ::: newRoles.map(_._2).flatten).collect {
      case Failure(err: UserError) => err.errors
    }.flatten
    if (errors.isEmpty)
      Success(
        t.copy(
          rules = newGlobalRules.map(_.get),
          roles = newRoles.map(pair => pair._1.copy(rules = pair._2.map(_.get)))
        )
      )
    else Failure(new UserError(errors))
  }

  def substitutePredicateRefs(
      acl: Permissions,
      ctx: HObject
  ): Try[Permissions] = {
    val newGlobalTenant = substituteTenant(acl.globalTenant, ctx)
    val newTenants = acl.tenants.map(substituteTenant(_, ctx))
    val errors = (newGlobalTenant :: newTenants).collect {
      case Failure(err: UserError) => err.errors
    }.flatten
    if (errors.isEmpty)
      Success(
        acl.copy(
          globalTenant = newGlobalTenant.get,
          tenants = newTenants.map(_.get)
        )
      )
    else Failure(new UserError(errors))
  }

  def substituteAccessRuleResource(
      rule: AccessRule,
      st: SyntaxTree
  ): Try[AccessRule] = {
    val parentRef = rule.resourcePath._1.asInstanceOf[Reference]
    val childRef = rule.resourcePath._2.asInstanceOf[Option[Reference]]
    val parent = st.models.find(_.id == parentRef.path.head)
    val child = parent match {
      case Some(model) if childRef.isDefined =>
        model.fields.find(_.id == childRef.get.path.head)
      case _ => None
    }
    val newPath = (parent, child) match {
      case (Some(model), field) => Success((model, field))
      case (None, _) =>
        Failure(
          UserError(
            (
              s"Referenced model `${parentRef.path.head}` is not defined",
              parentRef.position
            ) :: Nil
          )
        )
    }
    newPath.map(path => rule.copy(resourcePath = path))
  }

  def substituteAccessRulesResources(
      rules: List[AccessRule],
      st: SyntaxTree
  ): Try[List[AccessRule]] = {
    val newRules = rules.map { rule =>
      substituteAccessRuleResource(rule, st)
    }
    val errors = newRules.collect {
      case Failure(err: UserError) => err.errors
    }.flatten
    if (errors.isEmpty) Success(newRules.map(_.get))
    else Failure(UserError(errors))
  }

  def substituteAccessRuleResourceRefs(
      permissions: Permissions,
      st: SyntaxTree
  ): Try[Permissions] = {
    val newGlobalRules = substituteAccessRulesResources(
      permissions.globalTenant.rules,
      st
    )
    val newRoles = permissions.globalTenant.roles zip permissions.globalTenant.roles
      .map { role =>
        substituteAccessRulesResources(role.rules, st)
      }
    val globalRuleErrors = newGlobalRules match {
      case Failure(err: UserError) => err.errors
      case Success(_)              => Nil
      case _                       => Nil
    }
    val errors = globalRuleErrors ::: newRoles
      .map(_._2)
      .collect {
        case Failure(err: UserError) => err.errors
      }
      .flatten

    if (errors.isEmpty)
      Success(
        permissions.copy(
          globalTenant = permissions.globalTenant.copy(
            rules = newGlobalRules.get,
            roles = newRoles.map(pair => pair._1.copy(rules = pair._2.get))
          )
        )
      )
    else Failure(UserError(errors))
  }

  // Adds an _id: String @primary field to the model
  def withDefaultId(model: HModel) = model.copy(
    fields = HModelField(
      "_id",
      HString,
      None,
      Directive(
        "primary",
        HInterfaceValue(ListMap.empty, HInterface("primary", Nil, None)),
        FieldDirective,
        None
      ) :: Nil,
      None
    ) :: model.fields
  )

  def addDefaultPrimaryFields(st: SyntaxTree): SyntaxTree =
    st.copy(models = st.models.map { model =>
      val foundPrimaryField = Validator.findPrimaryField(model)
      if (foundPrimaryField.isDefined) model
      else withDefaultId(model)
    })

}
