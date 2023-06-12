package scalus.ledger.api.v2

import scalus.Compile
import scalus.builtins
import scalus.builtins.Builtins
import scalus.ledger.api.v1.Datum
import scalus.ledger.api.v1.DatumHash
import scalus.uplc.Data
import scalus.uplc.Data.ToData
import scalus.uplc.ToData

@Compile
object ToDataInstances {
  import scalus.uplc.Data.toData
  import scalus.uplc.ToDataInstances.given

  given OutputDatumToData[T <: OutputDatum]: ToData[T] = (d: T) =>
    d match
      case OutputDatum.NoOutputDatum => Builtins.mkConstr(0, Builtins.mkNilData())
      case OutputDatum.OutputDatumHash(datumHash) =>
        Builtins.mkConstr(1, builtins.List(datumHash.toData))
      case OutputDatum.OutputDatum(datum) => Builtins.mkConstr(2, builtins.List(datum))

  given ToData[TxOut] = ToData.deriveCaseClass[TxOut](0)

  given ToData[TxInInfo] = ToData.deriveCaseClass[TxInInfo](0)

  given ToData[TxInfo] = ToData.deriveCaseClass[TxInfo](0)

  given ToData[ScriptContext] = ToData.deriveCaseClass[ScriptContext](0)
}