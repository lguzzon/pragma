package setup.schemaGenerator

import scala.language.implicitConversions
import pragma.domain._
import sangria.ast.NamedType

object SchemaGeneratorImplicits {
  implicit def fromGraphQLNamedTypeToPType(namedType: NamedType): PType =
    namedType.name match {
      case "String"  => PString
      case "Int"     => PInt
      case "Float"   => PFloat
      case "ID"      => PString
      case "Boolean" => PBool
      case "Any"     => PAny
      case name      => PReference(name)
    }
}
