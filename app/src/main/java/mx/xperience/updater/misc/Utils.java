/*
 * Copyright (C) 2017-2025 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package mx.xperience.updater.misc;

import android.app.AlarmManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import mx.xperience.updater.R;
import mx.xperience.updater.UpdatesDbHelper;
import mx.xperience.updater.controller.UpdaterService;
import mx.xperience.updater.model.Update;
import mx.xperience.updater.model.UpdateBaseInfo;
import mx.xperience.updater.model.UpdateInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {

    private static final String TAG = "Utils";

    private Utils() {
    }

    public static File getDownloadPath(Context context) {
        return new File(context.getString(R.string.download_path));
    }

    public static File getCachedUpdateList(Context context) {
        return new File(context.getCacheDir(), "updates.json");
    }

    // This should really return an UpdateBaseInfo object, but currently this only
    // used to initialize UpdateInfo objects.
    //
    // The regular fields always describe the full OTA. An optional incremental
    // object (or entry in incrementals[]) may override the active package only
    // when its source build matches this device exactly. The full OTA remains the
    // fallback for every other installed build.
    private static UpdateInfo parseJsonUpdate(JSONObject object) throws JSONException {
        Update update = new Update();
        update.setTimestamp(object.getLong("fileTimestamp"));
        update.setName(object.getString("file"));
        update.setDownloadId(object.getString("filemd5"));
        update.setFileSize(object.getLong("filesize"));
        update.setFullFileSize(update.getFileSize());
        update.setType(object.getString("romtype"));
        update.setDownloadUrl(object.getString("filelink"));
        update.setVersion(object.getString("version"));
        update.setIncremental(false);

        JSONObject incremental = findCompatibleIncremental(object);
        if (incremental != null) {
            try {
                update.setName(incremental.getString("file"));
                update.setDownloadId(getPackageId(incremental));
                update.setFileSize(incremental.getLong("filesize"));
                update.setDownloadUrl(incremental.getString("filelink"));
                update.setIncremental(true);
                Log.i(TAG, "Using incremental OTA " + update.getName()
                        + " for installed build "
                        + SystemProperties.get(Constants.PROP_BUILD_VERSION_INCREMENTAL));
            } catch (JSONException e) {
                Log.e(TAG, "Incremental OTA metadata is incomplete; falling back to full OTA", e);
                update.setName(object.getString("file"));
                update.setDownloadId(object.getString("filemd5"));
                update.setFileSize(object.getLong("filesize"));
                update.setDownloadUrl(object.getString("filelink"));
                update.setIncremental(false);
            }
        }
        return update;
    }

    private static JSONObject findCompatibleIncremental(JSONObject update) {
        Object single = update.opt("incremental");
        if (single instanceof JSONObject && isIncrementalCompatible((JSONObject) single)) {
            return (JSONObject) single;
        }
        if (single instanceof JSONArray) {
            JSONObject match = findCompatibleIncremental((JSONArray) single);
            if (match != null) {
                return match;
            }
        }

        JSONArray incrementals = update.optJSONArray("incrementals");
        return incrementals != null ? findCompatibleIncremental(incrementals) : null;
    }

    private static JSONObject findCompatibleIncremental(JSONArray incrementals) {
        for (int i = 0; i < incrementals.length(); i++) {
            JSONObject candidate = incrementals.optJSONObject(i);
            if (candidate != null && isIncrementalCompatible(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isIncrementalCompatible(JSONObject incremental) {
        String expectedFingerprint = optString(incremental,
                "preBuild", "pre-build", "pre_build", "sourceFingerprint");
        String expectedIncremental = optString(incremental,
                "preBuildIncremental", "pre-build-incremental",
                "pre_build_incremental", "sourceIncremental");

        // Never guess. An incremental OTA is only safe when the JSON identifies
        // its source build with at least one exact build identity.
        if (expectedFingerprint.isEmpty() && expectedIncremental.isEmpty()) {
            Log.w(TAG, "Ignoring incremental OTA without source build metadata");
            return false;
        }

        if (!expectedFingerprint.isEmpty() && !expectedFingerprint.equals(Build.FINGERPRINT)) {
            Log.d(TAG, "Incremental fingerprint mismatch: expected "
                    + expectedFingerprint + ", device is " + Build.FINGERPRINT);
            return false;
        }

        String currentIncremental = SystemProperties.get(Constants.PROP_BUILD_VERSION_INCREMENTAL);
        if (!expectedIncremental.isEmpty() && !expectedIncremental.equals(currentIncremental)) {
            Log.d(TAG, "Incremental build mismatch: expected "
                    + expectedIncremental + ", device is " + currentIncremental);
            return false;
        }

        return true;
    }

    private static String getPackageId(JSONObject object) throws JSONException {
        String id = object.optString("filemd5", "").trim();
        if (id.isEmpty()) {
            id = object.optString("id", "").trim();
        }
        if (id.isEmpty()) {
            throw new JSONException("Missing incremental filemd5/id");
        }
        return id;
    }

    private static String optString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    public static boolean isCompatible(UpdateBaseInfo update) {
        if (update.getVersion().compareTo(SystemProperties.get(Constants.PROP_BUILD_VERSION)) < 0) {
            Log.d(TAG, update.getName() + " is older than current Android version");
            return false;
        }
        if (!SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false) &&
                update.getTimestamp() <= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) {
            Log.d(TAG, update.getName() + " is older than/equal to the current build");
            return false;
        }
        if (!update.getType().equalsIgnoreCase(SystemProperties.get(Constants.PROP_RELEASE_TYPE))) {
            Log.d(TAG, update.getName() + " has type " + update.getType());
            return false;
        }
        return true;
    }

    private static boolean compareVersions(String a, String b, boolean allowMajorUpgrades) {
        try {
            int majorA = Integer.parseInt(a.split("\\.")[0]);
            int minorA = Integer.parseInt(a.split("\\.")[1]);

            int majorB = Integer.parseInt(b.split("\\.")[0]);
            int minorB = Integer.parseInt(b.split("\\.")[1]);

            // Return early and allow if we allow major version upgrades
            return (allowMajorUpgrades && majorA > majorB)
                    || (majorA == majorB && minorA >= minorB);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            return false;
        }
    }

    public static boolean canInstall(UpdateBaseInfo update) {
        boolean allowMajorUpgrades = SystemProperties.getBoolean(
                Constants.PROP_ALLOW_MAJOR_UPGRADES, false);

        return (SystemProperties.getBoolean(Constants.PROP_UPDATER_ALLOW_DOWNGRADING, false) ||
                update.getTimestamp() > SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) &&
                compareVersions(
                        update.getVersion(),
                        SystemProperties.get(Constants.PROP_BUILD_VERSION),
                        allowMajorUpgrades);
    }

    public static List<UpdateInfo> parseJson(File file, boolean compatibleOnly)
            throws IOException, JSONException {
        List<UpdateInfo> updates = new ArrayList<>();

        StringBuilder json = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            for (String line; (line = br.readLine()) != null;) {
                json.append(line);
            }
        }

        JSONObject obj = new JSONObject(json.toString());
        JSONArray updatesList = obj.getJSONArray("files");
        for (int i = 0; i < updatesList.length(); i++) {
            if (updatesList.isNull(i)) {
                continue;
            }
            try {
                UpdateInfo update = parseJsonUpdate(updatesList.getJSONObject(i));
                if (!compatibleOnly || isCompatible(update)) {
                    updates.add(update);
                } else {
                    Log.d(TAG, "Ignoring incompatible update " + update.getName());
                }
            } catch (JSONException e) {
                Log.e(TAG, "Could not parse update object, index=" + i, e);
            }
        }

        return updates;
    }

    public static String getServerURL(Context context) {
        String version = SystemProperties.get(Constants.PROP_BUILD_VERSION);
        String device = SystemProperties.get(Constants.PROP_NEXT_DEVICE,
                SystemProperties.get(Constants.PROP_DEVICE));
        String type = SystemProperties.get(Constants.PROP_RELEASE_TYPE).toLowerCase(Locale.ROOT);
        String tipo = "nightly";

        String serverUrl = SystemProperties.get(Constants.PROP_UPDATER_URI);
        if (serverUrl.trim().isEmpty()) {
            serverUrl = context.getString(R.string.updater_server_url);
        }

        return serverUrl.replace("{device}", device)
                .replace("{version}", version)
                .replace("{type}", type);
    }

    public static String getUpgradeBlockedURL(Context context) {
        String device = SystemProperties.get(Constants.PROP_NEXT_DEVICE,
                SystemProperties.get(Constants.PROP_DEVICE));
        return context.getString(R.string.blocked_update_info_url, device);
    }

    public static String getChangelogURL(Context context) {
        String device = SystemProperties.get(Constants.PROP_NEXT_DEVICE,
                SystemProperties.get(Constants.PROP_DEVICE));
        String version = SystemProperties.get(Constants.PROP_BUILD_VERSION);
        String type = SystemProperties.get(Constants.PROP_RELEASE_TYPE).toLowerCase(Locale.ROOT);

        String changelogURI = SystemProperties.get(Constants.PROP_UPDATER_URI);
        if ( changelogURI.trim().isEmpty()) {
            changelogURI = context.getString(R.string.menu_changelog_url);
        }

        Log.d(TAG, "Weeee el changelog");

        return changelogURI.replace("{device}", device)
                .replace("{version}", version)
                .replace("{type}", type);
    }

    public static void triggerUpdate(Context context, String downloadId) {
        final Intent intent = new Intent(context, UpdaterService.class);
        intent.setAction(UpdaterService.ACTION_INSTALL_UPDATE);
        intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId);
        context.startService(intent);
    }

    public static boolean isNetworkAvailable(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network activeNetwork = cm.getActiveNetwork();
        NetworkCapabilities networkCapabilities = cm.getNetworkCapabilities(activeNetwork);
        if (networkCapabilities != null &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)
                    || networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        return false;
    }

    public static boolean isNetworkMetered(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        return cm.isActiveNetworkMetered();
    }

    /**
     * Compares two json formatted updates list files
     *
     * @param oldJson old update list
     * @param newJson new update list
     * @return true if newJson has at least a compatible update not available in oldJson
     */
    public static boolean checkForNewUpdates(File oldJson, File newJson)
            throws IOException, JSONException {
        List<UpdateInfo> oldList = parseJson(oldJson, true);
        List<UpdateInfo> newList = parseJson(newJson, true);
        Set<String> oldIds = new HashSet<>();
        for (UpdateInfo update : oldList) {
            oldIds.add(update.getDownloadId());
        }
        // In case of no new updates, the old list should
        // have all (if not more) the updates
        for (UpdateInfo update : newList) {
            if (!oldIds.contains(update.getDownloadId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        final int FIXED_HEADER_SIZE = 30;
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
        long offset = 0;
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            int n = entry.getName().length();
            int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
            int headerSize = FIXED_HEADER_SIZE + n + m;
            offset += headerSize;
            if (entry.getName().equals(entryPath)) {
                return offset;
            }
            offset += entry.getCompressedSize();
        }
        Log.e(TAG, "Entry " + entryPath + " not found");
        throw new IllegalArgumentException("The given entry was not found");
    }

    public static void removeUncryptFiles(File downloadPath) {
        File[] uncryptFiles = downloadPath.listFiles(
                (dir, name) -> name.endsWith(Constants.UNCRYPT_FILE_EXT));
        if (uncryptFiles == null) {
            return;
        }
        for (File file : uncryptFiles) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /**
     * Cleanup the download directory, which is assumed to be a privileged location
     * the user can't access and that might have stale files. This can happen if
     * the data of the application are wiped.
     *
     */
    public static void cleanupDownloadsDir(Context context) {
        File downloadPath = getDownloadPath(context);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        removeUncryptFiles(downloadPath);

        long buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0);
        long prevTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0);
        String lastUpdatePath = preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null);
        boolean reinstalling = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false);
        boolean deleteUpdates = preferences.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false);
        if ((buildTimestamp != prevTimestamp || reinstalling) && deleteUpdates &&
                lastUpdatePath != null) {
            File lastUpdate = new File(lastUpdatePath);
            if (lastUpdate.exists()) {
                //noinspection ResultOfMethodCallIgnored
                lastUpdate.delete();
                // Remove the pref not to delete the file if re-downloaded
                preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply();
            }
        }

        final String DOWNLOADS_CLEANUP_DONE = "cleanup_done";
        if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
            return;
        }

        Log.d(TAG, "Cleaning " + downloadPath);
        if (!downloadPath.isDirectory()) {
            return;
        }
        File[] files = downloadPath.listFiles();
        if (files == null) {
            return;
        }

        // Ideally the database is empty when we get here
        List<String> knownPaths = new ArrayList<>();
        try (UpdatesDbHelper dbHelper = new UpdatesDbHelper(context)) {
            for (UpdateInfo update : dbHelper.getUpdates()) {
                knownPaths.add(update.getFile().getAbsolutePath());
            }
        }
        for (File file : files) {
            if (!knownPaths.contains(file.getAbsolutePath())) {
                Log.d(TAG, "Deleting " + file.getAbsolutePath());
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply();
    }

    public static File appendSequentialNumber(final File file) {
        String name;
        String extension;
        int extensionPosition = file.getName().lastIndexOf(".");
        if (extensionPosition > 0) {
            name = file.getName().substring(0, extensionPosition);
            extension = file.getName().substring(extensionPosition);
        } else {
            name = file.getName();
            extension = "";
        }
        final File parent = file.getParentFile();
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            File newFile = new File(parent, name + "-" + i + extension);
            if (!newFile.exists()) {
                return newFile;
            }
        }
        throw new IllegalStateException();
    }

    public static boolean isABDevice() {
        return SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false);
    }

    public static boolean isABUpdate(ZipFile zipFile) {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null;
    }

    public static boolean isABUpdate(File file) throws IOException {
        ZipFile zipFile = new ZipFile(file);
        boolean isAB = isABUpdate(zipFile);
        zipFile.close();
        return isAB;
    }

    public static boolean hasTouchscreen(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    public static void addToClipboard(Context context, String label, String text,
                                      String toastMessage) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(
                Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show();
    }

    public static boolean isEncrypted(Context context, File file) {
        StorageManager sm = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        return sm.isEncrypted(file);
    }

    public static int getUpdateCheckSetting(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        return preferences.getInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY);
    }

    public static boolean isUpdateCheckEnabled(Context context) {
        return getUpdateCheckSetting(context) != Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER;
    }

    public static void rebootDevice(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            pm.reboot(null);
        }
    }

    public static long getUpdateCheckInterval(Context context) {
        switch (Utils.getUpdateCheckSetting(context)) {
            case Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY:
                return AlarmManager.INTERVAL_DAY;
            case Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY:
            default:
                return AlarmManager.INTERVAL_DAY * 7;
            case Constants.AUTO_UPDATES_CHECK_INTERVAL_MONTHLY:
                return AlarmManager.INTERVAL_DAY * 30;
        }
    }

    public static boolean isRecoveryUpdateExecPresent() {
        return new File(Constants.UPDATE_RECOVERY_EXEC).exists();
    }
}
