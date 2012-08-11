/* 
 * Kodkod -- Copyright (c) 2005-2007, Emina Torlak
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package minsolver.fol2sat;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import minsolver.MinSATSolver;

import kodkod.ast.Expression;
import kodkod.ast.Formula;
import kodkod.ast.IntExpression;
import kodkod.ast.Node;
import kodkod.ast.Relation;
import kodkod.ast.RelationPredicate;
import kodkod.ast.visitor.AbstractReplacer;
import kodkod.engine.bool.BooleanAccumulator;
import kodkod.engine.bool.BooleanConstant;
import kodkod.engine.bool.BooleanFactory;
import kodkod.engine.bool.BooleanFormula;
import kodkod.engine.bool.BooleanMatrix;
import kodkod.engine.bool.BooleanValue;
import kodkod.engine.bool.Int;
import kodkod.engine.bool.Operator;
import kodkod.engine.config.Options;
import kodkod.instance.Bounds;
import kodkod.instance.Instance;
import kodkod.util.ints.IntSet;
import kodkod.util.nodes.AnnotatedNode;

import static kodkod.util.nodes.AnnotatedNode.*;

/** 
 * Translates, evaluates, and approximates {@link Node nodes} with
 * respect to given {@link Bounds bounds} (or {@link Instance instances}) and {@link Options}.
 * 
 * @author Emina Torlak 
 */
public final class MinTranslator {
	
	/*---------------------- public methods ----------------------*/
	/**
	 * Overapproximates the value of the given expression using the provided bounds and options.
	 * @return a BooleanMatrix whose TRUE entries represent the tuples contained in a sound overapproximation
	 * of the expression.
	 * @throws expression = null || instance = null || options = null
	 * @throws MinUnboundLeafException - the expression refers to an undeclared variable or a relation not mapped by the instance
	 * @throws MinHigherOrderDeclException - the expression contains a higher order declaration
	 */
	@SuppressWarnings("unchecked")
	public static BooleanMatrix approximate(Expression expression, Bounds bounds, Options options) {
		return MinFOL2BoolTranslator.approximate(annotate(expression), MinLeafInterpreter.overapproximating(bounds, options), MinEnvironment.EMPTY);
	}
	
	/**
	 * Evaluates the given formula to a BooleanConstant using the provided instance and options.  
	 * 
	 * @return a BooleanConstant that represents the value of the formula.
	 * @throws NullPointerException - formula = null || instance = null || options = null
	 * @throws MinUnboundLeafException - the formula refers to an undeclared variable or a relation not mapped by the instance
	 * @throws MinHigherOrderDeclException - the formula contains a higher order declaration
	 */
	public static BooleanConstant evaluate(Formula formula, Instance instance, Options options) {
		return (BooleanConstant) MinFOL2BoolTranslator.translate(annotate(formula), MinLeafInterpreter.exact(instance, options));
	}
	
	/**
	 * Evaluates the given expression to a BooleanMatrix using the provided instance and options.
	 * 
	 * @return a BooleanMatrix whose TRUE entries represent the tuples contained by the expression.
	 * @throws NullPointerException - expression = null || instance = null || options = null
	 * @throws MinUnboundLeafException - the expression refers to an undeclared variable or a relation not mapped by the instance
	 * @throws MinHigherOrderDeclException - the expression contains a higher order declaration
	 */
	public static BooleanMatrix evaluate(Expression expression,Instance instance, Options options) {
		return (BooleanMatrix) MinFOL2BoolTranslator.translate(annotate(expression), MinLeafInterpreter.exact(instance, options));
	}

	/**
	 * Evalutes the given intexpression to an {@link kodkod.engine.bool.Int} using the provided instance and options. 
	 * @return an {@link kodkod.engine.bool.Int} representing the value of the intExpr with respect
	 * to the specified instance and options.
	 * @throws NullPointerException - formula = null || instance = null || options = null
	 * @throws MinUnboundLeafException - the expression refers to an undeclared variable or a relation not mapped by the instance
	 * @throws MinHigherOrderDeclException - the expression contains a higher order declaration
	 */
	public static Int evaluate(IntExpression intExpr, Instance instance, Options options) {
		return (Int) MinFOL2BoolTranslator.translate(annotate(intExpr), MinLeafInterpreter.exact(instance,options));
	}
	
	/**
	 * Translates the given formula using the specified bounds and options.
	 * @return a Translation whose solver is a SATSolver instance initialized with the 
	 * CNF representation of the given formula, with respect to the given bounds.  The CNF
	 * is generated in such a way that the magnitude of the literal representing the truth
	 * value of a given formula is strictly larger than the magnitudes of the literals representing
	 * the truth values of the formula's descendants.  
	 * @throws MinTrivialFormulaException - the given formula is reduced to a constant during translation
	 * (i.e. the formula is trivially (un)satisfiable).
	 * @throws NullPointerException - any of the arguments are null
	 * @throws MinUnboundLeafException - the formula refers to an undeclared variable or a relation not mapped by the given bounds.
	 * @throws MinHigherOrderDeclException - the formula contains a higher order declaration that cannot
	 * be skolemized, or it can be skolemized but options.skolemize is false.
	 */
	public static MinTranslation translate(Formula formula, Bounds bounds, Options options) throws MinTrivialFormulaException {
		return (new MinTranslator(formula,bounds,options)).translate();
	}
	
	/*---------------------- private translation state and methods ----------------------*/
	/**
	 * @specfield formula: Formula
	 * @specfield bounds: Bounds
	 * @specfield options: Options
	 * @specfield log: TranslationLog
	 */
	private final Formula formula;
	private final Bounds bounds;
	private final Options options;
	
	private MinTranslationLog log;
		
	/**
	 * Constructs a Translator for the given formula, bounds and options.
	 * @effects this.formula' = formula and 
	 * 	this.options' = options and 
	 * 	this.bounds' = bounds.clone() and
	 *  no this.log'
	 */
	private MinTranslator(Formula formula, Bounds bounds, Options options) {
		this.formula = formula;
		this.bounds = bounds.clone();
		this.options = options;
		this.log = null;
	}
	
	/**
	 * Translates this.formula with respect to this.bounds and this.options.
	 * @return a Translation whose solver is a SATSolver instance initialized with the 
	 * CNF representation of the given formula, with respect to the given bounds.  The CNF
	 * is generated in such a way that the magnitude of a literal representing the truth
	 * value of a given formula is strictly larger than the magnitudes of the literals representing
	 * the truth values of the formula's descendants.  
	 * @throws MinTrivialFormulaException - this.formula is reduced to a constant during translation
	 * (i.e. the formula is trivially (un)satisfiable).
	 * @throws MinUnboundLeafException - this.formula refers to an undeclared variable or a relation not mapped by this.bounds.
	 * @throws MinHigherOrderDeclException - this.formula contains a higher order declaration that cannot
	 * be skolemized, or it can be skolemized but this.options.skolemDepth < 0
	 */
	private MinTranslation translate() throws MinTrivialFormulaException  {
		final AnnotatedNode<Formula> annotated = options.logTranslation()>0 ? annotateRoots(formula) : annotate(formula);
		final MinSymmetryBreaker breaker = optimizeBounds(annotated);	
		return toBoolean(optimizeFormula(annotated, breaker), breaker);
	}
	
	/**
	 * Removes bindings for unused relations/ints from this.bounds and
	 * returns a SymmetryBreaker for the reduced bounds.
	 * @requires annotated.node = this.formula
	 * @effects this.bounds'.relations = this.formula.*children & Relations
	 * @effects !annotated.usesInts() => no this.bounds'.int
	 * @return { b: SymmetryBreaker | b.bounds = this.bounds' }
	 */
	private MinSymmetryBreaker optimizeBounds(AnnotatedNode<Formula> annotated) {	
		// remove bindings for unused relations/ints
		bounds.relations().retainAll(annotated.relations());
		if (!annotated.usesInts()) bounds.ints().clear();
		
		// detect symmetries
		return new MinSymmetryBreaker(bounds, options.reporter());
	}
	
	/**
	 * Optimizes annotated.node by first breaking symmetries on its top-level predicates,
	 * replacing them with the simpler formulas generated by {@linkplain MinSymmetryBreaker#breakMatrixSymmetries(Map, boolean) breaker.breakMatrixSymmetries(...)}, 
	 * and skolemizing the result.
	 * @requires annotated.node = this.formula
	 * @requires breaker.bounds = this.bounds
	 * @return the skolemization, up to depth this.options.skolemDepth, of annotated.node with
	 * the broken predicates replaced with simpler constraints and the remaining predicates inlined. 
	 */
	private AnnotatedNode<Formula> optimizeFormula(AnnotatedNode<Formula> annotated, MinSymmetryBreaker breaker) {	
		options.reporter().optimizingBoundsAndFormula();

		if (options.logTranslation()==0) { // no logging
			annotated = inlinePredicates(annotated, breaker.breakMatrixSymmetries(annotated.predicates(), true).keySet());
			return options.skolemDepth()>=0 ? MinSkolemizer.skolemize(annotated, bounds, options) : annotated;
		} else { // logging; inlining of predicates *must* happen last when logging is enabled
			if (options.coreGranularity()==1) { 
				annotated = MinFormulaFlattener.flatten(annotated, false);
			}
			if (options.skolemDepth()>=0) {
				annotated = MinSkolemizer.skolemize(annotated, bounds, options);
			}
			if (options.coreGranularity()>1) { 
				annotated = MinFormulaFlattener.flatten(annotated, options.coreGranularity()==3);
			}
			return inlinePredicates(annotated, breaker.breakMatrixSymmetries(annotated.predicates(), false));
		}
	}
	
	/**
	 * Returns an annotated formula f such that f.node is equivalent to annotated.node
	 * with its <tt>truePreds</tt> replaced with the constant formula TRUE and the remaining
	 * predicates replaced with equivalent constraints.
	 * @requires this.options.logTranslation = false
	 * @requires truePreds in annotated.predicates()[RelationnPredicate.NAME]
	 * @requires truePreds are trivially true with respect to this.bounds
	 * @return an annotated formula f such that f.node is equivalent to annotated.node
	 * with its <tt>truePreds</tt> replaced with the constant formula TRUE and the remaining
	 * predicates replaced with equivalent constraints.
	 */
	private AnnotatedNode<Formula> inlinePredicates(final AnnotatedNode<Formula> annotated, final Set<RelationPredicate> truePreds) {
		final AbstractReplacer inliner = new AbstractReplacer(annotated.sharedNodes()) {
			public Formula visit(RelationPredicate pred) {
				Formula ret = lookup(pred);
				if (ret!=null) return ret;
				return truePreds.contains(pred) ? cache(pred, Formula.TRUE) : cache(pred, pred.toConstraints());
			}
		};
		return annotate(annotated.node().accept(inliner));	
	}
	
	/**
	 * Returns an annotated formula f such that f.node is equivalent to annotated.node
	 * with its <tt>simplified</tt> predicates replaced with their corresponding Formulas and the remaining
	 * predicates replaced with equivalent constraints.  The annotated formula f will contain transitive source 
	 * information for each of the subformulas of f.node.  Specifically, let t be a subformula of f.node, and
	 * s be a descdendent of annotated.node from which t was derived.  Then, f.source[t] = annotated.source[s]. </p>
	 * @requires this.options.logTranslation = true
	 * @requires simplified.keySet() in annotated.predicates()[RelationPredicate.NAME]
	 * @requires no disj p, p': simplified.keySet() | simplified.get(p) = simplifed.get(p') // this must hold in order
	 * to maintain the invariant that each subformula of the returned formula has exactly one source
	 * @requires for each p in simplified.keySet(), the formulas "p and [[this.bounds]]" and
	 * "simplified.get(p) and [[this.bounds]]" are equisatisfiable
	 * @return an annotated formula f such that f.node is equivalent to annotated.node
	 * with its <tt>simplified</tt> predicates replaced with their corresponding Formulas and the remaining
	 * predicates replaced with equivalent constraints.
	 */
	private AnnotatedNode<Formula> inlinePredicates(final AnnotatedNode<Formula> annotated, final Map<RelationPredicate,Formula> simplified) {
		final Map<Node,Node> sources = new IdentityHashMap<Node,Node>();
		final AbstractReplacer inliner = new AbstractReplacer(annotated.sharedNodes()) {
			private RelationPredicate source =  null;			
			protected <N extends Node> N cache(N node, N replacement) {
				if (replacement instanceof Formula) {
					if (source==null) {
						final Node nsource = annotated.sourceOf(node);
						if (replacement!=nsource) 
							sources.put(replacement, nsource);
					} else {
						sources.put(replacement, source);
					}
				}
				return super.cache(node, replacement);
			}
			public Formula visit(RelationPredicate pred) {
				Formula ret = lookup(pred);
				if (ret!=null) return ret;
				source = pred;
				if (simplified.containsKey(pred)) {
					ret = simplified.get(pred).accept(this);
				} else {
					ret = pred.toConstraints().accept(this);
				}
				source = null;
				return cache(pred, ret);
			}
		};

		return annotate(annotated.node().accept(inliner), sources);
	}
	
	/**
	 * Translates the given annotated formula to a circuit, conjoins the circuit with an 
	 * SBP generated by the given symmetry breaker, flattens the result if so specified by this.options, 
	 * and returns its Translation to CNF.
	 * @requires [[annotated.node]] <=> ([[this.formula]] and [[breaker.broken]])
	 * @effects this.options.logTranslation => some this.log'
	 * @return the result of calling  {@link #generateSBP(BooleanFormula, MinLeafInterpreter, MinSymmetryBreaker)}
	 * on the translation of annotated.node with respect to this.bounds
	 * @throws MinTrivialFormulaException - the translation of annotated is a constant or can be made into
	 * a constant by flattening 
	 */
	private MinTranslation toBoolean(AnnotatedNode<Formula> annotated, MinSymmetryBreaker breaker) throws MinTrivialFormulaException {
		
		options.reporter().translatingToBoolean(annotated.node(), bounds);
		
		final MinLeafInterpreter interpreter = MinLeafInterpreter.exact(bounds, options);
		
		if (options.logTranslation()>0) {
			final MinTranslationLogger logger = options.logTranslation()==1 ? new MinMemoryLogger(annotated, bounds) : new MinFileLogger(annotated, bounds);
			final BooleanAccumulator circuit = MinFOL2BoolTranslator.translate(annotated, interpreter, logger);
			log = logger.log();
			if (circuit.isShortCircuited()) {
				throw new MinTrivialFormulaException(annotated.node(), bounds, circuit.op().shortCircuit(), log);
			} else if (circuit.size()==0) { 
				throw new MinTrivialFormulaException(annotated.node(), bounds, circuit.op().identity(), log);
			}
			return generateSBP(circuit, interpreter, breaker);
		} else {
			final BooleanValue circuit = (BooleanValue)MinFOL2BoolTranslator.translate(annotated, interpreter);
			if (circuit.op()==Operator.CONST) {
				throw new MinTrivialFormulaException(annotated.node(), bounds, (BooleanConstant)circuit, null);
			} 
			return generateSBP(annotated, (BooleanFormula)circuit, interpreter, breaker);
		}
	}		
	
	/**
	 * Adds to given accumulator an SBP generated using the given symmetry breaker and interpreter,
	 * and returns the resulting circuit's translation to CNF.
	 * @requires circuit is a translation of this.formula with respect to this.bounds
	 * @requires interpreter is the leaf interpreter used in generating the given circuit
	 * @requires breaker.bounds = this.bounds
	 * @return toCNF(circuit && breaker.generateSBP(interpreter))
	 */
	private MinTranslation generateSBP(BooleanAccumulator circuit, MinLeafInterpreter interpreter, MinSymmetryBreaker breaker) {
		options.reporter().generatingSBP();
		final BooleanFactory factory = interpreter.factory();
		//circuit.add(breaker.generateSBP(interpreter, options.symmetryBreaking())); 
		BooleanValue sbp = breaker.generateSBP(interpreter, options.symmetryBreaking());
		//return toCNF((BooleanFormula)factory.accumulate(circuit), factory.numberOfVariables(), interpreter.vars());
		return toCNF((BooleanFormula)factory.accumulate(circuit), 
				     sbp,
				factory.numberOfVariables(), interpreter.vars(), breaker);
	}
	
	/**
	 * Conjoins the given circuit with an SBP generated using the given symmetry breaker and interpreter,
	 * and returns the resulting circuit's translation to CNF.
	 * @requires [[annotated.node]] <=> ([[this.formula]] and [[breaker.broken]])
	 * @requires circuit is a translation of annotated.node with respect to this.bounds
	 * @requires interpreter is the leaf interpreter used in generating the given circuit
	 * @requires breaker.bounds = this.bounds
	 * @return flatten(circuit && breaker.generateSBP(interpreter), interpreter)
	 * @throws MinTrivialFormulaException - flattening the circuit and the predicate yields a constant
	 */
	private MinTranslation generateSBP(AnnotatedNode<Formula> annotated, BooleanFormula circuit, MinLeafInterpreter interpreter, MinSymmetryBreaker breaker) 
	throws MinTrivialFormulaException {
		options.reporter().generatingSBP();
		final BooleanValue sbp = breaker.generateSBP(interpreter, options.symmetryBreaking());						
		
		//return flatten(annotated, (BooleanFormula)factory.and(circuit, sbp), factory.and(circuit, sbp), interpreter);
		return flatten(annotated, (BooleanFormula)circuit, sbp, interpreter, breaker);
	}

	/**
	 * If this.options.flatten is true, flattens the given circuit and returns its translation to CNF.
	 * Otherwise, simply returns the given circuit's translation to CNF.
	 * @requires [[annotated.node]] <=> ([[this.formula]] and [[breaker.broken]])
	 * @requires circuit is a translation of annotated.node with respect to this.bounds
	 * @requires interpreter is the leaf interpreter used in generating the given circuit
	 * @return if this.options.flatten then 
	 * 	toCNF(flatten(circuit), interpreter.factory().numberOfVariables(), interpreter.vars()) else
	 *  toCNF(circuit, interpreter.factory().numberOfVariables(), interpreter.vars())
	 * @throws MinTrivialFormulaException - flattening the circuit yields a constant
	 */
	private MinTranslation flatten(AnnotatedNode<Formula> annotated, BooleanFormula circuit, BooleanValue sbp, MinLeafInterpreter interpreter, MinSymmetryBreaker breaker) throws MinTrivialFormulaException {	
		final BooleanFactory factory = interpreter.factory();
		if (options.flatten()) {
			options.reporter().flattening(circuit);
			final BooleanValue flatCircuit = MinBooleanFormulaFlattener.flatten(circuit, factory);
			if (flatCircuit.op()==Operator.CONST) {
				throw new MinTrivialFormulaException(annotated.node(), bounds, (BooleanConstant)flatCircuit, null);
			} else {
				return toCNF((BooleanFormula)flatCircuit, sbp, factory.numberOfVariables(), interpreter.vars(), breaker);
			}
		} else {
			return toCNF(circuit, sbp, factory.numberOfVariables(), interpreter.vars(), breaker);
		}
	}
	
	/**
	 * Translates the given circuit to CNF, adds the clauses to a SATSolver returned
	 * by options.solver(), and returns a Translation object constructed from the solver
	 * and the provided arguments.
	 * @requires circuit is a translation of this.formula with respect to this.bounds
	 * @requires primaryVars is the number of primary variables generated by translating 
	 * this.formula and this.bounds into the given circuit
	 * @requires varUsage maps each non-constant relation in this.bounds to the labels of 
	 * the primary variables used to represent that relation in the given circuit
	 * @return Translation constructed from a SAT solver initialized with the CNF translation
	 * of the given circuit, the provided arguments, this.bounds, and this.log
	 */
	private MinTranslation toCNF(BooleanFormula fmlaCircuit, BooleanValue sbpValue, int primaryVars, Map<Relation,IntSet> varUsage, MinSymmetryBreaker breaker) {	
		options.reporter().translatingToCNF(fmlaCircuit);			
		final MinSATSolver cnf = MinBool2CNFTranslator.translate((BooleanFormula)fmlaCircuit, sbpValue, options.solver(), primaryVars);		
		return new MinTranslation(cnf, bounds, varUsage, primaryVars, log, breaker.getSymmetries(), breaker.brokenPermutations);
	}
	
}