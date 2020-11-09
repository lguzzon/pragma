package running

import sangria.ast._
import sangria.macros._
import org.scalatest.funsuite.AnyFunSuite
import spray.json.JsObject

class RequestReducerSpec extends AnyFunSuite {
  test("spreadFragmentSpreads function works") {
    val validQuery = gql"""
      mutation {
        business(username: "anasbarg") {
          username
          ...Branches
        }
      }
    
      fragment Branches on Business {
        branches {
          ...Address
        }
      }
    
      fragment Address on Branch {
        address
      }
    """

    val expected = gql"""
    mutation  {
      business(username: "anasbarg") {
        username
        branches {
          address
        }
      }
    }
    
    fragment Branches on Business {
      branches {
        address
      }
    }
    
    fragment Address on Branch {
      address
    }
    """.renderPretty

    val queryAfterSpreadingFragmentSpreads = Document(
      validQuery.definitions
        .filter {
          case _: OperationDefinition => true
          case _: FragmentDefinition  => true
          case _                      => false
        }
        .map(
          s =>
            RequestReducer
              .substitute(
                s.asInstanceOf[SelectionContainer],
                validQuery,
                JsObject.empty
              )
              .asInstanceOf[Definition]
        )
    )

    assert(queryAfterSpreadingFragmentSpreads.renderPretty == expected)
  }
}
