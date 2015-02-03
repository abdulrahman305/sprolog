package ca.hyperreal.sprolog

import java.io.{Reader, StringReader, ByteArrayOutputStream, PrintStream}

import collection.mutable.{ListBuffer, ArrayBuffer, HashMap, HashSet}

import ca.hyperreal.rtcep._


object Prolog
{
	val parser =
		new AbstractPrologParser[AST]
		{
			def primary( value: Token ) =
				value.kind match
				{
					case 'atom => StructureAST( Symbol(value.s), IndexedSeq.empty )
					case 'string => StringAST( value.s )
					case 'integer => NumberAST( value.s.toInt )
					case 'variable => VariableAST( Symbol(value.s) )
					case `nilsym` => StructureAST( nilsym, IndexedSeq.empty )
					case _ => value.start.head.pos.error( "unrecognized token: [" + value.kind + "]" )
				}
			
			def structure( functor: Token, args: IndexedSeq[Value[AST]] ) =
				StructureAST(
					(functor.kind match
					{
						case 'atom|_: Character => Symbol(functor.s)
						case s: Symbol => s
					}),
					args.map(_.v) )
		}
	
	val COMMA = Symbol( "," )
	val RULE = Symbol( ":-" )
	val DOT = Symbol( "." )
	val NIL = Symbol( "[]" )
	
	def parseClause( s: String ) = parser.parse( s, 4, '.' )
	
	def parseQuery( s: String ) =
	{
	val (query, rest) = parseClause( s )
	
		if (rest.head.end || rest.tail.head.end)
		{
			if (!query.isInstanceOf[StructureAST])
				sys.error( "expected a structure/atom" )
				
			query.asInstanceOf[StructureAST]
		}
		else
			rest.tail.head.pos.error( "unexpected input following query" )
	}
	
	def parseProgram( s: String ) =
	{
	val clauses = new ListBuffer[StructureAST]
		
		def clause( toks: Stream[Token] )
		{
		val (c, rest) = parser.parseTokens( toks, '.' )
		
			if (rest.head.kind != '.')
				rest.head.pos.error( "expected '.' following clause" )
				
			if (!c.isInstanceOf[StructureAST])
				sys.error( "expected a structure/atom" )
				
			clauses += c.asInstanceOf[StructureAST]
			
			if (!rest.tail.head.end)
				clause( rest.tail )
		}
		
		clause( parser.scan(new StringReader(s), 4) )
		clauses.toList
	}
	
	def struct( outerreg: Int, nextreg: Int, varmap: HashMap[Symbol, (Int, Int)], regmap: HashMap[Int, StructureAST], permmap: Map[Symbol, Int] ) =
	{
	var r = nextreg

		def struct( reg: Int )
		{
		val s = regmap(reg)
		val s1 = StructureAST( s.f, s.args.map (
			_ match
			{
				case VariableAST( v ) =>
					varmap.get(v) match
					{
						case None =>
							permmap.get(v) match
							{
								case None =>
									val res = Var( v, 0, r )
									
									varmap(v) = (0, r)
									r += 1
									res
								case Some( p ) =>
									val res = Var( v, 1, p )
									
									varmap(v) = (1, p)
									res
							}
						case Some( (b, n) ) =>
							Var( v, b, n )
					}
				case str: StructureAST =>
					val res = Var( null, 0, r )
					
					regmap(r) = str
					r += 1
					res
			}))
		
			regmap(reg) = s1
			
			for (i <- 0 until s1.arity)
			{
			val v = s1.args(i).asInstanceOf[Var]
			
				if (v.bank == 0 && regmap.contains( v.reg ))
					struct( v.reg )
			}
		}
		
		struct( outerreg )
		r
	}
	
	def conjunctive( q: StructureAST ): Stream[StructureAST] =
		q match
		{
			case StructureAST( COMMA, IndexedSeq(left: StructureAST, right: StructureAST ) ) => left #:: conjunctive( right )
			case StructureAST( COMMA, IndexedSeq(left, right: StructureAST ) ) => sys.error( "left argument not a structure" )
			case StructureAST( COMMA, IndexedSeq(left: StructureAST, right ) ) => sys.error( "right argument not a structure" )
			case _: StructureAST => Stream( q )
			case _ => sys.error( "not a structure" )
		}
	
	def permanent( q: StructureAST, varset: HashSet[Symbol] ) =
	{
	val permset = HashSet[Symbol]()
	val ts = conjunctive( q )
	
		varset ++= structvars( ts.head )
		
		for (t <- ts.tail)
		{
		val vars = structvars( t ).toSet
		
			permset ++= varset intersect vars
			varset ++= vars
		}
		
	val permvars = new HashMap[Symbol, Int]
	var p = 1
	
		for (t <- conjunctive( q ); s <- structvars( t ) if permset( s ))
		{
			permvars(s) = p
			permset -= s
			p += 1
		}

		permvars.toMap
	}
	
	def structvars( q: StructureAST ) =
	{
	val vars = new ListBuffer[Symbol]
	
		def _structvars( t: AST )
		{
			t match
			{
				case VariableAST( v ) => vars += v
				case StructureAST( _, args ) =>
					for (a <- args)
						_structvars( a )
			}
		}
		
		_structvars( q )
		vars.toList
	}
	
	def compileQuery( q: StructureAST ) =
	{
	val code = new ArrayBuffer[Instruction]
	val permvars = permanent( q, new HashSet[Symbol] )
	
		code += AllocateInstruction( permvars.size + 1 )
		new Query( body(q, code, permvars, new HashMap[Symbol, (Int, Int)], true) )
	}
	
	def body( q: StructureAST, code: ArrayBuffer[Instruction], permvars: Map[Symbol, Int], varmap: HashMap[Symbol, (Int, Int)], variables: Boolean ) =
	{
	val seen = new HashSet[(Int, Int)] ++ varmap.values
	
		for (t <- conjunctive( q ))
		{
		var nextreg = t.arity + 1
		
			for (arg <- 1 to t.arity)
			{
				t.args(arg - 1) match
				{
					case VariableAST( v ) =>
						varmap.get( v ) match
						{
							case None =>
								permvars.get( v ) match
								{
									case None =>
										code += PutVariableInstruction( if (variables) v else null, 0, nextreg, arg )
										varmap(v) = (0, nextreg)
										seen add (0, nextreg)
										nextreg += 1
									case Some( n ) =>
										code += PutVariableInstruction( if (variables) v else null, 1, n, arg )
										varmap(v) = (1, n)
										seen add (1, n)
								}
							case Some( (b, n) ) =>
								code += PutValueInstruction( b, n, arg )
						}
					case s: StructureAST =>
						val regmap = HashMap(arg -> s)
						val eqs = new ArrayBuffer[(Int, StructureAST)]
						
						nextreg = struct( arg, nextreg, varmap, regmap, permvars )
						
						def arrange( reg: Int )
						{
						val s = regmap(reg)
						
							for (i <- 0 until s.arity)
							{
							val v = s.args(i).asInstanceOf[Var]
							
								if (v.bank == 0 && regmap.contains( v.reg ))
									arrange( v.reg )
							}
							
							eqs += reg -> s
						}
						
						arrange( arg )
						
						for (e <- eqs)
						{
							code += PutStructureInstruction( FunCell(e._2.f, e._2.arity), e._1 )
							seen add (0, e._1)
							
							for (Var( s, b, n ) <- e._2.args.asInstanceOf[Seq[Var]])
								if (seen( (b, n) ))
									code += SetValueInstruction( b, n )
								else
								{
									code += SetVariableInstruction( if (variables) s else null, b, n )
									seen add (b, n)
								}
						}
				}
			}
			
			code += CallInstruction( FunCell(t.f, t.arity) )
			
			for ((k, v) <- varmap)
				if (v._1 == 0)
					varmap -= k

//			varmap.clear
			
			for (e <- seen)
				if (e._1 == 0)
					seen -= e
					
//			seen.clear
		}

		code += DeallocateInstruction
		code.toVector
	}
	
	def fact( f: StructureAST, code: ArrayBuffer[Instruction] )
	{
		head( f, code, Map.empty )
		code += ProceedInstruction
	}
	
	def rule( h: StructureAST, b: StructureAST, code: ArrayBuffer[Instruction] )
	{
	val permvars = permanent( b, new HashSet ++ structvars(h) )
	
		code += AllocateInstruction( permvars.size + 1 )
		body( b, code, permvars, head(h, code, permvars), false )
	}
	
	def head( p: StructureAST, code: ArrayBuffer[Instruction], permvars: Map[Symbol, Int] ) =
	{
	val varmap = new HashMap[Symbol, (Int, Int)]
	var nextreg = p.arity + 1
	val regmap = new HashMap[Int, StructureAST]
	val seen = new HashSet[(Int, Int)]
	
		for (arg <- 1 to p.arity)
		{
			p.args(arg - 1) match
			{
				case VariableAST( v ) =>
					varmap.get( v ) match
					{
						case None =>
							permvars.get( v ) match
							{
								case None =>
									code += GetVariableInstruction( 0, nextreg, arg )
									varmap(v) = (0, nextreg)
									seen add (0, nextreg)
									nextreg += 1
								case Some( n ) =>
									code += GetVariableInstruction( 1, n, arg )
									varmap(v) = (1, n)
									seen add (1, n)
							}
						case Some( (b, n) ) =>
							code += GetValueInstruction( b, n, arg )
					}
				case s: StructureAST =>
					regmap(arg) = s
				
					nextreg = struct( arg, nextreg, varmap, regmap, permvars )
					
					val e = regmap(arg)
					
					code += GetStructureInstruction( FunCell(e.f, e.arity), arg )
					seen add (0, arg)
					
					for (Var( _, b, n ) <- e.args.asInstanceOf[Seq[Var]])
						if (seen( (b, n) ))
							code += UnifyValueInstruction( b, n )
						else
						{
							code += UnifyVariableInstruction( b, n )
							seen add (b, n)
						}
			}
		}

		for (e <- regmap.toSeq.filter( a => a._1 > p.arity ).sortWith( (a, b) => a._1 < b._1 ))
		{
			code += GetStructureInstruction( FunCell(e._2.f, e._2.arity), e._1 )
			
			for (Var( _, b, n ) <- e._2.args.asInstanceOf[Seq[Var]])
				if (seen( (b, n) ))
					code += UnifyValueInstruction( b, n )
				else
				{
					code += UnifyVariableInstruction( b, n )
					seen add (b, n)
				}
		}
		
		varmap
	}
	
	def clause( c: StructureAST, code: ArrayBuffer[Instruction], procmap: HashMap[FunCell, Int],
				proctype: HashMap[FunCell, Int], proclabel: HashMap[FunCell, Label] )
	{
	val pred =
		c match
		{
			case StructureAST( RULE, IndexedSeq(h: StructureAST, b: StructureAST) ) => FunCell( h.f, h.arity )
			case f: StructureAST => FunCell( f.f, f.arity )
		}
		
		if (procmap contains pred)
		{
			proclabel( pred ) backpatch code.length
			
			if (proctype( pred ) > 1)
			{
			val l = new Label
			
				code += RetryMeElseInstruction( l )
				proclabel( pred ) = l
				proctype( pred ) -= 1
			}
			else
				code += TrustMeInstruction
		}
		else
		{
			procmap( pred ) = code.length
			
			if (proctype( pred ) > 1)
			{
			val l = new Label
			
				code += TryMeElseInstruction( l )
				proclabel( pred ) = l
				proctype( pred ) -= 1
			}
		}
		
		c match
		{
			case StructureAST( RULE, IndexedSeq(h: StructureAST, b: StructureAST) ) => rule( h, b, code )
			case f: StructureAST => fact( f, code )
		}
	}
	
	def program( p: String ) = compileProgram( parseProgram(p) )
	
	def query( p: Program, q: String ) =
	{
	val wam = new WAM
	val buf = new StringBuilder
	val out = new ByteArrayOutputStream
	
		wam.program = p
		Console.withOut( new PrintStream(out, true) ) {wam query compileQuery(parseQuery(q))}
		out.toString.trim
	}
	
	def queryFirst( p: Program, q: String ) =
	{
	val wam = new WAM
	val buf = new StringBuilder
	val out = new ByteArrayOutputStream
	
		wam.program = p
		Console.withOut( new PrintStream(out, true) ) {wam queryFirst compileQuery(parseQuery(q))}
		out.toString.trim
	}
	
	def compileProgram( cs: List[StructureAST] ) =
	{
	val proctype = new HashMap[FunCell, Int]
	val proclabel = new HashMap[FunCell, Label]
	val procmap = new HashMap[FunCell, Int]
	
		for (c <- cs)
		{
		val pred =
			c match
			{
			case StructureAST( RULE, IndexedSeq(h: StructureAST, b: StructureAST) ) =>
				FunCell( h.f, h.arity )
			case f: StructureAST =>
				FunCell( f.f, f.arity )
			}
			
			if (proctype contains pred)
				proctype(pred) += 1
			else
				proctype(pred) = 1
		}
		
	val code = new ArrayBuffer[Instruction]
	val permvars = Map[Symbol, Int]()
	
		for (c <- cs)
			clause( c, code, procmap, proctype, proclabel )
		
		new Program( code.toVector, procmap.toMap )
	}
	
	def listing( code: Seq[Instruction] )
	{
	val labels = new HashMap[Int, Label]
	
		for (i <- 0 until code.size)
		{
			print( labels.get( i ) match 
				{
					case None => "\t"
					case Some( l ) => l + ":\n\t"
				} )
			
			println( code(i) match
				{
				case PutStructureInstruction( f, i )		=> s"put_structure $f, $i"
				case SetVariableInstruction( v, b, i )		=> s"set_variable $v, $b, $i"
				case SetValueInstruction( b, i )			=> s"set_value $b $i"
				case GetStructureInstruction( f, i )		=> s"get_structure $f, $i"
				case UnifyVariableInstruction( b, i )		=> s"unify_variable $b $i"
				case UnifyValueInstruction( b, i )			=> s"unify_value $b $i"
				case PutVariableInstruction( v, b, n, i )	=> s"put_variable $v $b $n $i"
				case PutValueInstruction( b, n, i )			=> s"put_value $b $n $i"
				case GetVariableInstruction( b, n, i )		=> s"get_variable $b $n $i"
				case GetValueInstruction( b, n, i )			=> s"get_value $b $n $i"
				case CallInstruction( f )					=> s"call $f"
				case ProceedInstruction						=> "proceed"
				case AllocateInstruction( n )				=> s"allocate $n"
				case DeallocateInstruction					=> "deallocate"
				case TryMeElseInstruction( l )				=>
					labels(l.ref) = l
					s"try_me_else $l"
				case RetryMeElseInstruction( l )			=>
					labels(l.ref) = l
					s"retry_me_else $l"
				case TrustMeInstruction						=> "trust_me"
				} )
		}
	}
	
	case class Var( v: Symbol, bank: Int, reg: Int ) extends AST
	case class RHS( f: Symbol, args: Vector[Var] )
	case class Eq( lhs: Int, rhs: RHS )

	def isList( a: AST ): Boolean =
		a match
		{
			case StructureAST( NIL, IndexedSeq() ) => true
			case StructureAST( DOT, IndexedSeq(head, tail) ) if isList( tail ) => true
			case _ => false
		}
		
	def toList( l: StructureAST ): List[AST] =
		l match
		{
			case StructureAST( NIL, IndexedSeq() ) => Nil
			case StructureAST( DOT, IndexedSeq(head, tail: StructureAST) ) => head :: toList( tail )
		}
		
	def display( a: AST ): String =
		a match
		{
			case VariableAST( s ) => s.name
			case StructureAST( f, IndexedSeq() ) => f.name
			case s: StructureAST if isList( s ) => toList( s ).map( display(_) ).mkString( "[", ", ", "]" )
			case StructureAST( f, args ) => f.name + (for (a <- args) yield display( a )).mkString( "(", ", ", ")" )
		}
		
	def display( m: Map[String, AST] ): Map[String, String] = m map {case (k, v) => k -> display( v )}
}