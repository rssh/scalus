package scalus.macros

import scalus.builtin
import scalus.builtin.Builtins
import scalus.builtin.Data
import scalus.uplc.ExprBuilder
import scalus.uplc.ExprBuilder.*
import scalus.uplc.{Expr => Exp}
import scalus.uplc.{Term => Trm}

import scala.collection.immutable
import scala.quoted.*
import scala.annotation.nowarn
import scala.collection.mutable.ListBuffer
object Macros {
    @nowarn
    def lamMacro[A: Type, B: Type](f: Expr[Exp[A] => Exp[B]])(using Quotes): Expr[Exp[A => B]] =
        import quotes.reflect.*
        val name = f.asTerm match
            // lam(x => body)
            case Inlined(
                  _,
                  _,
                  Block(List(DefDef(_, List(List(ValDef(name, _, _))), _, body)), _)
                ) =>
                Expr(name)
            // lam { x => body }
            case Inlined(
                  _,
                  _,
                  Block(List(), Block(List(DefDef(_, List(List(ValDef(name, _, _))), _, body)), _))
                ) =>
                Expr(name)
            case x => report.errorAndAbort(x.toString)
        '{
            Exp(Trm.LamAbs($name, $f(vr($name)).term))
        }

    def fieldAsExprDataMacro[A: Type](e: Expr[A => Any])(using
        Quotes
    ): Expr[Exp[Data] => Exp[Data]] =
        import quotes.reflect.*
        e.asTerm match
            case Inlined(
                  _,
                  _,
                  Block(List(DefDef(_, _, _, Some(select @ Select(_, fieldName)))), _)
                ) =>
                def genGetter(
                    typeSymbolOfA: Symbol,
                    fieldName: String
                ): Expr[Exp[Data] => Exp[Data]] =
                    val fieldOpt: Option[(Symbol, Int)] =
                        if typeSymbolOfA == TypeRepr.of[Tuple2].typeSymbol then
                            fieldName match
                                case "_1" =>
                                    typeSymbolOfA.caseFields
                                        .find(_.name == fieldName)
                                        .map(s => (s, 0))
                                case "_2" =>
                                    typeSymbolOfA.caseFields
                                        .find(_.name == fieldName)
                                        .map(s => (s, 1))
                                case _ =>
                                    report.errorAndAbort(
                                      "Unexpected field name for Tuple2 type: " + fieldName
                                    )
                        else typeSymbolOfA.caseFields.zipWithIndex.find(_._1.name == fieldName)
//          report.info(s"$typeSymbolOfA => fieldOpt: $fieldOpt")
                    fieldOpt match
                        case Some((fieldSym: Symbol, idx)) =>
                            val idxExpr = Expr(idx)
                            '{
                                var expr: Exp[Data] => Exp[List[Data]] = d =>
                                    sndPair(unConstrData(d))
                                var i = 0
                                while i < $idxExpr do
                                    val exp =
                                        expr // save the current expr, otherwise it will loop forever
                                    expr = d => tailList(exp(d))
                                    i += 1
                                d => headList(expr(d))
                            }
                        case None =>
                            report.errorAndAbort("fieldMacro: " + fieldName)

                def composeGetters(tree: Tree): Expr[Exp[Data] => Exp[Data]] = tree match
                    case Select(select @ Select(_, _), fieldName) =>
                        val a = genGetter(select.tpe.typeSymbol, fieldName)
                        val b = composeGetters(select)
                        '{ $a compose $b }
                    case Select(ident @ Ident(_), fieldName) =>
                        genGetter(ident.tpe.typeSymbol, fieldName)
                    case _ =>
                        report.errorAndAbort(
                          s"field macro supports only this form: _.caseClassField1.field2, but got " + tree.show
                        )
                composeGetters(select)
            case x => report.errorAndAbort(s"fieldAsExprDataMacro: $x")

    def fieldAsDataMacro[A: Type](e: Expr[A => Any])(using Quotes): Expr[Data => Data] =
        import quotes.reflect.*
        fieldAsDataMacroTerm(e.asTerm)

    def fieldAsDataMacroTerm(using q: Quotes)(e: q.reflect.Term): Expr[Data => Data] =
        import quotes.reflect.*
        e match
            case Inlined(_, _, block) => fieldAsDataMacroTerm(block)
            case Block(List(DefDef(_, _, _, Some(select @ Select(_, fieldName)))), _) =>
                def genGetter(typeSymbolOfA: Symbol, fieldName: String): Expr[Data => Data] =
                    val fieldOpt: Option[(Symbol, Int)] =
                        if typeSymbolOfA == TypeRepr.of[Tuple2].typeSymbol then
                            fieldName match
                                case "_1" =>
                                    typeSymbolOfA.caseFields
                                        .find(_.name == fieldName)
                                        .map(s => (s, 0))
                                case "_2" =>
                                    typeSymbolOfA.caseFields
                                        .find(_.name == fieldName)
                                        .map(s => (s, 1))
                                case _ =>
                                    report.errorAndAbort(
                                      "Unexpected field name for Tuple2 type: " + fieldName
                                    )
                        else typeSymbolOfA.caseFields.zipWithIndex.find(_._1.name == fieldName)
//          report.info(s"$typeSymbolOfA => fieldOpt: $fieldOpt")
                    fieldOpt match
                        case Some((fieldSym: Symbol, idx)) =>
                            '{ d =>
                                // a bit of staged programming here
                                ${
                                    var expr = '{ Builtins.unConstrData(d).snd }
                                    var i = 0
                                    while i < idx do
                                        val exp =
                                            expr // save the current expr, otherwise it will loop forever
                                        expr = '{ $exp.tail }
                                        i += 1
                                    expr
                                }.head
                            }

                        case None =>
                            report.errorAndAbort("fieldMacro: " + fieldName)

                def composeGetters(tree: Tree): Expr[Data => Data] = tree match
                    case Select(select @ Select(_, _), fieldName) =>
                        val a = genGetter(select.tpe.typeSymbol, fieldName)
                        val b = composeGetters(select)
                        '{ ddd => ${ Expr.betaReduce('{ $a($b(ddd)) }) } }
                    case Select(ident @ Ident(_), fieldName) =>
                        genGetter(ident.tpe.typeSymbol, fieldName)
                    case _ =>
                        report.errorAndAbort(
                          s"field macro supports only this form: _.caseClassField1.field2, but got " + tree.show
                        )
                composeGetters(select)
            case x => report.errorAndAbort(x.toString)

    import upickle.default.*
    def mkReadWriterImpl[A: Type](using Quotes): Expr[ReadWriter[A]] = {
        import scala.quoted.*
        import quotes.reflect.*
        val tpe = TypeTree.of[A]
        val fields = tpe.symbol.declaredFields
        val fieldNames = fields.map(_.name)
        val impl = '{
            upickle.default
                .readwriter[ujson.Value]
                .bimap[A](
                  m =>
                      ujson.Obj.from(${
                          Expr.ofList(
                            fields.map(name =>
                                '{
                                    (
                                      ${ Expr(name.name) },
                                      writeJs[Long](${ Select('{ m }.asTerm, name).asExprOf[Long] })
                                    )
                                }
                            )
                          )
                      }),
                  json =>
                      ${
                          val stats = ListBuffer[Statement]()
                          // val params = new A()
                          val value = ValDef(
                            Symbol.newVal(
                              Symbol.spliceOwner,
                              "params",
                              tpe.tpe.widen,
                              Flags.EmptyFlags,
                              Symbol.noSymbol
                            ),
                            Some(New(tpe).select(tpe.symbol.primaryConstructor).appliedToNone)
                          )
                          val ref = Ref(value.symbol)
                          stats += value
                          // params.field1 = read[Long](json.obj("field1"))
                          // ...
                          fields.foreach { field =>
                              stats += Assign(
                                ref.select(field),
                                '{ read[Long](json.obj(${ Expr(field.name.toString) })) }.asTerm
                              )
                          }
                          // { val params = new A(); params.field1 = read[Long](json.obj("field1")); ...; params }
                          Block(stats.toList, ref).asExprOf[A]
                      }
                )
        }
        // println(impl.asTerm.show(using Printer.TreeShortCode))
        impl
    }

    def mkClassFieldsFromSeqIsoImpl[A: Type](using
        Quotes
    ): Expr[(A => Seq[Long], Seq[Long] => A)] = {
        import scala.quoted.*
        import quotes.reflect.*
        val tpe = TypeTree.of[A]
        val fields = tpe.symbol.declaredFields
        val fieldNames = fields.map(_.name)
        val impl = '{
            (
              (m: A) =>
                  ${ Expr.ofSeq(fields.map(name => '{ m }.asTerm.select(name).asExprOf[Long])) },
              (seq: Seq[Long]) =>
                  ${
                      val stats = ListBuffer[Statement]()
                      // val params = new A()
                      val value = ValDef(
                        Symbol.newVal(
                          Symbol.spliceOwner,
                          "params",
                          tpe.tpe.widen,
                          Flags.EmptyFlags,
                          Symbol.noSymbol
                        ),
                        Some(New(tpe).select(tpe.symbol.primaryConstructor).appliedToNone)
                      )
                      val ref = Ref(value.symbol)
                      stats += value
                      // params.field1 = seq(0)
                      // ...
                      for (field, idx) <- fields.zipWithIndex do
                          stats += Assign(ref.select(field), '{ seq(${ Expr(idx) }) }.asTerm)

                      // { val params = new A(); params.field1 =...; params }
                      Block(stats.toList, ref).asExprOf[A]
                  }
            )
        }
        // println(impl.asTerm.show(using Printer.TreeShortCode))
        impl
    }

    def inlineBuiltinCostModelJsonImpl(using Quotes): Expr[String] = {
        import scala.quoted.*
        import quotes.reflect.*
        val input = this.getClass().getResourceAsStream("/builtinCostModel.json")
        val string = scala.io.Source.fromInputStream(input).mkString
        Expr(string)
    }
}
