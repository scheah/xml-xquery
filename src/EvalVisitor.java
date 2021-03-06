import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

public class EvalVisitor extends XqueryBaseVisitor<IXqueryValue>{
	private Document document;
	private Stack<XqueryNodes> rpContext;
	private Stack<HashMap<String, XqueryNodes>> scopeContext;
	private Set<String> visitedVariables;
	
	public EvalVisitor() {
		super(); // may be unnecessary
		DocumentBuilderFactory factory = null;
		DocumentBuilder builder = null; 
		try {
			factory = DocumentBuilderFactory.newInstance();
			builder = factory.newDocumentBuilder();
		} 
		catch (ParserConfigurationException e) {
			e.printStackTrace();
		}
		document = builder.newDocument();
		rpContext = new Stack<XqueryNodes>();
		scopeContext = new Stack<HashMap<String, XqueryNodes>>();
		scopeContext.push(new HashMap<String, XqueryNodes>());	
		visitedVariables = new HashSet<String>();
	}
	
	/*
	 * XQ rules
	 */
	
	/*
	 * Var
	 * #XQVar
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQVar(XqueryParser.XQVarContext)
	 */
	@Override public XqueryNodes visitXQVar(XqueryParser.XQVarContext ctx) 
	{ 
		XqueryNodes ret = (XqueryNodes) scopeContext.peek().get(ctx.Var().getText()); 
		if (ret == null)// can return null if not found
			ret = new XqueryNodes(); // return empty
		visitedVariables.add(ctx.Var().getText());
		return ret;
	}

	/*
	 * String
	 * #XQString
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQString(XqueryParser.XQStringContext)
	 */
	@Override public XqueryNodes visitXQString(XqueryParser.XQStringContext ctx) 
	{ 
		String string = ctx.String().getText();
		string = string.substring(1, string.length()-1); // strip leading and trailing \"
		Node textNode = document.createTextNode(string);
		return new XqueryNodes(textNode);
	}

	/*
	 * ap																			
	 * #XQAp	
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQAp(XqueryParser.XQApContext)
	 */
	@Override public XqueryNodes visitXQAp(XqueryParser.XQApContext ctx) 
	{ 
		return (XqueryNodes) visit(ctx.ap()); 
	}

	/*
	 * xq '/' rp
	 * #XQChildren	
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQChildren(XqueryParser.XQChildrenContext)
	 */
	@Override public XqueryNodes visitXQChildren(XqueryParser.XQChildrenContext ctx) 
	{ 
		XqueryNodes x = (XqueryNodes) visit(ctx.xq());
		rpContext.push(x.getChildren());
		XqueryNodes y = (XqueryNodes) visit(ctx.rp());
		rpContext.pop();
		return y.uniqueById();
	}

	/*
	 * xq '//' rp
	 * #XQBoth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQBoth(XqueryParser.XQBothContext)
	 */
	@Override public XqueryNodes visitXQBoth(XqueryParser.XQBothContext ctx) 
	{ 
		XqueryNodes x = (XqueryNodes) visit(ctx.xq());
		rpContext.push(x.getDescendants());
		XqueryNodes y = (XqueryNodes) visit(ctx.rp());
		rpContext.pop();
		return y.uniqueById(); 
	}
	
	/*
	 * '(' xq ')'
	 * #XQParanth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQParanth(XqueryParser.XQParanthContext)
	 */
	@Override public XqueryNodes visitXQParanth(XqueryParser.XQParanthContext ctx) 
	{ 
		return (XqueryNodes) visit(ctx.xq()); 
	}

	/*
	 * xq ',' xq
	 * #XQWithXQ
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQWithXQ(XqueryParser.XQWithXQContext)
	 */
	@Override public XqueryNodes visitXQWithXQ(XqueryParser.XQWithXQContext ctx) 
	{ 
		XqueryNodes left = (XqueryNodes) visit(ctx.xq(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.xq(1));
		return left.concat(right);
	}

	/*
	 * letClause xq
	 * #XQLet
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQLet(XqueryParser.XQLetContext)
	 */
	@Override public XqueryNodes visitXQLet(XqueryParser.XQLetContext ctx) { 
		visit(ctx.letClause());
		return (XqueryNodes) visit(ctx.xq());
	}

	/*
	 * For use with #XQFor rule
	 * Evaluate where clause using every combination of ind. elements in variables from the context. 
	 * In base case, evaluate where and return clauses using recursively generated combinations
	 */
	public void flwr(XqueryParser.XQForContext ctx, int keyIndex, Set<String> whereVars, XqueryNodes returnVal) {
		// base case
		int numVariables = ctx.forClause().Var().size();
		if (ctx.letClause() != null)
			numVariables = numVariables + ctx.letClause().Var().size();
		if (keyIndex >= numVariables) {
			XqueryBoolean condition = new XqueryBoolean(true); // Assume true unless there is a whereClause
			if (ctx.whereClause() != null)
				condition = (XqueryBoolean) visit(ctx.whereClause());
			if (condition.getValue() == true) {
				XqueryNodes ret = (XqueryNodes) visit(ctx.returnClause());
				for (int i = 0; i < ret.size(); i++) 
					returnVal.add(ret.get(i)); // add to final return
			}
		}
		else {
			HashMap<String, XqueryNodes> currentContext = scopeContext.peek();
			String currentKey = null;
			XqueryNodes currentNodes = null;
			if (keyIndex < ctx.forClause().Var().size()) { // handle vars in forClause
				currentKey = ctx.forClause().Var(keyIndex).getText();
				currentNodes = (XqueryNodes) visit(ctx.forClause().xq(keyIndex));
			}
			else { // handle vars in letClause, should not be able to enter if there is no letClause
				int offsetKeyIndex = keyIndex - ctx.forClause().Var().size();
				currentKey = ctx.letClause().Var(offsetKeyIndex).getText();
				currentNodes = (XqueryNodes) visit(ctx.letClause().xq(offsetKeyIndex));
			}
			// Put into context list of nodes (special case of let) or individual nodes
			if (keyIndex >= ctx.forClause().Var().size() && !whereVars.contains(currentKey)) { // Let vars not in where clause
				currentContext.put(currentKey, currentNodes); // binding should be with full list of nodes
				flwr(ctx, keyIndex + 1, whereVars, returnVal); // recursive call
			}
			else { // let vars in where clause & for vars
				for (int i = 0; i < currentNodes.size(); i++) {
					Node singleNode = currentNodes.get(i);
					XqueryNodes xn = new XqueryNodes(singleNode);
					currentContext.put(currentKey, xn);
					flwr(ctx, keyIndex + 1, whereVars, returnVal); // recursive call
				}
			}
		}
	}
		
	/*
	 * forClause (letClause | epsilon) (whereClause | epsilon) returnClause	
	 * #XQFor
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQFor(XqueryParser.XQForContext)
	 */
	@Override public XqueryNodes visitXQFor(XqueryParser.XQForContext ctx) 
	{ 
		HashMap<String, XqueryNodes> copy = new HashMap<String, XqueryNodes>(scopeContext.peek());
		scopeContext.push(copy);
		visitedVariables.clear();
		if (ctx.whereClause() != null)
			visit(ctx.whereClause()); // populate visitedVariables
		Set<String> whereVars = new HashSet<String>(visitedVariables);
		XqueryNodes result = new XqueryNodes();
		flwr(ctx, 0, whereVars, result);
		scopeContext.pop();
		return result;
	}

	/*
	 * '<' Name '>' '{' xq '}' '</' Name '>'
	 * #XQTag
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitXQTag(XqueryParser.XQTagContext)
	 */
	@Override public XqueryNodes visitXQTag(XqueryParser.XQTagContext ctx) 
	{ 
		String tagName = ctx.Name(0).getText();
		Node outer = document.createElement(tagName);
		XqueryNodes inner = (XqueryNodes) visit(ctx.xq());
		for (int i = 0; i < inner.size(); i++) {
			Node innerNode = document.importNode(inner.get(i), true);
			outer.appendChild(innerNode);
		}
		return new XqueryNodes(outer);
	}

	/*
	 * forClause rules
	 */
	
	/*
	 * 'for' Var 'in' xq (',' Var 'in' xq)*;
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitForClause(XqueryParser.ForClauseContext)
	 */
	@Override public XqueryBoolean visitForClause(XqueryParser.ForClauseContext ctx) 
	{ 
		for (int i = 0; i < ctx.Var().size(); i++) {
			String var = ctx.Var(i).getText();
			XqueryNodes val = (XqueryNodes) visit(ctx.xq(i));
			scopeContext.peek().put(var, val);
		}
		return new XqueryBoolean(true); // unused return value
	}

	/*
	 * letClause rules
	 */
	
	/*
	 * 'let' Var ':=' xq (',' Var ':=' xq)*;
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitLetClause(XqueryParser.LetClauseContext)
	 */
	@Override public XqueryBoolean visitLetClause(XqueryParser.LetClauseContext ctx) 
	{ 
		// modify current context scope
		for (int i = 0; i < ctx.Var().size(); i++) {
			String var = ctx.Var(i).getText();
			XqueryNodes val = (XqueryNodes) visit(ctx.xq(i));
			scopeContext.peek().put(var, val);
		}
		return new XqueryBoolean(true); // unused return value
	}

	/*
	 * whereClause rules
	 */
	
	/*
	 * 'where' cond;
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitWhereClause(XqueryParser.WhereClauseContext)
	 */
	@Override public XqueryBoolean visitWhereClause(XqueryParser.WhereClauseContext ctx) 
	{ 
		return (XqueryBoolean) visit(ctx.cond());
	}

	/*
	 * returnClause rules
	 */
	
	/*
	 * 'return' xq;
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitReturnClause(XqueryParser.ReturnClauseContext)
	 */
	@Override public XqueryNodes visitReturnClause(XqueryParser.ReturnClauseContext ctx) { 
		return (XqueryNodes) visit(ctx.xq()); 
	}

	/*
	 * f rules
	 */
	
	/*
	 * f 'and' f
	 * #FilterAnd
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterAnd(XqueryParser.FilterAndContext)
	 */
	@Override 
	public XqueryBoolean visitFilterAnd(XqueryParser.FilterAndContext ctx)
	{
		XqueryBoolean left = (XqueryBoolean) visit(ctx.f(0));
		XqueryBoolean right = (XqueryBoolean) visit(ctx.f(1));
		return left.and(right);
	}
	
	/*
	 * f 'or' f
	 * #FilterOr
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterOr(XqueryParser.FilterOrContext)
	 */
	@Override
	public XqueryBoolean visitFilterOr(XqueryParser.FilterOrContext ctx)
	{
		XqueryBoolean left = (XqueryBoolean) visit(ctx.f(0));
		XqueryBoolean right = (XqueryBoolean) visit(ctx.f(1));
		return left.or(right);
	}
	
	/*
	 * rp '==' rp
	 * rp 'is' rp
	 * #FilterIs
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterIs(XqueryParser.FilterIsContext)
	 */
	@Override 
	public XqueryBoolean visitFilterIs(XqueryParser.FilterIsContext ctx)
	{
		XqueryNodes left = (XqueryNodes) visit(ctx.rp(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.rp(1));
		return new XqueryBoolean(left.isEqualId(right));
	}
	
	/*
	 * rp '=' rp
	 * rp 'eq' rp
	 * #FilterEqual
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterEqual(XqueryParser.FilterEqualContext)
	 */
	@Override 
	public XqueryBoolean visitFilterEqual(XqueryParser.FilterEqualContext ctx)
	{
		XqueryNodes left = (XqueryNodes) visit(ctx.rp(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.rp(1));
		return new XqueryBoolean(left.isEqualValue(right));
	}
	
	/*
	 * 'not' f
	 * #FilterNot
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterNot(XqueryParser.FilterNotContext)
	 */
	@Override 
	public XqueryBoolean visitFilterNot(XqueryParser.FilterNotContext ctx)
	{
		XqueryBoolean op = (XqueryBoolean) visit(ctx.f());
		return op.not();
	}
	
	/*
	 * '(' f ')'
	 * #FilterParan
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilterParan(XqueryParser.FilterParanContext)
	 */
	@Override 
	public XqueryBoolean visitFilterParan(XqueryParser.FilterParanContext ctx)
	{
		return (XqueryBoolean) visit(ctx.f());
	}
	
	/*
	 * rp
	 * #Filter
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitFilter(XqueryParser.FilterContext)
	 */
	@Override 
	public XqueryBoolean visitFilter(XqueryParser.FilterContext ctx)
	{
		XqueryNodes x = (XqueryNodes) visit(ctx.rp());
		if (x.size() > 0)
			return new XqueryBoolean(true);
		return new XqueryBoolean(false);
	}
	
	/*
	 * cond Rules
	 */
	
	/*
	 * xq '=' xq
	 * xq 'eq' xq
	 * #ConditionEqual
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionEqual(XqueryParser.ConditionEqualContext)
	 */
	@Override 
	public XqueryBoolean visitConditionEqual(XqueryParser.ConditionEqualContext ctx)
	{
		XqueryNodes left = (XqueryNodes) visit(ctx.xq(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.xq(1));
		return new XqueryBoolean(left.isEqualValue(right));
	}
	
	/*
	 * xq '==' xq
	 * xq 'is' xq
	 * #ConditionIs
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionIs(XqueryParser.ConditionIsContext)
	 */
	@Override 
	public XqueryBoolean visitConditionIs(XqueryParser.ConditionIsContext ctx)
	{
		XqueryNodes left = (XqueryNodes) visit(ctx.xq(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.xq(1));
		return new XqueryBoolean(left.isEqualId(right));
	}
	
	/*
	 * '(' cond ')'
	 * #ConditionParanth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionParanth(XqueryParser.ConditionParanthContext)
	 */
	@Override
	public XqueryBoolean visitConditionParanth(XqueryParser.ConditionParanthContext ctx)
	{
		return (XqueryBoolean) visit(ctx.cond());
	}
	
	/*
	 * cond 'and' cond
	 * #ConditionAnd
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionAnd(XqueryParser.ConditionAndContext)
	 */
	@Override public XqueryBoolean visitConditionAnd(XqueryParser.ConditionAndContext ctx)
	{
		XqueryBoolean left = (XqueryBoolean) visit(ctx.cond(0));
		XqueryBoolean right = (XqueryBoolean) visit(ctx.cond(1));
		return left.and(right);
	}
	
	/*
	 * cond 'or' cond
	 * #ConditionOr
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionOr(XqueryParser.ConditionOrContext)
	 */
	@Override public XqueryBoolean visitConditionOr(XqueryParser.ConditionOrContext ctx)
	{
		XqueryBoolean left = (XqueryBoolean) visit(ctx.cond(0));
		XqueryBoolean right = (XqueryBoolean) visit(ctx.cond(1));
		return left.or(right);
	}
	
	/*
	 * For use with #ConditionIn rule
	 * Evaluate where clause using every combination of ind. elements in variables from the context. 
	 * In base case, evaluate the condition using recursively generated combinations
	 */
	public XqueryBoolean someSatisfies(XqueryParser.ConditionInContext ctx, int keyIndex) {
		// base case
		int numVariables = ctx.Var().size();
		if (keyIndex >= numVariables) {
			XqueryBoolean condition = (XqueryBoolean) visit(ctx.cond());
			return condition;
		}
		else {
			HashMap<String, XqueryNodes> currentContext = scopeContext.peek();
			String currentKey = null;
			XqueryNodes currentNodes = null;
			if (keyIndex < ctx.Var().size()) { // handle vars in forClause
				currentKey = ctx.Var(keyIndex).getText();
				currentNodes = (XqueryNodes) visit(ctx.xq(keyIndex));
			}
			for (int i = 0; i < currentNodes.size(); i++) {
				Node singleNode = currentNodes.get(i);
				XqueryNodes xn = new XqueryNodes(singleNode);
				currentContext.put(currentKey, xn);
				XqueryBoolean result = someSatisfies(ctx, keyIndex + 1); // recursive call
				if (result.getValue() == true)
					return new XqueryBoolean(true);
			}
			return new XqueryBoolean(false);
		}
	}
	
	/*
	 * 'some' Var 'in' xq (',' Var 'in' xq)* 'satisfies' cond
	 * #ConditionIn
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitConditionIn(XqueryParser.ConditionInContext)
	 */
	@Override public XqueryBoolean visitConditionIn(XqueryParser.ConditionInContext ctx) 
	{ 
		HashMap<String, XqueryNodes> copy = new HashMap<String, XqueryNodes>(scopeContext.peek());
		scopeContext.push(copy);
		XqueryBoolean result = someSatisfies(ctx, 0);
		scopeContext.pop();
		return result;
	}
	
	
	/*
	 * ap rules
	 */
	
	/*
	 * For use with ap rules
	 * Read in xml file and return the root node.
	 */
	public ArrayList<Node> Doc(String name)
	{
		//open a file and change the current.
		File inputFile = new File(name);
        DocumentBuilderFactory dbFactory= DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = null;
		try {
			dBuilder = dbFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        //dBuilder.setEntityResolver(resolver);
        Document doc = null;
		try {
			doc = dBuilder.parse(inputFile);
		} catch (SAXException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
//        doc.getDocumentElement().normalize();
		ArrayList<Node> result = new ArrayList<Node>();
		result.add((Node)doc);
		return result;
	}
	
	/*
	 * 'doc(' String ')/' rp
	 * 'document(' String ')/' rp
	 * #APChildren
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitAPChildren(XqueryParser.APChildrenContext)
	 */
	@Override public XqueryNodes visitAPChildren(XqueryParser.APChildrenContext ctx)
	{
		//visit doc
		String filename = ctx.String().getText();
		filename = filename.substring(1, filename.length()-1); // strip leading and trailing \"
		XqueryNodes root = new XqueryNodes(Doc(filename));
		rpContext.push(root.getChildren());
		XqueryNodes returnVal = (XqueryNodes) visit(ctx.rp());
		rpContext.pop();
		return returnVal;
	}
	
	/*
	 * 'doc(' String ')//' rp
	 * 'document(' String ')//' rp
	 * #APBoth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitAPBoth(XqueryParser.APBothContext)
	 */
	@Override public XqueryNodes visitAPBoth(XqueryParser.APBothContext ctx)
	{
		String filename = ctx.String().getText();
		filename = filename.substring(1, filename.length()-1); // strip leading and trailing \"
		XqueryNodes root = new XqueryNodes(Doc(filename));
		rpContext.push(root.getDescendants());
		XqueryNodes returnVal = (XqueryNodes) visit(ctx.rp());
		rpContext.pop();
		return returnVal;
	}
	
	/*
	 * rp rules
	 */
	
	/*
	 * Name
	 * #RPName
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPName(XqueryParser.RPNameContext)
	 */
	@Override public XqueryNodes visitRPName(XqueryParser.RPNameContext ctx) 
	{ 
		String tagName = ctx.getText();
		XqueryNodes cur = rpContext.peek();
		XqueryNodes returnVal = cur.getNodes(tagName);
		return returnVal;
	}
	
	/*
	 * '(' rp ')'
	 * #RPParanth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPParanth(XqueryParser.RPParanthContext)
	 */
	@Override public XqueryNodes visitRPParanth(XqueryParser.RPParanthContext ctx) 
	{
		return (XqueryNodes) visit(ctx.rp());
	}
	
	/*
	 * rp '/' rp
	 * #RPChildren
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPChildren(XqueryParser.RPChildrenContext)
	 */
	@Override public XqueryNodes visitRPChildren(XqueryParser.RPChildrenContext ctx) 
	{
		XqueryNodes x = (XqueryNodes) visit(ctx.rp(0));
		rpContext.push(x.getChildren());
		XqueryNodes y = (XqueryNodes) visit(ctx.rp(1));
		rpContext.pop();
		return y.uniqueById();
	}

	/*
	 * rp '//' rp
	 * #RPBoth
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPBoth(XqueryParser.RPBothContext)
	 */
	@Override public XqueryNodes visitRPBoth(XqueryParser.RPBothContext ctx)
	{
		XqueryNodes x = (XqueryNodes) visit(ctx.rp(0));
		rpContext.push(x.getDescendants());
		XqueryNodes y = (XqueryNodes) visit(ctx.rp(1));
		rpContext.pop();
		return y.uniqueById();
	}
	
	/*
	 * rp '[' f ']'
	 * #RPWithFilter
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPWithFilter(XqueryParser.RPWithFilterContext)
	 */
	@Override public XqueryNodes visitRPWithFilter(XqueryParser.RPWithFilterContext ctx) 
	{ 
		XqueryNodes returnVal = new XqueryNodes();
		XqueryNodes x = (XqueryNodes) visit(ctx.rp());
		// Process each node separately, evaluating each one with the filter 
		for (int i = 0; i < x.size(); i++) {
			Node singleNode = x.get(i);
			XqueryNodes xn = new XqueryNodes(singleNode);
			rpContext.push(xn.getChildren());
			XqueryBoolean filter = (XqueryBoolean) visit(ctx.f());
			if(filter.getValue() == true)
				returnVal.add(singleNode);
			rpContext.pop();
		}
		return returnVal;
	}
	
	/*
	 * 'text()'
	 * #RPText
	 * (non-Javadoc)
	 * @see XqueryBaseVisitor#visitRPText(XqueryParser.RPTextContext)
	 */
	@Override public XqueryNodes visitRPText(XqueryParser.RPTextContext ctx)
	{
		XqueryNodes cur = rpContext.peek();
		XqueryNodes returnVal = cur.getTextNodes();
		return returnVal;
	}
	
	/*
	 * '*'
	 * #RPAll
	 * Jialong
	 */
	@Override public XqueryNodes visitRPAll(XqueryParser.RPAllContext ctx) 
	{
		XqueryNodes current=rpContext.peek();
		return current;
	}
	/*
	 * rp ',' rp
	 * #RPWithRP
	 * Jialong
	 */
	@Override public XqueryNodes visitRPWithRP(XqueryParser.RPWithRPContext ctx)
	{
		XqueryNodes left = (XqueryNodes) visit(ctx.rp(0));
		XqueryNodes right = (XqueryNodes) visit(ctx.rp(1));
		return left.concat(right);
	}
	/*
	 * '..'
	 * #RPParents
	 * Jialong
	 */
	@Override public XqueryNodes visitRPParents(XqueryParser.RPParentsContext ctx)
	{
		XqueryNodes current=rpContext.peek();
		XqueryNodes parents=current.getParents().getParents();
		return parents;
	}
	
	/*
	 * '.'
	 * #RPCurrent
	 * Jialong
	 */
	@Override public XqueryNodes visitRPCurrent(XqueryParser.RPCurrentContext ctx)
	{
		XqueryNodes current=rpContext.peek().getParents();
		return current;
	}
	
	/*
	 * 'empty(' xq ')'
	 * #ConditionEmpty
	 * Jialong
	 */
	@Override public XqueryBoolean visitConditionEmpty(XqueryParser.ConditionEmptyContext ctx)
	{
		XqueryNodes tobechecked= (XqueryNodes) visit(ctx.xq());
		if(tobechecked.size()==0)
			return new XqueryBoolean(true);
		return new XqueryBoolean(false);
	}
	
	/*
	 * 'not' cond
	 * #conditionNot
	 * jialong
	 * 
	 */
	@Override public XqueryBoolean visitConditionNot(XqueryParser.ConditionNotContext ctx)
	{
		XqueryBoolean condition=(XqueryBoolean) visit(ctx.cond());
		return condition.not();
	}
	/*
	 * 
	 * #'@' Name
	 * #RPAttribute
	 * Jialong
	 * 
	 */
	//add @? or not?
	@Override public XqueryNodes visitRPAttribute(XqueryParser.RPAttributeContext ctx)
	{
		XqueryNodes current=rpContext.peek();
		return current.getAttributeNodes(ctx.Name().getText());
	}
	/*
	 * # 'join' '(' xq ',' xq ',' NameList ',' NameList ')'
	 * #XQJoin
	 * Jialong
	 *
	 */
	@Override public XqueryNodes visitXQJoin(XqueryParser.XQJoinContext ctx) 
	{
		// For left set of tuples, for each one, go through each attribute value from namelist, create specific hashkey. 
		// For other set of tuples: for each one, create hashkey and match with hashtable. 
		// Combine set of matched tuples with current tuple and add to result, repeat
		
		XqueryNodes result = new XqueryNodes();
		XqueryNodes leftTuples = (XqueryNodes) visit(ctx.xq(0));
		XqueryParser.NameListContext leftAttrs = ctx.nameList(0); 
		XqueryNodes rightTuples = (XqueryNodes) visit(ctx.xq(1));
		XqueryParser.NameListContext rightAttrs = ctx.nameList(1);
		
		// hash on attribute value
		HashMap<String, XqueryNodes> hashtable = new HashMap<String, XqueryNodes>();
		for (int i = 0; i < leftTuples.size(); i++) {
			XqueryNodes tuple = new XqueryNodes(leftTuples.get(i));
			// hashkey with current tuple
			String hashkey = "";
			for (int j = 0; j < leftAttrs.Name().size(); j++) {
				String leftAttrName = leftAttrs.Name(j).getText();
				XqueryNodes attrNode = tuple.getChildren(leftAttrName);
				String attrValue;
				if (attrNode.size() == 0) {
					System.err.println("Problem with join -- left tuple attribute: " + leftAttrName);
					attrValue = "";
				}
				else {
					attrValue = XqueryNodes.getNodeString(attrNode.get(0));
					// trim the differing parent tag
					attrValue = attrValue.substring(2+leftAttrName.length(), attrValue.length()-3-leftAttrName.length());
				}
				hashkey = hashkey + j + ":" + attrValue + "\n";
			}
			// Update hashtable
			if (hashtable.containsKey(hashkey)) {
				hashtable.get(hashkey).add(tuple.get(0));
			}
			else {
				hashtable.put(hashkey, new XqueryNodes());
				hashtable.get(hashkey).add(tuple.get(0));
			}
		}
		
		for (int i = 0; i < rightTuples.size(); i++) {
			XqueryNodes tuple = new XqueryNodes(rightTuples.get(i));
			// hashkey with current tuple
			String hashkey = "";
			for (int j = 0; j < rightAttrs.Name().size(); j++) {
				String rightAttrName = rightAttrs.Name(j).getText();
				XqueryNodes attrNode = tuple.getChildren(rightAttrName);
				String attrValue;
				if (attrNode.size() == 0) {
					System.err.println("Problem with join -- right tuple attribute: " + rightAttrName);
					attrValue = "";
				}
				else {
					attrValue = XqueryNodes.getNodeString(attrNode.get(0));
					// trim the differing parent tag
					attrValue = attrValue.substring(2+rightAttrName.length(), attrValue.length()-3-rightAttrName.length());
				}
				hashkey = hashkey + j + ":" + attrValue + "\n";
			}
			// if there is a key match, join current tuple with tuples in hashtable with key
			if (hashtable.containsKey(hashkey)) {
				XqueryNodes build = hashtable.get(hashkey);
				XqueryNodes probeChildren = tuple.getChildren();
				for (int k = 0; k < build.size(); k++) {
					XqueryNodes buildTuple = new XqueryNodes(build.get(k));
					XqueryNodes buildChildren = buildTuple.getChildren();
					XqueryNodes combined = buildChildren.concat(probeChildren);
					Node outer = document.createElement("tuple");
					for (int l = 0; l < combined.size(); l++) {
						Node innerNode = combined.get(l).cloneNode(true);
						outer.appendChild(innerNode);
					}
					result.add(outer);
				}
			}
		}
		return result;
	}
}
