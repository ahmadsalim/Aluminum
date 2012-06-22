package minsolver;

import kodkod.engine.satlab.SATFactory;
import kodkod.engine.satlab.SATSolver;

import org.sat4j.minisat.SolverFactory;

public class MinSATSolverFactory extends SATFactory
{   
	private MyReporter theReporter; 

	public MinSATSolverFactory()
	{
		super();
	}
	
	public MinSATSolverFactory(MyReporter theReporter)
	{
		super();
		this.theReporter = theReporter;
	}
	
    @Override
    public SATSolver instance()
    {
    	// Registering a reporter gives us two pieces of information:
    	// (1) Which variables are primary (and thus subject to tampering to get minimal models);
    	// (2) Which relations are added for Skolemization.
    	// ... can also get at more information by modifying MyReporter.
    	
    	MinSATSolver result = new MinSATSolver(SolverFactory.instance().defaultSolver());
    	//TODO result.registerReporter(theReporter);
        return result;
    }
    
}