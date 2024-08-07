# Changelog

## 0.7.1 (2024-08-04)

### Added

- implemented all Plutus V1/V2 builtins on JVM and JavaScript platforms
- passing all Plutus Conformance Tests on JVM and JavaScript platforms
- `OptimizingSirToUplcLowering` SIR to UPLC lowering with built-in optimizations
- PlutusV3 constr/case support
- `Data.to*` extension methods

### Changed

- speedup `UplcParser` by 10x

### Fixed

- use Java 11 on Github CI and release builds

## 0.7.0 (2024-05-29)

Scalus CEK and cost calculation implementation is now feature complete.

We were able to validate transactions from whole Cardano Epoch 484 using Scalus and Cardano Client Lib.

### Added

- `ScalusTransactionEvaluator` - Cardano Client Lib (CCL) `TransactionEvaluator` implementation. You can now use Scalus to evaluate scripts and their costs off-chain during transaction building.
- SIR and UPLC optimizations: `RemoveRecursivity`, `EtaReduce`
- `evaluateScriptRestricting` mode
- Advanced documentation
- `Data` JSON codec

### Changed

- optimized From/ToData instances to generate less code
- `PlutusV1Params`, `PlutusV2Params` are now classes with Long fields
- Removed type parameter from `Interval`
- bytesToHex uses lowercase hex characters

### Fixed

- memoryUsageInteger was wrong in certain cases
- sliceByteString wrong order of parameters
- Data CBOR encoding of large integers must be chunked by 64 byte chunks
- typo in `ConstAboveDiagonal` cost calculation implementation

## 0.6.1 (2024-04-18)

### Added

- compile `==`, `!=` operators as Plutus builtins for `Boolean`, `ByteString`, `String` and `Data` types
- `++` operator, `size` and `length` functions for `ByteString`

### Fixed

- wrong `ConstAboveDiagonal` cost calculation used in division

## 0.6.0 (2024-04-05)

### Added

- fast debruijned CEK machine implementation
- CEK machine benchmarking, runs on par with high performance Haskell implementation
- CEK machine execution budget calculation
- cost model for CEK machine loading from Cardano CLI and Blockfrost API JSONs
- updated to Scala 3.3.3
- updated to Scala.js 1.16.0, 3x reduction in JS bundle size

### Changed

- lots of internal refactoring

## 0.5.0 (2024-02-17)

### Added

- better error messages in the compiler
- Scalus Intermediate Representation (SIR) now has a version number (requires recompilation of existing SIR)
- SIR and UPLC pretty printers can print normal and syntax highlighted code using XTerm colors
- UPLC pretty printer outputs a better indented code
- added Blake2b 224 builtin implementation on JVM
- updated dependencies: Scala 3.3.2
- use uplc cli from Plutus 1.15.0.1

### Changed

- moved scalus.uplc.Data to scalus.builtin.Data
- renamed scalus.builtins to scalus.builtin

### Fixed

- Plutus Data Map can have duplicate keys; fixed Data CBOR codec to handle this case.
