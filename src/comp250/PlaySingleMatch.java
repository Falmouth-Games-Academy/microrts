package comp250;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Policy;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jdom.JDOMException;

import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import ai.core.AI;
import rts.GameState;
import rts.PhysicalGameState;
import rts.PlayerAction;
import rts.Trace;
import rts.TraceEntry;
import rts.units.UnitTypeTable;
import util.RunnableWithTimeOut;
import util.XMLWriter;

public class PlaySingleMatch {
	
	private static AI loadAI(String jarPath, String className, UnitTypeTable utt) throws Exception {
		Class<?> cls;
		if (jarPath == ".") {
			cls = Class.forName(className);
		} else {
			ClassLoader loader = new PluginClassLoader(new File(jarPath).toURI().toURL());
			cls = loader.loadClass(className);
		}
		
		try {
			return (AI)cls.getConstructor(UnitTypeTable.class, int.class).newInstance(utt, 100);
		} catch (NoSuchMethodException error) {
			return (AI)cls.getConstructor(UnitTypeTable.class).newInstance(utt);
		}
	}
	
	private static String getStackTrace(Exception ex) {
		StringWriter writer = new StringWriter();
		ex.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}
	
	private static JsonValue playMatch(
			String jar1, String className1, 
			String jar2, String className2,
			GameState gs, Trace trace) {
		
		int MAXCYCLES = 5000;
        boolean gameover = false;
        
        JsonObject result = new JsonObject();
		
        AI ai1;
		try {
			ai1 = RunnableWithTimeOut.runWithTimeout(new Callable<AI>() {
				public AI call() throws Exception {
					return loadAI(jar1, className1, gs.getUnitTypeTable());
				}
			}, 1, TimeUnit.SECONDS);
		} catch (Exception e1) {
			result.set("disqualified", 1);
			result.set("winner", 2);
			result.set("stackTrace", getStackTrace(e1));
	        result.set("duration", -1);
			return result;
		}
		
        AI ai2;
		try {
			ai2 = RunnableWithTimeOut.runWithTimeout(new Callable<AI>() {
				public AI call() throws Exception {
					return loadAI(jar2, className2, gs.getUnitTypeTable());
				}
			}, 1, TimeUnit.SECONDS);
		} catch (Exception e1) {
			result.set("disqualified", 2);
			result.set("winner", 1);
			result.set("stackTrace", getStackTrace(e1));
	        result.set("duration", -1);
			return result;
		}

        do{
            PlayerAction pa1;
			try {
				pa1 = RunnableWithTimeOut.runWithTimeout(new Callable<PlayerAction>() {
					public PlayerAction call() throws Exception {
						GameState clone = gs.clone();
						PlayerAction action = ai1.getAction(0, gs);
						if (!gs.getPhysicalGameState().equivalents(clone.getPhysicalGameState())) {
							throw new RuleViolationException("getAction modified the game state");
						}
						return action;
					}
				}, 150, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				result.set("disqualified", 1);
				result.set("winner", 2);
				result.set("stackTrace", getStackTrace(e));
		        result.set("duration", gs.getTime());
				return result;
			}
			
            PlayerAction pa2;
			try {
				pa2 = RunnableWithTimeOut.runWithTimeout(new Callable<PlayerAction>() {
					public PlayerAction call() throws Exception {
						GameState clone = gs.clone();
						PlayerAction action = ai2.getAction(1, gs);
						if (!gs.getPhysicalGameState().equivalents(clone.getPhysicalGameState())) {
							throw new RuleViolationException("getAction modified the game state");
						}
						return action;
					}
				}, 150, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				result.set("disqualified", 2);
				result.set("winner", 1);
				result.set("stackTrace", getStackTrace(e));
		        result.set("duration", gs.getTime());
				return result;
			}
			
            	TraceEntry te = new TraceEntry(gs.getPhysicalGameState().clone(),gs.getTime());
                te.addPlayerAction(pa1.clone());
                te.addPlayerAction(pa2.clone());
                trace.addEntry(te);

            gs.issueSafe(pa1);
            gs.issueSafe(pa2);

            // simulate:
            gameover = gs.cycle();
        }while(!gameover && gs.getTime()<MAXCYCLES);
        
        result.set("winner", gs.winner() + 1);
        result.set("duration", gs.getTime());
        return result;
	}

	public static void main(String[] args) {
		
		Policy.setPolicy(new SandboxSecurityPolicy());
		System.setSecurityManager(new SecurityManager());
		
		String jar1 = args[0];
		String className1 = args[1];
		String jar2 = args[2];
		String className2 = args[3];
		String mapName = args[4];
		String traceName = args[5];
		
		UnitTypeTable utt = new UnitTypeTable();
        
		PhysicalGameState pgs;
		try {
			pgs = PhysicalGameState.load(mapName, utt);
		} catch (JDOMException | IOException e) {
			e.printStackTrace();
			return;
		}
		
        GameState gs = new GameState(pgs, utt);
        
        
        Trace trace = new Trace(utt);
        TraceEntry te = new TraceEntry(gs.getPhysicalGameState().clone(),gs.getTime());
        trace.addEntry(te);
        
        JsonValue result = playMatch(jar1, className1, jar2, className2, gs, trace);
        
        te = new TraceEntry(gs.getPhysicalGameState().clone(), gs.getTime());
        trace.addEntry(te);
        
		try {
			ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(traceName));
			
            zip.putNextEntry(new ZipEntry("trace.xml"));
            XMLWriter xml = new XMLWriter(new OutputStreamWriter(zip));
            trace.toxml(xml);
            xml.flush();
            zip.closeEntry();
            
            zip.putNextEntry(new ZipEntry("result.json"));
            BufferedWriter jsonWriter = new BufferedWriter(new OutputStreamWriter(zip));
			result.writeTo(jsonWriter);
			jsonWriter.flush();
			zip.closeEntry();
            
            zip.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// This ensures that any still-running threads get killed
		System.exit(0);
	}
}
