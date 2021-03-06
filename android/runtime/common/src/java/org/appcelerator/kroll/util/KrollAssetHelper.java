/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2011 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.kroll.util;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

public class KrollAssetHelper
{
	private static final String TAG = "TiAssetHelper";
	private static AssetManager assetManager;
	private static String packageName;
	private static String cacheDir;
	private static AssetCrypt assetCrypt;
	private static HashSet<String> apkAssetFilePathSet = new HashSet<>(256);
	private static DirectoryListingMap apkDirectoryListingMap = new DirectoryListingMap(32);
	private static DirectoryListingMap encrpytedDirectoryListingMap = new DirectoryListingMap(256);

	public interface AssetCrypt {
		InputStream openAsset(String path);
		String readAsset(String path);
		Collection<String> getAssetPaths();
	}

	public static void setAssetCrypt(AssetCrypt assetCrypt)
	{
		// Do not continue if reference already assigned.
		if (assetCrypt == KrollAssetHelper.assetCrypt) {
			return;
		}

		// Store the encrypted asset accessing reference.
		KrollAssetHelper.assetCrypt = assetCrypt;

		// Fetch all directories and their file listings from encrypted assets. (For fast access.)
		KrollAssetHelper.encrpytedDirectoryListingMap.clear();
		if (assetCrypt != null) {
			Collection<String> collection = assetCrypt.getAssetPaths();
			if (collection != null) {
				for (String path : collection) {
					KrollAssetHelper.encrpytedDirectoryListingMap.addPath(path);
				}
			}
		}
	}

	public static void init(Context context)
	{
		// Do not continue if already initialized.
		if (KrollAssetHelper.assetManager != null) {
			return;
		}

		// Fetch application info and asset manager.
		KrollAssetHelper.assetManager = context.getAssets();
		KrollAssetHelper.packageName = context.getPackageName();
		KrollAssetHelper.cacheDir = context.getCacheDir().getAbsolutePath();

		// Open the APK as a zip file (fastest way to access its contents) and do the following:
		// - Add all file paths under "assets" to "apkAssetFilePathSet" for quick file existence checks.
		// - Add all "assets" subdirectories and their file/subfolder listings to "apkDirectoryListingMap".
		KrollAssetHelper.apkAssetFilePathSet.clear();
		KrollAssetHelper.apkDirectoryListingMap.clear();
		String apkPath = context.getApplicationInfo().sourceDir;
		try (ZipFile zipFile = new ZipFile(apkPath)) {
			final String APK_ASSETS_PATH = "assets/";
			Enumeration<? extends ZipEntry> entryEnumeration = zipFile.entries();
			while (entryEnumeration.hasMoreElements()) {
				// Fetch the next zip entry path.
				ZipEntry entry = entryEnumeration.nextElement();
				String path = (entry != null) ? entry.getName() : null;
				if (path == null) {
					continue;
				}

				// Ignore zip entries not under the APK's "assets" folder.
				if (!path.startsWith(APK_ASSETS_PATH)) {
					continue;
				}

				// Remove the root "assets/" directory from the path.
				path = path.substring(APK_ASSETS_PATH.length());

				// Store the asset file path and extract its directory listings (if any).
				KrollAssetHelper.apkAssetFilePathSet.add(path);
				KrollAssetHelper.apkDirectoryListingMap.addPath(path);
			}
		} catch (Exception ex) {
			String message = ex.getMessage();
			if (message == null) {
				message = "Unknown";
			}
			Log.e(TAG, "Failed to fetch APK asset entries. Reason: " + message);
		}
	}

	public static InputStream openAsset(String path)
	{
		// Validate argument.
		if ((path == null) || path.isEmpty()) {
			return null;
		}

		// First, attempt to open it as a Titanium encrypted asset.
		if (assetCrypt != null) {
			InputStream stream = assetCrypt.openAsset(normalizeAssetCryptPath(path));
			if (stream != null) {
				return stream;
			}
		}

		// Next, try to open it from APK's "assets" folder.
		try {
			if (assetManager != null) {
				InputStream stream = assetManager.open(path);
				if (stream != null) {
					return stream;
				}
			} else {
				Log.e(TAG, "AssetManager is null, can't open asset: " + path);
			}
		} catch (Exception ex) {
			Log.e(TAG, "Error while opening asset \"" + path + "\":", ex);
		}

		// Failed to find given asset.
		return null;
	}

	public static String readAsset(String path)
	{
		if (assetCrypt != null) {
			String asset = assetCrypt.readAsset(normalizeAssetCryptPath(path));
			if (asset != null) {
				return asset;
			}
		}

		try {
			if (assetManager == null) {
				Log.e(TAG, "AssetManager is null, can't read asset: " + path);
				return null;
			}

			InputStream in = assetManager.open(path);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte buffer[] = new byte[1024];
			int count = 0;

			while ((count = in.read(buffer)) != -1) {
				if (out != null) {
					out.write(buffer, 0, count);
				}
			}

			return out.toString();

		} catch (IOException e) {
			Log.e(TAG, "Error while reading asset \"" + path + "\":", e);
		}

		return null;
	}

	public static Collection<String> getEncryptedAssetPaths()
	{
		if (assetCrypt != null) {
			return assetCrypt.getAssetPaths();
		}
		return null;
	}

	public static String readFile(String path)
	{
		try {
			FileInputStream in = new FileInputStream(path);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte buffer[] = new byte[1024];
			int count = 0;

			while ((count = in.read(buffer)) != -1) {
				if (out != null) {
					out.write(buffer, 0, count);
				}
			}

			return out.toString();

		} catch (FileNotFoundException e) {
			Log.e(TAG, "File not found: " + path, e);

		} catch (IOException e) {
			Log.e(TAG, "Error while reading file: " + path, e);
		}

		return null;
	}

	public static boolean assetExists(String path)
	{
		// Validate argument.
		if (path == null) {
			return false;
		}

		// First, check if path references an encrypted Titanium asset.
		Collection<String> collection = getEncryptedAssetPaths();
		if (collection != null) {
			if (collection.contains(normalizeAssetCryptPath(path))) {
				return true;
			}
		}

		// Next, check if path exists under APK "assets".
		if (apkAssetFilePathSet.contains(path)) {
			return true;
		}

		// Asset path not found.
		return false;
	}

	public static boolean directoryExists(String path)
	{
		path = normalizeDirectoryPath(path);
		if (encrpytedDirectoryListingMap.containsKey(normalizeAssetCryptPath(path))) {
			return true;
		}
		if (apkDirectoryListingMap.containsKey(path)) {
			return true;
		}
		return false;
	}

	public static List<String> getDirectoryListing(String path)
	{
		ArrayList<String> resultList = new ArrayList<>();
		path = normalizeDirectoryPath(path);
		{
			// Grab the APK "assets" listing first since it's likely the largest list.
			Collection<String> collection = apkDirectoryListingMap.get(path);
			if (collection != null) {
				resultList.addAll(collection);
			}
		}
		{
			// Merge the encrypted assets listing (if any) into the result list.
			Collection<String> collection = encrpytedDirectoryListingMap.get(normalizeAssetCryptPath(path));
			if (collection != null) {
				for (String entry : collection) {
					if (!resultList.contains(entry)) {
						resultList.add(entry);
					}
				}
			}
		}
		return resultList;
	}

	public static String getPackageName()
	{
		return packageName;
	}

	public static String getCacheDir()
	{
		return cacheDir;
	}

	private static String normalizeDirectoryPath(String path)
	{
		// Normalize the given path, if necessary.
		if ((path == null) || path.equals("/")) {
			// Path references the root directory.
			path = "";
		} else if (!path.isEmpty() && !path.endsWith("/")) {
			// Subdirectory paths must end with a slash.
			path += "/";
		}
		return path;
	}

	private static String normalizeAssetCryptPath(String path)
	{
		final String ROOT_PATH_NAME = "Resources/";

		if (path != null) {
			if (path.startsWith(ROOT_PATH_NAME)) {
				path = path.substring(ROOT_PATH_NAME.length());
			}
		}
		return path;
	}

	/**
	 * Stores a mapping of directory paths and their file/subfolder listings.
	 * <p>
	 * The keys are the full relative path to the directory. These directory paths always end with
	 * a trailing slash except for the root directory which is represented by an empty string.
	 * <p>
	 * The values store a listing of files and subdirectories owned by the key's directory path.
	 * <p>
	 * You are expected to call this class' addPath() method to extract directories from the given
	 * path and add its trailing file to the listing.
	 */
	private static class DirectoryListingMap extends HashMap<String, TreeSet<String>>
	{
		public DirectoryListingMap(int initialCapacity)
		{
			super(initialCapacity);
		}

		public void addPath(String path)
		{
			// Null path references the root directory.
			if (path == null) {
				path = "";
			}

			// Remove path separator(s) from the front of the path. It must be relative down below.
			int startIndex = 0;
			while ((startIndex < path.length()) && (path.charAt(startIndex) == '/')) {
				startIndex++;
			}
			if (startIndex > 0) {
				if (startIndex < path.length()) {
					path = path.substring(startIndex);
				} else {
					path = "";
				}
			}

			// An empty path represents the root directory.
			// If given one, then add it to the collection and stop here.
			if (path.isEmpty()) {
				TreeSet<String> fileListing = this.get(path);
				if (fileListing == null) {
					this.put(path, new TreeSet<String>());
				}
				return;
			}

			// Parse the given path's subdirectories and trailing file name and add them to the collection.
			for (int nameIndex = 0; nameIndex < path.length();) {
				// Fetch the next component from the path.
				int separatorIndex = path.indexOf('/', nameIndex);

				// There are 2 path separators next to each other. Skip it.
				if (separatorIndex == nameIndex) {
					nameIndex++;
					continue;
				}

				// Fetch the parent directory path for the next component.
				String parentDirectoryPath = "";
				if (nameIndex > 0) {
					parentDirectoryPath = path.substring(0, nameIndex);
				}

				// Fetch the next component name.
				String name;
				if (separatorIndex > nameIndex) {
					// This is a subdirectory name.
					name = path.substring(nameIndex, separatorIndex);
					nameIndex = separatorIndex + 1;
				} else {
					// This is a file name since a path separator was not found.
					name = path.substring(nameIndex);
					nameIndex = path.length();
				}

				// Fetch the parent directory's file listing. Create one if it doesn't exist.
				TreeSet<String> fileListing = this.get(parentDirectoryPath);
				if (fileListing == null) {
					fileListing = new TreeSet<String>();
					this.put(parentDirectoryPath, fileListing);
				}

				// Add the file/subfolder name to the listing.
				fileListing.add(name);
			}
		}
	}
}
