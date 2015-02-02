package ca.hyperreal.sprolog


object TestMain extends App
{
	val wam = new WAM
// 	val p = Prolog.parseProgram( """
// X = X.
// 
// man(adam).
// man(peter).
// man(rick).
// man(paul).
// 
// woman(marry).
// woman(eve).
// 
// parent(adam,peter). % means adam is parent of peter
// parent(eve,peter).
// parent(rick,paul).
// parent(marry,paul).
// 
// father(F,C) :- man(F), parent(F,C).
// mother(M,C) :- woman(M), parent(M,C).
// 
// """ )
	val p = Prolog.parseProgram( """
member(X,[X|_]).
member(X,[_|T]) :- member(X,T).
""" )
	val pc = Prolog.program( p )

	println( pc )
	wam.program = pc
	
//  	val q = Prolog.parseQuery( "father( A, B ), B = paul." )
 	val q = Prolog.parseQuery( "member( M, [a, b, c] )." )
	val qc = Prolog.query( q )
	
	println( qc )
	
	if (wam execute qc)
		println( "no" )
	else
	{
		if (wam.bindings isEmpty)
			println( "yes" )
		else
			while (wam.alternative)
			{
				println( wam.bindings )
				wam.continue
			}
	}
}