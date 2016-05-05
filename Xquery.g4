/**
 * Define a grammar called Xquery
 */
grammar Xquery;

// XPath, entry point
xp  : ap
	| rp
	| f
	;    

// Xquery sub-language for the project, entry point
xq	: Var				#XQVariable
	| String			#XQString
	| ap				#XQAP
	| '(' xq ')'		#XQParanth													
	| xq ',' xq			#XQWithXQ
	| xq '/' rp			#XQRPChildren
	| xq '//' rp		#XQRPBoth
	| '<' Name '>' '{' xq '}' '</' Name '>'	#XQElement
	| forClause (letClause | /*epsilon*/) (whereClause | /*epsilon*/) returnClause	#XQCore
	| letClause xq		#XQLet
	;
		
// absolute path 
ap 	: 'doc(' String ')/' rp 		#APChildren
	| 'doc(' String ')//' rp		#APBoth
	| 'document(' String ')/' rp 	#APChildren
	| 'document(' String ')//' rp 	#APBoth
	;
	
// relative path
rp	: Name			#RPName
	| '*'			#RPAll
	| '..'			#RPParents
	| '.'			#RPCurrent
	| 'text()'		#RPText
	| '@' Name		#RPAttribute
	| '(' rp ')'	#RPParanth
	| rp '/' rp		#RPChildren
	| rp '//' rp	#RPBoth
	| rp '[' f ']'	#RPWithFilter
	| rp ',' rp 	#RPWithRP
	;

//path filter
f	: rp			#Filter
	| rp '=' rp		#FilterEqual
	| rp 'eq' rp	#FilterEqual
	| rp '==' rp	#FilterIs
	| rp 'is' rp	#FilterIs
	| '(' f ')'		#FilterParan
	| f 'and' f		#FilterAnd
	| f 'or' f		#FilterOr
	| 'not' f		#FilterNot
	;	

forClause	: 'for' Var 'in' xq (',' Var 'in' xq)*;

letClause	: 'let' Var ':=' xq (',' Var ':=' xq)*;

whereClause	: 'where' cond;

returnClause: 'return' xq;

cond	: xq '=' xq													#ConditionEqual
		| xq 'eq' xq												#ConditionEqual
		| xq '==' xq												#ConditionIs
		| xq 'is' xq												#ConditionIs
		| 'empty(' xq ')'											#ConditionEmpty
		| 'some' Var 'in' xq (',' Var 'in' xq)* 'satisfies' cond	#ConditionIn
		| '(' cond ')'												#ConditionParanth
		| cond 'and' cond											#ConditionAnd
		| cond 'or' cond											#ConditionOr
		| 'not' cond												#ConditionNot
		;

// Literals must be capitalized

// attName, tagName
Name 		: ( 'a' .. 'z' | 'A' .. 'Z' | '_' )( 'a' .. 'z' | 'A' .. 'Z' | '0' .. '9' | '_' )*;

// Var
Var			: '$' Name;

// fileName, StringConstant
String	: '"' ~('\n'|'\r')* '"';

// Ignore
//Comment 	:  '//' ~('\n')* '\n' -> skip;
  
WS : [ \t\r\n]+ -> skip ; // skip spaces, tabs, newlines

