package com.spazedog.rootfw;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.HashMap;

import android.os.Bundle;
import android.util.Log;

import com.spazedog.rootfw.containers.ShellResult;
import com.spazedog.rootfw.helpers.Binaries;
import com.spazedog.rootfw.helpers.Busybox;
import com.spazedog.rootfw.helpers.Filesystem;
import com.spazedog.rootfw.helpers.Utils;
import com.spazedog.rootfw.tools.AsyncProcess;
import com.spazedog.rootfw.tools.AsyncProcess.AsyncProcessReceiver;

public final class RootFW {
	
	public final static String TAG = "RootFW";
	
	public final static Integer LOG_INFO = 1;
	public final static Integer LOG_WARNING = 2;
	public final static Integer LOG_ERROR = 3;
	
	private static HashMap<String, RootFW> SHELLS = new HashMap<String, RootFW>();
	
	private Process SHELL = null;
	private Boolean SHELL_IS_CONNECTED = false;
	private Boolean SHELL_IS_ROOT = false;
	private Boolean COPY = false;
	private String NAME = null;
	
	public final Busybox busybox = new Busybox(this);
	public final Filesystem filesystem = new Filesystem(this);
	public final Binaries binaries = new Binaries(this);
	public final Utils utils = new Utils(this);
	
	public static void log(String argMsg) {
		log(null, argMsg, LOG_INFO, null);
	}
	
	public static void log(String argTag, String argMsg) {
		log(argTag, argMsg, LOG_INFO, null);
	}
	
	public static void log(String argTag, String argMsg, Integer argLevel) {
		log(argTag, argMsg, argLevel, null);
	}
	
	public static void log(String argTag, String argMsg, Integer argLevel, Throwable e) {
		String tag = argTag == null ? TAG : argTag;
		
		switch (argLevel) {
			case 1: Log.v(tag, argMsg, e); break;
			case 2: Log.w(tag, argMsg, e); break;
			case 3: Log.e(tag, argMsg, e); break;
		}
	}
	
	public static String mkCmdEcho(String argContent) {
		return "$(busybox echo 'busybox echo " + argContent + "' 2>/dev/null || toolbox echo 'toolbox echo " + argContent + "' 2>/dev/null || echo '" + argContent + "' 2>/dev/null)";
	}
	
	public static String mkCmd(String argCmd) {
		return "busybox " + argCmd + " 2>/dev/null || toolbox " + argCmd + " 2>/dev/null || " + argCmd + " 2>/dev/null";
	}
	
	/*
	 * Just a small hack which allows the class alone
	 * to get an instance without opening a process at the same time
	 */
	private RootFW(Integer hack) {}
	
	/* 
	 * This will return a nameless object.
	 * If you need to use the same process in different places, 
	 * like different methods which calls one another, 
	 * use the getInstance() below instead. 
	 */
	public RootFW() {
		this(true);
	}
	
	public RootFW(Boolean argUseRoot) {
		SHELL_IS_CONNECTED = argUseRoot;
		
		try {
			ProcessBuilder builder;
			ShellResult result;
			
			if (argUseRoot) {
				builder = new ProcessBuilder("su");
				
			} else {
				builder = new ProcessBuilder("sh");
			}
			
			builder.redirectErrorStream(true);
			
			SHELL = builder.start();
			
			if (argUseRoot) {
				if ((result = runShell("id")) != null && result.getResult().getLastLine().contains("uid=0")) {
					SHELL_IS_CONNECTED = true;
				}
				
			} else {
				SHELL_IS_CONNECTED = true;
			}
			
		} catch(Throwable e) { e.printStackTrace(); }
	}
	
	/* 
	 * This method allows you to get a copy of an already existing instance 
	 * containing a reference to an already opened shell process. The object itself
	 * is a new instance, but it will use the same shell process as the parent object.
	 * 
	 * Note that copy objects will not be allowed to close the shell process. 
	 * This is meant to be used when one code with an open process calls
	 * another code that needs a process. This way both can use the same,
	 * but the closing will be done from the parent code. 
	 * 
	 * If there is no open shell with the parsed name, a new process will be
	 * returned instead. In this case this will be used as the parent and 
	 * will be allowed to close the process.
	 */
	public static RootFW getInstance(String argName) {
		return getInstance(argName, true);
	}
	
	public static RootFW getInstance(String argName, Boolean argUseRoot) {
		String name = argName + (argUseRoot ? ":root" : ":user");
		
		if (SHELLS.get(name) == null) {
			SHELLS.put(name, new RootFW(argUseRoot));
			SHELLS.get(name).NAME = argName;
			
		} else {
			RootFW rfw = new RootFW(0);
			rfw.SHELL = SHELLS.get(name).SHELL;
			rfw.SHELL_IS_CONNECTED = SHELLS.get(name).SHELL_IS_CONNECTED;
			rfw.SHELL_IS_ROOT = SHELLS.get(name).SHELL_IS_ROOT;
			rfw.NAME = SHELLS.get(name).NAME;
			rfw.COPY = true;
			
			return rfw;
		}
		
		return SHELLS.get(name);
	}
	
	/*
	 * This will close the shell process inside the object.
	 * Note that only the initiating object can close it,
	 * clones are not allowed to, as these are meant to be used
	 * in between the initial opening and closing.
	 */
	public void close() {
		if (!COPY) {
			try {
				runShell("exit");
				SHELL.destroy();
				
			} catch(Throwable e) { e.printStackTrace(); }
			
			if (NAME != null) {
				String name = NAME + (SHELL_IS_ROOT ? ":root" : ":user");
				
				if (SHELLS.get(name) != null) {
					SHELLS.remove(name);
				}
			}
			
			SHELL = null;
			SHELL_IS_CONNECTED = false;
		}
	}
	
	/*
	 * Check whether the current object is a clone or an initiating object
	 */
	public Boolean isCopy() {
		return COPY;
	}
	
	/*
	 * Check whether this shell process was opened with root access or not.
	 * This does not check whether or not root is available on a device.
	 * It just checks to see if 'su' was called instead of 'sh' during
	 * the initiation of the process
	 */
	public Boolean isRootShell() {
		return SHELL_IS_ROOT;
	}
	
	/*
	 * Check whether or not the shell is ready to be used or not
	 */
	public Boolean isConnected() {
		return SHELL_IS_CONNECTED;
	}
	
	/*
	 * Get the name of this object. It will return null
	 * if this instance of was created nameless
	 */
	public String getShellName() {
		return NAME;
	}
	
	/*
	 * This will execute one or more commands in the shell process
	 * and return the data and result code. 
	 * 
	 * Commands can be passed in singles as a String or
	 * you can parse multiple commands using String[] instead.
	 * Just note that the result code will be from the last command.
	 * So if you are depended on the result code, you should parse one command
	 * at a time and check the result code before parsing another command.
	 * 
	 * Also note that the result data will be from all entered commands
	 * 
	 * @ Example: runShell("ls -l /data")
	 * @ Example: runShell( new String[] {"command1", "command2"} )
	 */
	public ShellResult runShell(String argCommand) {
		return runShell(new String[] {argCommand});
	}
	
	public ShellResult runShell(String[] argCommand) {
		try {
			DataOutputStream output = new DataOutputStream(SHELL.getOutputStream());
			BufferedReader buffer = new BufferedReader(new InputStreamReader(SHELL.getInputStream()));
			String cmd="", data="", line, result=null;
			Boolean skip = false;
			
			for (int i=0; i < argCommand.length; i++) {
				cmd += argCommand[i] + "\n";
			}
			
			cmd += "\n";
			cmd += "busybox echo $? 2> /dev/null || toolbox echo $? 2> /dev/null || echo $? 2> /dev/null || $?\n";
			cmd += "busybox echo EOL:a00c38d8:EOL 2> /dev/null || toolbox echo EOL:a00c38d8:EOL 2> /dev/null || echo EOL:a00c38d8:EOL 2> /dev/null || EOL:a00c38d8:EOL\n";
			
			/* The problem with BufferedReader.readLine, is that it will block as soon as it reaches the end, as it will be waiting
			 * for the next line to be printed. In this case it will never return NULL at any point. So we add a little ID at the end
			 * that we can look for and then manually break the loop when that ID is returned, while getting the last line, which is what we need
			 */
			output.write(cmd.getBytes()); 
			output.flush();
			
			try {
				while ((line = buffer.readLine()) != null && !line.contains("EOL:a00c38d8:EOL")) {
					if (!skip && result != null) {
						data += result + "\n";
					}
					
					if (line.length() > 0) {
						result = line;
						skip = false;
						
					} else {
						data += line + "\n";
						skip = true;
					}
				}
				
				Integer iResult;
				try { iResult = Integer.parseInt(result.split(":")[0]); } catch(Throwable e) { iResult = -1; }
				
				ShellResult shellresult = new ShellResult(iResult, data.split("\n"));
				
				return shellresult;
				
			} catch(Throwable e) { e.printStackTrace(); }
			
		} catch(Throwable e) { e.printStackTrace(); }
		
		return null;
	}
	
	/*
	 * These methods will start a new shell process which will be executed
	 * in the background. 
	 * 
	 * By implementing the AsyncProcessReceiver interface into your main class and parsing it along,
	 * you can execute code before and after the process executes.
	 * 
	 * If you parse a bundle, then this will be parsed to both the pre and post
	 * methods in your AsyncProcessReceiver class
	 * 
	 * @ Example: RootFW.startProcess("/system/bin/process", true, mybundle, myclass);
	 */
	public static AsyncProcess startProcess(String[] argCmd) {
		return AsyncProcess.injectInstance(argCmd, true, null, null);
	}
	
	public static AsyncProcess startProcess(String argCmd) {
		return AsyncProcess.injectInstance(new String[] {argCmd}, true, null, null);
	}
	
	public static AsyncProcess startProcess(String[] argCmd, Boolean argUseroot) {
		return AsyncProcess.injectInstance(argCmd, argUseroot, null, null);
	}
	
	public static AsyncProcess startProcess(String argCmd, Boolean argUseroot) {
		return AsyncProcess.injectInstance(new String[] {argCmd}, argUseroot, null, null);
	}
	
	public static AsyncProcess startProcess(String[] argCmd, Boolean argUseroot, Bundle argBundle) {
		return AsyncProcess.injectInstance(argCmd, argUseroot, argBundle, null);
	}
	
	public static AsyncProcess startProcess(String argCmd, Boolean argUseroot, Bundle argBundle) {
		return AsyncProcess.injectInstance(new String[] {argCmd}, argUseroot, argBundle, null);
	}
	
	public static AsyncProcess startProcess(String[] argCmd, Boolean argUseroot, Bundle argBundle, AsyncProcessReceiver argReciever) {
		return AsyncProcess.injectInstance(argCmd, argUseroot, argBundle, argReciever);
	}
	
	public static AsyncProcess startProcess(String argCmd, Boolean argUseroot, Bundle argBundle, AsyncProcessReceiver argReciever) {
		return AsyncProcess.injectInstance(new String[] {argCmd}, argUseroot, argBundle, argReciever);
	}
	
	public static AsyncProcess injectAsyncProcess(AsyncProcess argProcess) {
		return (AsyncProcess) argProcess.execute("");
	}
}