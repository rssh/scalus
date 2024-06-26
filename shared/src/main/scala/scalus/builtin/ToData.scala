package scalus.builtin

import Data.ToData
import scala.collection.immutable.List

import scala.quoted.*

/** ToData[A] derivation macros.
  */
object ToData {
    inline def deriveCaseClass[T](inline constrIdx: Int): ToData[T] = ${
        deriveCaseClassMacro[T]('{ constrIdx })
    }

    def deriveCaseClassMacro[T: Type](constrIdx: Expr[Int])(using Quotes): Expr[ToData[T]] =
        import quotes.reflect.*
        val classSym = TypeTree.of[T].symbol
        val companionModuleRef = classSym.companionModule
        val unapplyRef = companionModuleRef.methodMember("unapply").head.termRef
        val constr = classSym.primaryConstructor
        val params = constr.paramSymss.flatten
        val paramsNameType = params.map(p => p.name -> p.typeRef)
        /*
      Generate a pattern match to introduce all the params,
      to avoid a.field1, a.field2, etc.
      Something ike:
        a match
          case A(field1, field2, ...) =>
            mkConstr(
              BigInt($constrIdx),
              mkCons(field1.toData, mkCons(field2.toData, ...))
            )
         */
        def genMatch(prodTerm: Term, params: List[(String, TypeRepr)])(using Quotes) = {
            val bindingsSymbols = params.map { (name, tpe) =>
                (Symbol.newBind(Symbol.noSymbol, name, Flags.EmptyFlags, tpe), tpe)
            }

            val bindings = bindingsSymbols.map { case (symbol, _) =>
                Bind(symbol, Wildcard())
            }
            val rhs = genRhs(prodTerm, bindingsSymbols).asTerm
            val m =
                Match(prodTerm, List(CaseDef(Unapply(Ident(unapplyRef), Nil, bindings), None, rhs)))
            m
        }

        def genRhs(prodTerm: Term, bindings: List[(Symbol, TypeRepr)])(using Quotes) = '{
            Builtins.constrData(
              BigInt($constrIdx),
              ${
                  val args = bindings
                      .map { case (binding, tpe) =>
                          tpe.asType match
                              case '[t] =>
                                  Expr.summon[ToData[t]] match
                                      case None =>
                                          report.errorAndAbort(
                                            s"Could not find implicit for ToData[${tpe.widen.show}]"
                                          )
                                      case Some(toData) =>
                                          val arg = Ident(binding.termRef).asExprOf[t]
                                          '{ $toData($arg) }
                      }
                      .asInstanceOf[List[Expr[Data]]]
                  args.foldRight('{ scalus.builtin.Builtins.mkNilData() }) { (data, acc) =>
                      '{ scalus.builtin.Builtins.mkCons($data, $acc) }
                  }
              }
            )
        }

        '{ (product: T) =>
            ${
                val prodTerm = '{ product }.asTerm
                genMatch(prodTerm, paramsNameType).asExprOf[Data]
            }
        }

    extension [A: ToData](a: A) inline def toData: Data = summon[ToData[A]].apply(a)
}
