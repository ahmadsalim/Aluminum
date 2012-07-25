package minalloy;
/* This class implements a program that records the execution time of alloy specs.*/

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Stack;
import java.util.StringTokenizer;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.opts.BooleanOption;
import org.kohsuke.args4j.opts.FileOption;
import org.kohsuke.args4j.opts.IntOption;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.ErrorWarning;
import edu.mit.csail.sdg.alloy4compiler.ast.Command;
import edu.mit.csail.sdg.alloy4compiler.ast.Module;
import edu.mit.csail.sdg.alloy4compiler.parser.CompUtil;
import edu.mit.csail.sdg.alloy4compiler.translator.A4Options;
import edu.mit.csail.sdg.alloy4compiler.translator.A4Solution;
import edu.mit.csail.sdg.alloy4compiler.translator.TranslateAlloyToKodkod;
import minalloy.translator.MinA4Options;
import minalloy.translator.MinA4Solution;
import minalloy.translator.MinTranslateAlloyToKodkod;
import minsolver.ExplorationException;


public final class ExecutionTimeRecorder {
    /*
     * Execute every command in every file.
     *
     * This method parses every file, then execute every command.
     *
     * If there are syntax or type errors, it may throw
     * a ErrorSyntax or ErrorType or ErrorAPI or ErrorFatal exception.
     * You should catch them and display them,
     * and they may contain filename/line/column information.
     */
    public static void main(String[] args) throws Err {
    	//The input spec file
    	FileOption optInput = new FileOption("-i");
    	//The output file
    	FileOption optOutput = new FileOption("-o");
    	//Produce minimal solutions (by default non-minimal)
    	BooleanOption optMinimal = new BooleanOption("-m", false);
    	//Number of models to produce
    	IntOption optNumberOfModels = new IntOption("-n", 10);
    	//Symmetry Breaking (off by default)
    	IntOption optSymmetryBreaking = new IntOption("-sb", 0);
    	//Augmentation information
    	FileOption optAugmentation = new FileOption("-a");
    	//Number of trials
    	IntOption optNumberOfTrials = new IntOption("-t", 1);

    	
    	CmdLineParser optParser = new CmdLineParser();
    	optParser.addOption(optInput);
    	optParser.addOption(optOutput);
    	optParser.addOption(optMinimal);
    	optParser.addOption(optNumberOfModels);
    	optParser.addOption(optSymmetryBreaking);
    	optParser.addOption(optAugmentation);
    	optParser.addOption(optNumberOfTrials);
    	
    	try{
    		optParser.parse(args);
    	}
    	catch(CmdLineException e){
    		System.err.println(e.getMessage());
    	}

    	if(optInput.value == null){
    		System.err.println("No input file is provided!");
    		System.exit(0);
    	}
    	if(optInput.value == null){
    		System.err.println("No output file is provided!");
    		System.exit(0);
    	}
    	if(!optMinimal.value && optAugmentation.value != null){
    		System.err.println("Augmentation is only applicable on minimal model finding.");
    		System.exit(0);
    	}
    	
    	
    	//TODO this is the worst code ever! Consider revision:
    	if(optMinimal.value)
    		solveMinimal(optInput, optOutput, optMinimal, optNumberOfModels, optSymmetryBreaking, optAugmentation, optNumberOfTrials);
    	else
    		solveNonMinimal(optInput, optOutput, optMinimal, optNumberOfModels, optSymmetryBreaking, optNumberOfTrials);
    }

    /**
     * Runs the tests using Aluminum
     */
	private static void solveMinimal(FileOption optInput, FileOption optOutput, 
			BooleanOption optMinimal, IntOption optNumberOfModels, 
			IntOption optSymmetryBreaking, FileOption optAugmentation, 
			IntOption optNumberOfTrials) throws Err {
		ArrayList<String> output = new ArrayList<String>();    	
        // Alloy4 sends diagnostic messages and progress reports to the A4Reporter.
        // By default, the A4Reporter ignores all these events (but you can extend the A4Reporter to display the event for the user)
        A4Reporter rep = new A4Reporter() {
            // For example, here we choose to display each "warning" by printing it to System.out
            @Override public void warning(ErrorWarning msg) {
                System.out.print("Relevance Warning:\n"+(msg.toString().trim())+"\n\n");
                System.out.flush();
            }
        };

        // Parse+typecheck the model
        System.out.println("Parsing+Typechecking "+optInput.value.getName());
        output.add("Spec: " + optInput.value.getName());
        
        System.out.println("-m = " + optMinimal.value);
        output.add("-m = " + optMinimal.value);
        System.out.println("-sb = " + optSymmetryBreaking.value);
        output.add("-sb = " + optSymmetryBreaking.value);
        System.out.println("-n = " + optNumberOfModels.value + "\n");
        output.add("-n = " + optNumberOfModels.value + "\n");
        
        Module world = CompUtil.parseEverything_fromFile(rep, null, optInput.value.getPath());

        // Choose some default options for how you want to execute the commands
        MinA4Options options = new MinA4Options();
        options.symmetry = optSymmetryBreaking.value;
        
        Stack<AugmentationElement> stack = null;
        if(optAugmentation.value != null){
        	try{
        		stack = parseAugmentationFile(optAugmentation.value);
        	}
        	catch(IOException e){
        		System.err.println(e.getMessage());
        		System.exit(0);
        	}
        }

        for (Command command: world.getAllCommands()) {

    		// Execute the command
    		System.out.println("Command: "+command + "-------------\n");
    		output.add("Command:\t" + command.toString() + "-------------\n");
    		
            //Keeps the number of items in the output so far. We keep this number to add data in the next trials.
            int lineNumber = output.size() - 1;    		
    		
        	for(int i = 0; i < optNumberOfTrials.value; i++){   
        		System.out.println("TRIAL " + (i + 1) + "------");
        		
        		MinA4Solution ans = null;
        		try{
        			ans = getFirstSolution(rep, world, command, options, output, stack, i, lineNumber);
        		}
        		catch(ExplorationException e){
        			System.err.println(e.getMessage());
        			System.exit(0);
        		}

        		long time = 0;            
        		int counter = 1;

        		while(ans.satisfiable()){
        			if(counter == optNumberOfModels.value)
        				break;

        			time = System.currentTimeMillis();                	
        			ans = ans.next();
        			time = System.currentTimeMillis() - time;

        			System.out.println(++counter + ": " + time);
        			if(i == 0)
        				output.add(new Long(time).toString());
        			else
        				output.set(counter + lineNumber, output.get(counter + lineNumber) + "\t" + time); 
        		}
        	}
	        
	        try{
	        	writeOutput(output, optOutput.value);
	        }
	        catch(IOException e){
	        	System.err.println(e.getMessage());
	        }
        }
	}
	
	private static MinA4Solution getFirstSolution(A4Reporter rep, Module world, Command command, 
			MinA4Options options, ArrayList<String> output, Stack<AugmentationElement> stack, int trial, int lineNumber) 
					throws Err, ExplorationException{
        long time = 0;
        time = System.currentTimeMillis();
        MinA4Solution ans = MinTranslateAlloyToKodkod.execute_command(rep, world.getAllReachableSigs(), command, options);
        time = System.currentTimeMillis() - time;
        
        int counter = 1;

        while(stack!= null && !stack.empty()){
        	AugmentationElement aug = stack.pop();
        	while(counter != aug.solutionNumber){
        		ans = ans.next();
        		counter ++;
        	}
        	
        	time = System.currentTimeMillis();
        	ans = ans.lift(aug.augmentingFact);
        	time = System.currentTimeMillis() - time;
        }
        
        //TODO separate translation and execution times.
        System.out.println("1: " + time);
        if(trial == 0)
        	output.add(new Long(time).toString());
        else
        	output.set(lineNumber + 1, output.get(lineNumber + 1) + "\t" + time);
		
		return ans;
	}
	
	/**
	 * Runs the tests using Alloy 
	 */
	private static void solveNonMinimal(FileOption optInput, FileOption optOutput, 
			BooleanOption optMinimal, IntOption optNumberOfModels, 
			IntOption optSymmetryBreaking, IntOption optNumberOfTrials) throws Err {
		ArrayList<String> output = new ArrayList<String>();    	
        // Alloy4 sends diagnostic messages and progress reports to the A4Reporter.
        // By default, the A4Reporter ignores all these events (but you can extend the A4Reporter to display the event for the user)
        A4Reporter rep = new A4Reporter() {
            // For example, here we choose to display each "warning" by printing it to System.out
            @Override public void warning(ErrorWarning msg) {
                System.out.print("Relevance Warning:\n"+(msg.toString().trim())+"\n\n");
                System.out.flush();
            }
        };

        // Parse+typecheck the model
        System.out.println("Parsing+Typechecking "+optInput.value.getName());
        output.add("Spec: " + optInput.value.getName());
        
        System.out.println("-m = " + optMinimal.value);
        output.add("-m = " + optMinimal.value);
        System.out.println("-sb = " + optSymmetryBreaking.value);
        output.add("-sb = " + optSymmetryBreaking.value);
        System.out.println("-n = " + optNumberOfModels.value + "\n");
        output.add("-n = " + optNumberOfModels.value + "\n");
        
        Module world = CompUtil.parseEverything_fromFile(rep, null, optInput.value.getPath());

        // Choose some default options for how you want to execute the commands
        A4Options options = new A4Options();
        options.symmetry = optSymmetryBreaking.value;

        for (Command command: world.getAllCommands()) {	
        	// Execute the command
        	System.out.println("Command: "+command + "-------------\n");
        	output.add("Command:\t" + command.toString() + "-------------\n");

            //Keeps the number of items in the output so far. We keep this number to add data in the next trials.
            int lineNumber = output.size() - 1;

        	for(int i = 0; i < optNumberOfTrials.value; i++){
        		System.out.println("TRIAL " + (i + 1) + "------");
        		
        		long time = 0;
        		time = System.currentTimeMillis();
        		A4Solution ans = TranslateAlloyToKodkod.execute_command(rep, world.getAllReachableSigs(), command, options);
        		time = System.currentTimeMillis() - time;
        		
        		int counter = 1;
        		
        		//TODO separate translation and execution times.
        		System.out.println("1: " + time);
        		if(i == 0)
        			output.add(new Long(time).toString());
        		else
        			output.set(lineNumber + counter, output.get(lineNumber + counter) + "\t" + time);

        		while(ans.satisfiable()){
        			if(counter == optNumberOfModels.value)
        				break;

        			time = System.currentTimeMillis();                	
        			ans = ans.next();
        			time = System.currentTimeMillis() - time;

        			System.out.println(++counter + ": " + time);
        			if(i == 0)
        				output.add(new Long(time).toString());
        			else
        				output.set(counter + lineNumber, output.get(counter + lineNumber) + "\t" + time);
        		}
            }
        }
        
        try{
        	writeOutput(output, optOutput.value);
        }
        catch(IOException e){
        	System.err.println(e.getMessage());
        }
	}
    
    private static void writeOutput(ArrayList<String> output, File outputFile) throws IOException{
    	FileWriter fstream = new FileWriter(outputFile);
    	BufferedWriter out = new BufferedWriter(fstream);
    	for(String str: output){
    		out.write(str + "\n");
    	}
    	out.close();
    }
    
    private static Stack<AugmentationElement> parseAugmentationFile(File file) 
    		throws IOException{
    	Stack<AugmentationElement> result = new Stack<AugmentationElement>();
    	
		FileReader fstream = new FileReader(file);
    	BufferedReader in = new BufferedReader(fstream);
    	
    	String currentLine;
    	while((currentLine = in.readLine()) != null){
    		//Skip comment lines
    		if(currentLine.startsWith("--"))
    			continue;
    		
    		StringTokenizer tokenizer = new StringTokenizer(currentLine, "\t");
    		AugmentationElement element = new AugmentationElement(
    				new Integer(tokenizer.nextToken()).intValue(), tokenizer.nextToken());
    		
    		result.add(element);
    	}
    	
    	in.close();
    	return result;
    }
}

/**
 * A data structure that holds information about a solution that is being augmented and
 * a fact that is augmenting the solution.
 */
class AugmentationElement {
	int solutionNumber;
	String augmentingFact;
	
	AugmentationElement(int solutionNumber, String augmentationFact){
		this.solutionNumber = solutionNumber;
		this.augmentingFact = augmentationFact;
	}
}