package setup

import parsing.HeavenlyParser
import domain._
import domain.primitives._
import org.parboiled2.Position
import scala.collection.immutable.ListMap

object MockSyntaxTree {
  val businessModel = HModel(
    "Business",
    List(
      HModelField(
        "username",
        HString,
        None,
        List(),
        None
      ),
      HModelField(
        "email",
        HString,
        None,
        List(
          FieldDirective(
            "publicCredential",
            HInterfaceValue(ListMap(), HInterface("", List(), None)),
            None
          ),
          FieldDirective(
            "primary",
            HInterfaceValue(ListMap(), HInterface("", List(), None)),
            None
          )
        ),
        None
      ),
      HModelField(
        "password",
        HString,
        None,
        List(
          FieldDirective(
            "publicCredential",
            HInterfaceValue(ListMap(), HInterface("", List(), None)),
            None
          )
        ),
        None
      ),
      HModelField(
        "branches",
        HArray(HReference("Branch")),
        None,
        List(
          FieldDirective(
            "secretCredential",
            HInterfaceValue(ListMap(), HInterface("", List(), None)),
            None
          )
        ),
        None
      ),
      HModelField(
        "businessType",
        HReference("BusinessType"),
        None,
        Nil,
        None
      ),
    ),
    List(
      ModelDirective(
        "user",
        HInterfaceValue(ListMap(), HInterface("", List(), None)),
        None
      )
    ),
    None
  )

  val branchModel =
    HModel(
      "Branch",
      List(
        HModelField(
          "address",
          HString,
          None,
          List(),
          None
        ),
        HModelField(
          "business",
          HReference("Business"),
          None,
          List(
            FieldDirective(
              "primary",
              HInterfaceValue(ListMap(), HInterface("", List(), None)),
              None
            )
          ),
          None
        )
      ),
      List(),
      None
    )

  val businessTypeEnum = HEnum("BusinessType", List("FOOD", "CLOTHING", "OTHER"), None)
  val permissions = Permissions(
    globalTenant = Tenant(
      "global",
      Nil,
      None
    ),
    tenents = Nil,
    None
  )

  val syntaxTree =
    SyntaxTree(Nil, Nil, List(businessModel, branchModel), List(businessTypeEnum), permissions)
}