package cli

import org.scalatest.flatspec.AnyFlatSpec
import cli.utils._

class ContentReading extends AnyFlatSpec {
  "`readContent` function" should "zip directories correctly" in {
    val (indexContent, indexIsBinary) = readContent {
      os.pwd / "cli" / "src" / "test" / "scala" / "test-js-project" / "index.js"
    }.get

    val expectedIndexContent =
      """const utils = require('./utils')
        |const nestedUtils = require('./nested-utils/nested-utils')
        |
        |utils.log('Hellooo')""".stripMargin

    assert(expectedIndexContent == indexContent)
    assert(!indexIsBinary)

    val (_, isBinary) = readContent {
      os.pwd / "cli" / "src" / "test" / "scala" / "test-js-project"
    }.get

    assert(isBinary)
  }
}
