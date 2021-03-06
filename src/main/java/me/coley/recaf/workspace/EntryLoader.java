package me.coley.recaf.workspace;

import me.coley.recaf.plugin.PluginsManager;
import me.coley.recaf.plugin.api.LoadInterceptorPlugin;
import me.coley.recaf.util.ClassUtil;
import me.coley.recaf.util.IllegalBytecodePatcherUtil;
import me.coley.recaf.util.Log;
import org.objectweb.asm.ClassReader;

import java.util.*;
import java.util.zip.ZipEntry;

import static me.coley.recaf.util.Log.*;

/**
 * Standard archive content loader.
 *
 * @author Matt
 */
public class EntryLoader {
	private final Map<String, byte[]> classes = new HashMap<>();
	private final Map<String, byte[]> files = new HashMap<>();
	private final Set<String> invalidClasses = new HashSet<>();

	/**
	 * @return New archive entry loader instance.
	 */
	public static EntryLoader create() {
		EntryLoader loader = PluginsManager.getInstance().getEntryLoader();
		// Fallback to default
		if (loader == null)
			loader = new EntryLoader();
		return loader;
	}

	/**
	 * Load a class.
	 *
	 * @param entryName
	 * 		Class's archive entry name.
	 * @param value
	 * 		Class's bytecode.
	 *
	 * @return Addition was a success.
	 */
	public boolean onClass(String entryName, byte[] value) {
		// Attempt to patch invalid classes.
		// If the internal measure fails, allow plugins to patch invalid classes
		if (!ClassUtil.isValidClass(value)) {
			Log.info("Attempting to patch invalid class '{}'", entryName);
			byte[] patched = IllegalBytecodePatcherUtil.fix(value);
			if (ClassUtil.isValidClass(patched)) {
				// TODO: In more advanced cases we may want to have access to all loaded classes/files
				//  - So this approach can bre refactored later to allow that.
				//  - That'll also change some of the log messages,
				//    moving the "add as a file" to the very end applied to all files, not individually, and
				//    after the patch process fails to fix a given class.
				Log.info(" - Default patching succeeded");
				value = patched;
			} else {
				Log.info(" - Default patching failed");
				for (LoadInterceptorPlugin interceptor :
						PluginsManager.getInstance().ofType(LoadInterceptorPlugin.class)) {
					try {
						value = interceptor.interceptInvalidClass(entryName, value);
					} catch (Throwable t) {
						Log.error(t, "Plugin '{}' threw an exception when reading the invalid class '{}'",
								interceptor.getName(), entryName);
					}
				}
			}
		}
		// Check if class is valid
		if (!ClassUtil.isValidClass(value)) {
			warn("Invalid class \"{}\" - Cannot be parsed with ASM reader\nAdding as a file instead.", entryName);
			invalidClasses.add(entryName);
			onFile(entryName, value);
			return false;
		}
		// Load the class
		String name = new ClassReader(value).getClassName();
		for(LoadInterceptorPlugin interceptor :
				PluginsManager.getInstance().ofType(LoadInterceptorPlugin.class)) {
			// Intercept class
			try {
				value = interceptor.interceptClass(name, value);
			} catch(Throwable t) {
				Log.error(t, "Plugin '{}' threw exception when reading the class '{}'", interceptor.getName(), name);
			}
			// Make sure the class interception doesn't break the class
			if (!ClassUtil.isValidClass(value)) {
				warn("Invalid class '{}' due to modifications by plugin '{}'\nAdding as a file instead.", entryName);
				invalidClasses.add(entryName);
				onFile(entryName, value);
				return false;
			}
			// Update name
			name = new ClassReader(value).getClassName();
		}
		classes.put(name, value);
		return true;
	}

	/**
	 * Load a file.
	 *
	 * @param entryName
	 * 		File's archive entry name.
	 * @param value
	 * 		File's raw value.
	 *
	 * @return Addition was a success.
	 */
	public boolean onFile(String entryName, byte[] value) {
		for (LoadInterceptorPlugin interceptor : PluginsManager.getInstance().ofType(LoadInterceptorPlugin.class)) {
			value = interceptor.interceptFile(entryName, value);
		}
		files.put(entryName, value);
		return true;
	}

	/**
	 * @param entry
	 * 		Zip entry in the archive.
	 *
	 * @return {@code true} if the entry indicates the content should be a class file.
	 */
	public boolean isValidClassEntry(ZipEntry entry) {
		return isFileValidClassName(entry.getName());
	}

	/**
	 * @param name
	 * 		File name.
	 *
	 * @return {@code true} if the entry indicates the content should be a class file.
	 */
	public boolean isFileValidClassName(String name) {
		// Must end in .class or .class/
		return name.endsWith(".class") || name.endsWith(".class/");
	}

	/**
	 * @param entry
	 * 		Zip entry in the archive.
	 *
	 * @return If the entry indicates the content is a valid file.
	 */
	public boolean isValidFileEntry(ZipEntry entry) {
		// If the entry is a directory, then skip it....
		// Unless its a "fake" directory because archive manipulation by obfuscation
		if (entry.isDirectory() && !isValidClassEntry(entry))
			return false;
		String name = entry.getName();
		// name / directory escaping
		if (name.contains("../"))
			return false;
		// empty directory names is a no
		return !name.contains("//");
	}

	/**
	 * Called when all classes in the jar have been read.
	 */
	public void finishClasses() {}

	/**
	 * Called when all files in the archive have been read.
	 */
	public void finishFiles() {}

	/**
	 * @return Loaded classes.
	 */
	public Map<String, byte[]> getClasses() {
		return classes;
	}

	/**
	 * @return Loaded files.
	 */
	public Map<String, byte[]> getFiles() {
		return files;
	}

	/**
	 * @return Set of classes that failed to load.
	 */
	public Set<String> getInvalidClasses() {
		return invalidClasses;
	}
}
