package scalus.uplc
import Term.*

object DeBruijn:
    def deBruijnProgram(p: Program): DeBruijnedProgram =
        val term = DeBruijn.deBruijnTerm(p.term)
        DeBruijnedProgram(version = p.version, term = term)

    def fromDeBruijnProgram(p: DeBruijnedProgram): Program =
        val term = DeBruijn.fromDeBruijnTerm(p.term)
        Program(version = p.version, term = term)

    /** Converts a term with named variables to a term with De Bruijn indices. We use unique
      * negative indices to represent free variables.
      * @param term
      *   the term with named variables
      * @return
      *   the term with De Bruijn indices
      */
    def deBruijnTerm(term: Term): Term =
        var unique = 0
        def deBruijnTerm(term: Term, env: List[String]): Term =
            term match
                case Var(name) =>
                    val idx = env.indexOf(name.name)
                    if idx == -1 then
                        unique -= 1
                        Var(name.copy(index = unique))
                        // throw new Exception(s"Variable $name not found in environment $env")
                    else Var(name.copy(index = idx + 1)) // 1-based index
                case LamAbs(name, term) => LamAbs(name, deBruijnTerm(term, name :: env))
                case Apply(f, arg)      => Apply(deBruijnTerm(f, env), deBruijnTerm(arg, env))
                case Force(term)        => Force(deBruijnTerm(term, env))
                case Delay(term)        => Delay(deBruijnTerm(term, env))
                case Constr(tag, args)  => Constr(tag, args.map(deBruijnTerm(_, env)))
                case Case(arg, cases) =>
                    Case(deBruijnTerm(arg, env), cases.map(deBruijnTerm(_, env)))
                case Const(const) => term
                case Builtin(bn)  => term
                case Error        => term
        deBruijnTerm(term, Nil)

    def fromDeBruijnTerm(term: Term): Term =
        var idx = 0
        def go(term: Term, env: List[String]): Term = term match
            case Var(name) =>
                val binderName =
                    if name.index < 0 then name.name else env(name.index - 1) // 1-based index
                Var(name.copy(name = binderName))
            case LamAbs(_, term) =>
                val binderName = s"i$idx"
                idx += 1
                LamAbs(binderName, go(term, binderName :: env))
            case Apply(f, arg) => Apply(go(f, env), go(arg, env))
            case Force(term)   => Force(go(term, env))
            case Delay(term)   => Delay(go(term, env))
            case Constr(tag, args) =>
                Constr(tag, args.map(go(_, env)))
            case Case(arg, cases) =>
                Case(go(arg, env), cases.map(go(_, env)))
            case Const(const) => term
            case Builtin(bn)  => term
            case Error        => term

        go(term, Nil)
