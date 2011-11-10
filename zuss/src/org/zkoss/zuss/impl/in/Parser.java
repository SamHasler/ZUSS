/* Parser.java

	Purpose:
		
	Description:
		
	History:
		Thu Nov  3 11:35:27 TST 2011, Created by tomyeh

Copyright (C) 2011 Potix Corporation. All Rights Reserved.

*/
package org.zkoss.zuss.impl.in;

import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.io.Reader;
import java.io.InputStream;
import java.io.IOException;

import org.zkoss.zuss.Locator;
import org.zkoss.zuss.ZussException;
import org.zkoss.zuss.metainfo.NodeInfo;
import org.zkoss.zuss.metainfo.SheetDefinition;
import org.zkoss.zuss.metainfo.RuleDefinition;
import org.zkoss.zuss.metainfo.StyleDefinition;
import org.zkoss.zuss.metainfo.VariableDefinition;
import org.zkoss.zuss.metainfo.FunctionDefinition;
import org.zkoss.zuss.metainfo.MixinDefinition;
import org.zkoss.zuss.metainfo.ArgumentDefinition;
import org.zkoss.zuss.metainfo.Expression;
import org.zkoss.zuss.metainfo.ConstantValue;
import org.zkoss.zuss.metainfo.VariableValue;
import org.zkoss.zuss.metainfo.FunctionValue;
import org.zkoss.zuss.metainfo.Operator;
import static org.zkoss.zuss.metainfo.Operator.Type.*;
import static org.zkoss.zuss.impl.in.Keyword.Value.*;


/**
 * The ZUSS parser.
 * @author tomyeh
 */
public class Parser {
	private static final char EOF = (char)0;

	private final Tokenizer _in;
	private final Locator _loc;

	public Parser(Reader in, Locator loc) {
		_in = new Tokenizer(in);
		_loc = loc;
	}

	/** Parses the ZUSS style sheet.
	 * <p>Notice that this method can be called only once.
	 */
	public SheetDefinition parse() throws IOException {
		try {
			Context ctx = new Context();
			parse(ctx);
			return ctx.sheet;
		} finally {
			try {
				_in.getInput().close();
			} catch (Throwable t) {
			}
		}
	}
	private void parse(Context ctx) throws IOException {
		for (Token token; (token = next(ctx)) != null;) {
			if (token instanceof Keyword) {
				parseKeyword(ctx, (Keyword)token);
			} else if (token instanceof Id) {
				parseId(ctx, (Id)token);
			} else if (token instanceof Selector) {
				final RuleDefinition rdef = new RuleDefinition(ctx.state.parent, token.getLine());
				rdef.getSelectors().add(((Selector)token).getValue());
				parseSelector(ctx, rdef);
			} else if (ctx.state.bracing && token instanceof Symbol
			&& ((Symbol)token).getValue() == '}') {
				return; //done (closed)
			} else if (token instanceof Other) {
				if (ctx.state.parent instanceof SheetDefinition)
					throw new ZussException("{ expected; not "+token, token.getLine());
				parseStyle(ctx, (Other)token);
			} else {
				throw new ZussException("unknown "+token, getLine(token));
			}
		}
	}

	private void parseKeyword(Context ctx, Keyword kw) throws IOException {
		//TODO
	}

	/** Parse a definition starts with {@link Id}. */
	private void parseId(Context ctx, Id id) throws IOException {
		final boolean old = ctx.state.expressioning;
		ctx.state.expressioning = true;
		parseId0(ctx, id);
		ctx.state.expressioning = old;
	}
	private void parseId0(Context ctx, Id id) throws IOException {
		Token t0 =  next(ctx);
		if (t0 instanceof Symbol) {
			final char symbol = ((Symbol)t0).getValue();
			if (symbol == ':') { //variable definition
				final Expression expr = new Expression(t0.getLine());
					//note: expr is NOT a child of any node but part of VariableDefinition below
				parseExpression(ctx, expr, ';');
				new VariableDefinition(
					ctx.state.parent, id.getValue(), expr, id.getLine());
				return;
			}
		} else if (t0 instanceof Op && ((Op)t0).getValue() == LPAREN) {
			//1) definition of function or mixin
			//2) use of function or mixin
			char cc = _in.peekAfterRPAREN();
			if (cc == ';' || cc == '}' || cc == EOF) { //use of function/mixin
				putback(t0);
				putback(id);
				parseExpression(ctx, new Expression(ctx.state.parent, id.getLine()), EOF);

				t0 = next(ctx);
				if (t0 instanceof Symbol) {
					switch (((Symbol)t0).getValue()) {
					case '}':
						putback(t0);
						//fall thru
					case ';':
						return; //done
					}
				}
				throw new ZussException("expected ';', not "+t0, getLine(t0));
			}

			//definition of function/mixin
			final ArgumentDefinition[] adefs = parseArguments(ctx);
			t0 = next(ctx);
			if (t0 instanceof Symbol) {
				final char symbol = ((Symbol)t0).getValue();
				if (symbol == ':') { //function definition
					Token t1 = next(ctx);
					if (t1 instanceof Keyword && ((Keyword)t1).getValue() == IMPORT) {
						t0 = next(ctx);
						if (!(t0 instanceof Other))
							throw new ZussException("a class name expected, not "+t0, getLine(t0));
						t1 = next(ctx);
						if (!(t1 instanceof Symbol) || ((Symbol)t1).getValue() != ';')
							throw new ZussException("';' expected", getLine(t1));

						final Method mtd = Classes.getMethod(
							((Other)t0).getValue(), id.getValue(), adefs.length, t0.getLine());
						new FunctionDefinition(
							ctx.state.parent, id.getValue(), adefs, mtd, id.getLine());
						return;
					} else {
						putback(t1);
						final Expression expr = new Expression(t0.getLine());
							//note: expr is NOT a child of any node but part of VariableDefinition below
						parseExpression(ctx, expr, ';');
						new FunctionDefinition(
							ctx.state.parent, id.getValue(), adefs, expr, id.getLine());
						return;
					}
				} else if (symbol == '{') { //mixin
					final MixinDefinition mdef = new MixinDefinition(
						ctx.state.parent, id.getValue(), adefs, id.getLine());
					ctx.push(new State(mdef, true));
					parse(ctx);
					ctx.pop();
					return;
				}
			}
		}
		throw new ZussException("unexpected "+t0, getLine(t0));
	}

	/** Parse a definition starts with selector. */
	private void parseSelector(Context ctx, RuleDefinition rdef) throws IOException {
		Token t0 = next(ctx);
		char symbol;
		if (!(t0 instanceof Symbol)
		|| ((symbol = ((Symbol)t0).getValue()) != ',' && symbol != '{'))
			throw new ZussException(", or { expected after a selector", getLine(t0));

		if (symbol == ',') {
			Token t1 = next(ctx);
			if (!(t1 instanceof Selector))
				throw new ZussException("a selector expected after ','", getLine(t1));
			rdef.getSelectors().add(((Selector)t1).getValue());
			parseSelector(ctx, rdef);
		} else { //{
			ctx.push(new State(rdef, true));
			parse(ctx);
			ctx.pop();
		}
	}

	private void parseStyle(Context ctx, Other name) throws IOException {
		Token t0 = next(ctx);
		if (!(t0 instanceof Symbol) || ((Symbol)t0).getValue() != ':')
			throw new ZussException(": expected", (t0 != null ? t0: name).getLine());

		StyleDefinition sdef = new StyleDefinition(ctx.state.parent, name.getValue(), name.getLine());
		for (Token token; (token = next(ctx)) != null;) {
			if (token instanceof Other) {
				new ConstantValue(sdef, ((Other)token).getValue(), token.getLine());
			} else if (token instanceof Symbol) {
				final char symbol = ((Symbol)token).getValue();
				if (symbol == ';')
					break; //done
				if (symbol == '}') {
					putback(token);
					break; //done
				}
				throw new ZussException("unexpected '" + symbol + '\'', token.getLine());
			} else if (token instanceof Id) {
				//handle @xx or @xxx()
				if (_in.peek() == '(') { //a function invocation
					putback(token);
					parseExpression(ctx, new Expression(sdef, token.getLine()), EOF);
						//note: the expression is a child of sdef
				} else {
					new VariableValue(sdef, ((Id)token).getValue(), token.getLine());
				}
			}
		}
	}

	private ArgumentDefinition[] parseArguments(Context ctx)
	throws IOException {
		Token token = next(ctx);
		if (token instanceof Op && ((Op)token).getValue() == RPAREN)
			return new ArgumentDefinition[0];
		putback(token);

		final List<ArgumentDefinition> args = new ArrayList<ArgumentDefinition>();
		for (; (token = next(ctx)) != null;) {
			if (!(token instanceof Id))
				throw new ZussException("Argument must be defined with a variable (@xxx)", getLine(token));

			final String name = ((Id)token).getValue();
			String defValue = null;
			Token t0 = next(ctx);
			if (t0 instanceof Symbol) {
				if (((Symbol)t0).getValue() == ':') {
					t0 = next(ctx);
					if (!(t0 instanceof Other))
						throw new ZussException("unexpected "+t0, getLine(t0));
					defValue = ((Other)t0).getValue();
					t0 = next(ctx);
				}
			}
			if (t0 instanceof Symbol) {
				if (((Symbol)t0).getValue() == ',') {
					args.add(new ArgumentDefinition(name, defValue, t0.getLine()));
					continue;
				}
			} else if (t0 instanceof Op) {
				if (((Op)t0).getValue() == RPAREN) {
					args.add(new ArgumentDefinition(name, defValue, t0.getLine()));
					return args.toArray(new ArgumentDefinition[args.size()]); //done
				}
			}
			throw new ZussException("unexpected "+t0, getLine(t0));
		}
		throw new ZussException("')' expected", getLine(token));
	}

	private int getLine(Token token) {
		return token != null ? token.getLine(): _in.getLine();
	}

	/**
	 * @param endcc the character to denote the end of the expression.
	 * If EOF, it means it is parsing @f(...) and it ends with the last ')'.
	 */
	private void parseExpression(Context ctx, Expression expr, final char endcc)
	throws IOException {
		final boolean old = ctx.state.expressioning;
		ctx.state.expressioning = true;
		parseExpression0(ctx, expr, endcc);
		ctx.state.expressioning = old;
	}
	private void parseExpression0(Context ctx, Expression expr, final char endcc)
	throws IOException {
		final List<Op> ops = new ArrayList<Op>();
		boolean opExpected = false;
		for (Token token; (token = next(ctx)) != null;) {
			if (token instanceof Symbol) {
				char cc = ((Symbol)token).getValue();
				if (cc == endcc)
					break; //done
				if (endcc == EOF && (cc == ';' || cc == '}')) {
					putback(token);
					break;
				}
				if (cc != ',')
					throw new ZussException("unexpected "+token, token.getLine());
				if (!opExpected)
					throw new ZussException("unexpected ','", token.getLine());

				while (!ops.isEmpty()) {
					final Op xop = ops.get(0);
					final Operator.Type xtype = xop.getValue();
					if (xtype == FUNC || xtype == COMMA)
						break;
					if (xtype == LPAREN)
						throw new ZussException("')' expected", xop.getLine());
					ops.remove(0);
					new Operator(expr, xop.getValue(), xop.getLine());
				}
				ops.add(0, new Op(COMMA, token.getLine()));
				opExpected = false;
			} else if (token instanceof Op) {
				final Op op = (Op)token;
				if (!opExpected) {
					switch (op.getValue()) {
					case LPAREN:
						ops.add(0, op);
						continue; //next
					case SUBTRACT:
						op.setValue(NEGATE);
						break;
					case ADD:
						continue; //ignore
					default:
						throw new ZussException("an operand expected, not "+op, op.getLine());
					}
				} else if (op.getValue() == RPAREN) {
					int argc = 1; //zero argument has been processed when Id is found
					l_pop:
					while (!ops.isEmpty()) {
						final Op xop = ops.remove(0);
						switch (xop.getValue()) {
						case FUNC:
							new FunctionValue(expr, xop.getData(), argc, xop.getLine());
							//fall thru
						case LPAREN:
							break l_pop; //done
						case COMMA:
							++argc;
							break;
						default:
							new Operator(expr, xop.getValue(), xop.getLine());
						}
					}
					if (endcc == EOF && ops.isEmpty())
						break; //done
					continue; //next token
				} else if (op.getValue() == LPAREN)
					throw new ZussException("unexpected '('", op.getLine());

				//push an operator
				while (!ops.isEmpty()) {
					final Op xop = ops.get(0);
					final Operator.Type xtype = xop.getValue();
					if (xtype.getPrecedence() > op.getValue().getPrecedence())
						break;
					//move ops[0] to expression since the precedence is GE
					ops.remove(0);
					new Operator(expr, xop.getValue(), xop.getLine());
				}
				ops.add(0, op);
				opExpected = false;
			} else {
				if (opExpected)
					throw new ZussException("an operator expected, not "+token, token.getLine());

				if (token instanceof Id) {
					final String nm = ((Id)token).getValue();
					Token t = next(ctx);
					if (!(t instanceof Op) || ((Op)t).getValue() != LPAREN) {
						putback(t);
						new VariableValue(expr, nm, token.getLine());
					} else { //function invocation
						t = next(ctx);
						if (t instanceof Op && ((Op)t).getValue() == RPAREN) {
							//handle no arg invocation special, since it is not easy
							//to tell the difference between f(a) vs. f()
							new FunctionValue(expr, nm, 0, token.getLine());
						} else {
							putback(t);
							ops.add(0, new Op(FUNC, nm, token.getLine())); //pass name as op's data
							continue; //opExpected still false
						}
					}
				} else if (token instanceof Other)
					new ConstantValue(expr, ((Other)token).getValue(), token.getLine());
				else
					throw new ZussException("unexpected "+token, token.getLine());
				opExpected = true;
			}
		}

		while (!ops.isEmpty()) {
			final Op xop = ops.remove(0);
			final Operator.Type xtype = xop.getValue();
			if (xtype == COMMA)
				throw new ZussException("unexpected ','", xop.getLine());
			if (xtype == LPAREN || xtype == FUNC)
				throw new ZussException("')' expected", xop.getLine());
			new Operator(expr, xtype, xop.getLine());
		}

		if (expr.getChildren().isEmpty())
			throw new ZussException("expression expected", expr.getLine());
	}

	private void putback(Token token) {
		_in.putback(token);
	}
	private Token next(Context ctx) throws IOException {
		return _in.next(ctx.state.expressioning);
	}

	private class Context {
		private final SheetDefinition sheet = new SheetDefinition();
		private final List<State> _states = new ArrayList<State>();
		private State state = new State(sheet, false);

		private void push(State state) {
			_states.add(0, this.state);
			this.state = state;
		}
		private void pop() {
			this.state = _states.remove(0);
		}
	}
	private class State {
		/** The parent node. */
		private final NodeInfo parent;
		/** whether { is encountered, but not }. That is, } is expected. */
		private final boolean bracing;
		/** whether it is parsing an expression, i.e., operations are recognized. */
		private boolean expressioning;

		private State(NodeInfo parent, boolean bracing) {
			this.parent = parent;
			this.bracing = bracing;
		}
	}
}
