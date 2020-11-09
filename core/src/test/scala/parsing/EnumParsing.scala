package pragma.parsing

import pragma.domain._, pragma.parsing._
import scala.util._
import org.parboiled2.Position
import org.scalatest.flatspec.AnyFlatSpec

class EnumParsing extends AnyFlatSpec {
  "An enum" should "be parsed correctly" in {
    val code = """
        enum WeekDay {
            "Sunday"
            "Monday", Tuesday
            Wednesday,
            Thursday
            Friday,
            Saturday
        }
        """
    val parsedEnum = new PragmaParser(code).syntaxTree.run()
    val expected = Success(
      List(
        PEnum(
          "WeekDay",
          List(
            "Sunday",
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday"
          ),
          Some(PositionRange(Position(14, 2, 14), Position(21, 2, 21)))
        )
      )
    )
    assert(parsedEnum == expected)
  }
}
