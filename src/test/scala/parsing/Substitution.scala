package parsing

import org.scalatest.FlatSpec
import parsing.Substitutor
import domain.{HImport, GraalFunction, SyntaxTree}
import spray.json._
import scala.util.Success

class Substitution extends FlatSpec {

  "Substitutor" should "return an object containing all defined functions in a file as GraalFunctionValues using readGraalFunctions" in {
    val himport =
      HImport("functions", "./src/test/scala/parsing/test-functions.js", None)
    val functionObject = Substitutor.readGraalFunctions(himport).get
    val f = functionObject.value("f").asInstanceOf[GraalFunction]
    val additionResult = f.execute(JsNumber(2))
    assert(
      additionResult.get.asInstanceOf[JsNumber].value == BigDecimal(
        Success(3.0).value
      )
    )
  }

  "Substitutor" should "substitute function references in directives with actual functions" in {
    val code = """
    import "./src/test/scala/parsing/test-functions.js" as fns

    @validate(function: fns.validateCat)
    model Cat { name: String }
    """
    val syntaxTree = SyntaxTree.from(code).get
    val substituted = Substitutor.substitute(syntaxTree).get
    val directive = substituted.models.head.directives.head
    directive.args.value("function") match {
      case GraalFunction(id, _, filePath, graalCtx, languageId) => {
        assert(id == "validateCat")
        assert(filePath == "./src/test/scala/parsing/test-functions.js")
      }
      case _ => fail("Result should be a Graal function 'validateCat'")
    }
  }

  "Substitutor" should "be able to retrieve referenced data from context" in {
    val code = """
    import "./src/test/scala/parsing/test-functions.js" as fns
    """
    val syntaxTree = SyntaxTree.from(code).get
    val ctx = Substitutor.getContext(syntaxTree.imports).get
    val ref = HeavenlyParser
      .Reference(
        "fns",
        Some(HeavenlyParser.Reference("validateCat", None, None)),
        None
      )
    val retrieved = Substitutor.getReferencedFunction(ref, ctx.value)
    retrieved match {
      case Some(f: GraalFunction) => {
        assert(f.filePath == "./src/test/scala/parsing/test-functions.js")
        assert(f.id == "validateCat")
      }
      case None => fail("Should've found referenced value")
      case _    => ()
    }
  }

  "Predicate references in permissions" should "be substituted with actual predicates correctly" in {
    val code = """
    @user model User {
      username: String @publicCredential
      password: String @secretCredential
      todos: [Todo]
    }

    model Todo { title: String }

    import "./src/test/scala/parsing/test-functions.js" as fns

    role User {
      allow ALL Todo fns.isOwner
    }
    """
    val syntaxTree = SyntaxTree.from(code).get
    val substituted = Substitutor.substitute(syntaxTree).get
    val newGlobalTenant = substituted.permissions.get.globalTenant
    val todoOwnershipPredicate =
      newGlobalTenant.roles.head.rules.head.predicate
        .asInstanceOf[GraalFunction]
    assert(todoOwnershipPredicate.id == "isOwner")
  }

}
