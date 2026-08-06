/*
 * Copyright (C) 2017-2023 The LineageOS Project
 * Copyright (C) 2026 The XPerience Project
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
package mx.xperience.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import mx.xperience.updater.controller.UpdaterController;
import mx.xperience.updater.controller.UpdaterService;
import mx.xperience.updater.download.DownloadClient;
import mx.xperience.updater.misc.BuildInfoUtils;
import mx.xperience.updater.misc.Constants;
import mx.xperience.updater.misc.StringGenerator;
import mx.xperience.updater.misc.Utils;
import mx.xperience.updater.model.Update;
import mx.xperience.updater.model.UpdateInfo;
import mx.xperience.updater.model.UpdateStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private View mRefreshIconView;
    private RotateAnimation mRefreshAnimation;

    private boolean mIsTV;

    private UpdateInfo mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    // Nuevas vistas para el nuevo diseño
    private TextView mUpdateTitle;
    private TextView mUpdateDate;
    private TextView mUpdateVersion;
    private TextView mUpdateSize;
    private TextView mChangelogText;
    /*private TextView mGrupSupport;*/
    private TextView mSystemInfo;
    private TextView mLastCheck;
    private TextView mNoUpdatesTitle;
    private TextView mNoUpdatesMessage;
    private android.widget.Button  mCheckForUpdatesButton;
    private android.widget.ProgressBar mDownloadProgress;
    private android.widget.Button mDownloadButton;
    private android.widget.ImageButton mFabInstall;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_updates);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mRefreshAnimation = new RotateAnimation(0, 360,
        Animation.RELATIVE_TO_SELF, 0.5f,
        Animation.RELATIVE_TO_SELF, 0.5f);
        mRefreshAnimation.setInterpolator(new LinearInterpolator());
        mRefreshAnimation.setDuration(1000);

        mUpdateImporter = new UpdateImporter(this, this);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        // Initialise new design views
        mUpdateTitle = findViewById(R.id.update_title);
        mUpdateDate = findViewById(R.id.update_date);
        mUpdateVersion = findViewById(R.id.update_version);
        mUpdateSize = findViewById(R.id.update_size);
        mChangelogText = findViewById(R.id.changelog_text);
        /*mGrupSupport = findViewById(R.id.grup_support);*/
        mDownloadProgress = findViewById(R.id.download_progress);
        mDownloadButton = findViewById(R.id.download_button);
        mFabInstall = findViewById(R.id.fab_install);
        mSystemInfo = findViewById(R.id.system_info);
        mLastCheck = findViewById(R.id.last_check);
        mNoUpdatesTitle = findViewById(R.id.no_updates_title);
        mNoUpdatesMessage = findViewById(R.id.no_updates_message);
        mCheckForUpdatesButton = findViewById(R.id.check_for_updates_button);

        // Configure initial views
        if (mUpdateTitle != null) {
            mUpdateTitle.setText(getString(R.string.update_available));
        }

        /*if (mGrupSupport != null) {
            mGrupSupport.setText(getString(R.string.grup_support, "xperiencechat"));
        }*/

        // Hide elements initially
        if (mDownloadProgress != null) {
            mDownloadProgress.setVisibility(View.GONE);
        }

        if (mFabInstall != null) {
            mFabInstall.setVisibility(View.GONE);
        }

        // Hide changelog_container initially
        View changelogContainer = findViewById(R.id.changelog_container);
        if (changelogContainer != null) {
            changelogContainer.setVisibility(View.GONE);
        }

        if (mSystemInfo != null) {
            mSystemInfo.setText(getString(R.string.header_android_version, Build.VERSION.RELEASE));
        }

        // Update the last check-up:
        updateLastCheckedView();

        // Configure the check button:
        if (mCheckForUpdatesButton != null) {
            mCheckForUpdatesButton.setOnClickListener(v -> {
                downloadUpdatesList(true);
            });
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> onSupportNavigateUp());

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    if (downloadId != null && downloadId.equals(Update.LOCAL_ID)) {
                        UpdateInfo update = mUpdaterService != null ? 
                            mUpdaterService.getUpdaterController().getUpdate(downloadId) : null;
                        
                        if (update != null && update.getStatus() == UpdateStatus.INSTALLED) {
                            setUpdateAvailableSetting(context, false);
                            showRebootDialog();
                        }
                    }
                    handleDownloadStatusChange(downloadId);
                    updateUIForCurrentUpdate(); // This will call updateButtonState
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    // Update speed and progress in real time
                    updateDownloadProgress(downloadId);
                } else if (UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    // Update installation progress
                    updateDownloadProgress(downloadId);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    List<UpdateInfo> sortedUpdates =
                            mUpdaterService.getUpdaterController().getUpdates();
                    if (sortedUpdates.isEmpty()) {
                        showNoUpdatesView();
                    } else {
                        updateUIForCurrentUpdate();
                    }
                }
            }
        };

        // Configure button listeners
        if (mDownloadButton != null) {
            mDownloadButton.setOnClickListener(v -> {
                if (mUpdaterService != null) {
                    List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
                    if (!updates.isEmpty()) {
                        UpdateInfo update = updates.get(0);
                        UpdateStatus status = update.getStatus();

                        if (status == UpdateStatus.INSTALLING) {
                            showCancelInstallationDialog();
                        } else {
                            handleDownloadButtonClick(update);
                        }
                    }
                }
            });
        }

        Button cancelButton = findViewById(R.id.cancel_button);
        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> {
                if (mUpdaterService != null) {
                    List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
                    if (!updates.isEmpty()) {
                        UpdateInfo update = updates.get(0);
                        UpdateStatus status = update.getStatus();
                        
                        if (status == UpdateStatus.DOWNLOADING || 
                            status == UpdateStatus.STARTING || 
                            status == UpdateStatus.PAUSED ||
                            status == UpdateStatus.PAUSED_ERROR) {
                            showCancelDownloadDialog(update);
                        } else {
                            showSnackbar(R.string.no_download_to_cancel, Snackbar.LENGTH_SHORT);
                        }
                    }
                }
            });
        }

        if (mFabInstall != null) {
            mFabInstall.setOnClickListener(v -> {
                if (mUpdaterService != null) {
                    List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
                    if (!updates.isEmpty()) {
                        UpdateInfo update = updates.get(0);
                        UpdaterController controller = mUpdaterService.getUpdaterController();
                        if (controller.isInstallingUpdate() || 
                            controller.isInstallingUpdate(update.getDownloadId())) {
                            showSnackbar(R.string.already_installing, Snackbar.LENGTH_LONG);
                            return;
                        }
                        Utils.triggerUpdate(this, update.getDownloadId());
                    }
                }
            });
        }

        maybeShowWelcomeMessage();
    }

    private void resumeDownloadWithChecks(String downloadId) {
        if (mUpdaterService == null) return;
        
        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) return;
        
        File file = update.getFile();
        File directory = file != null ? file.getParentFile() : null;
        
        // Verificar permisos y directorio
        if (file == null || directory == null) {
            showSnackbar(R.string.error_invalid_file_path, Snackbar.LENGTH_LONG);
            return;
        }
        
        // Crear directorio si no existe
        if (!directory.exists() && !directory.mkdirs()) {
            showSnackbar(R.string.error_creating_directory, Snackbar.LENGTH_LONG);
            return;
        }
        
        // Verificar permisos de escritura
        if (!directory.canWrite()) {
            showSnackbar(R.string.error_no_write_permission, Snackbar.LENGTH_LONG);
            return;
        }
        
        // Intentar reanudar
        try {
            mUpdaterService.getUpdaterController().resumeDownload(downloadId);
        } catch (Exception e) {
            Log.e(TAG, "Error resuming download", e);
            showSnackbar(R.string.error_resuming_download, Snackbar.LENGTH_LONG);
        }
    }

    private void updateLastCheckedView() {
        if (mLastCheck != null) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
            String lastCheckString;

            if (lastCheck > 0) {
                lastCheckString = getString(R.string.header_last_updates_check,
                        StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                        StringGenerator.getTimeLocalized(this, lastCheck));
            } else {
                lastCheckString = "Last checked: Never";
            }

            mLastCheck.setText(lastCheckString);
        }
    }

    private void updateUIForCurrentUpdate() {
        if (mUpdaterService == null) return;

        List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
        if (updates.isEmpty()) {
            showNoUpdatesView();
            return;
        }

        UpdateInfo update = updates.get(0);

        // Show new design
        findViewById(R.id.update_container).setVisibility(View.VISIBLE);
        findViewById(R.id.changelog_container).setVisibility(View.VISIBLE);

        // Hide original views
        findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
        findViewById(R.id.recycler_view).setVisibility(View.GONE);

        // Configure update information
        mUpdateDate.setText(StringGenerator.getDateLocalized(this,
                DateFormat.MEDIUM, update.getTimestamp()));

        mUpdateVersion.setText(update.getVersion());

        long size = update.getFileSize();
        mUpdateSize.setText(android.text.format.Formatter.formatShortFileSize(this, size));

        // Update button status according to download status
        updateButtonState(update);

        // Load changelog for this update
        loadChangelogForUpdate(update);
    }

    private void loadChangelogForUpdate(UpdateInfo update) {
        String changelogUrl = Utils.getChangelogURL(this);

       // Log.d(TAG, "Changelog URL: " + changelogUrl);

        // Load the changelog in the background
        new LoadChangelogTask().execute(changelogUrl);
    }

    private class LoadChangelogTask extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... urls) {
            String changelogUrl = urls[0];
            StringBuilder changelogContent = new StringBuilder();

            try {
                URL url = new URL(changelogUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream())
                    );

                    String line;
                    while ((line = reader.readLine()) != null) {
                        changelogContent.append(line).append("\n");
                    }
                    reader.close();

                    // Format the changelog to make it look better.
                    return formatChangelog(changelogContent.toString());
                } else {
                    Log.e(TAG, "Failed to fetch changelog. Response code: " + responseCode);
                    return getString(R.string.changelog_unavailable);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error loading changelog", e);
                return getString(R.string.changelog_unavailable);
            }
        }

        @Override
        protected void onPostExecute(String changelog) {
            mChangelogText.setText(changelog);
        }

        private String formatChangelog(String rawChangelog) {
            // Format the changelog so that it looks nice.
            StringBuilder formatted = new StringBuilder();
            String[] lines = rawChangelog.split("\n");

            for (String line : lines) {
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                // Detect dates in DD-MMM-YYYY format or similar
                if (line.matches(".*\\d{1,2}-[A-Za-z]{3}-\\d{4}.*") ||
                    line.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) {
                    formatted.append("\n📅 ");
                    formatted.append(line);
                    formatted.append("\n━━━━━━━━━━━━━━━━━━━━\n");
                }
                // Detect items with different types of bullet points
                else if (line.startsWith("•") || line.startsWith("*") || line.startsWith("-")) {
                    String cleanLine = line.substring(1).trim();
                    formatted.append("   ◦ ");
                    formatted.append(cleanLine);
                    formatted.append("\n");
                }
                // Titles or headings
                else if (line.contains(":") && !line.startsWith(" ") && line.length() < 50) {
                    formatted.append("\n🔹 ");
                    formatted.append(line);
                    formatted.append("\n");
                }
                // Texto normal
                else {
                    formatted.append(line);
                    formatted.append("\n");
                }
            }

            // If there is no formatted content, display the raw content.
            if (formatted.length() == 0) {
                return rawChangelog;
            }

            return formatted.toString().trim();
        }
    }

    private void updateButtonState(UpdateInfo update) {
       mx.xperience.updater.model.UpdateStatus status = update.getStatus();

        RelativeLayout downloadInfoContainer = findViewById(R.id.download_info_container);
        TextView downloadSpeedTextView = findViewById(R.id.download_speed);
        TextView downloadPercentageTextView = findViewById(R.id.download_percentage);
        Button cancelButton = findViewById(R.id.cancel_button);

        switch (status) {
            case UNKNOWN:
            case DELETED:
                mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setText(R.string.download);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.GONE);

                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;

            case STARTING:
            case DOWNLOADING:
               mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setText(R.string.pause_download);
                mDownloadProgress.setVisibility(View.VISIBLE);
                mDownloadProgress.setIndeterminate(status == mx.xperience.updater.model.UpdateStatus.STARTING);
                mFabInstall.setVisibility(View.GONE);

                if (downloadInfoContainer != null) {
                    downloadInfoContainer.setVisibility(View.VISIBLE);
                }

                if (cancelButton != null) {
                    cancelButton.setVisibility(View.VISIBLE);
                    cancelButton.setText(R.string.cancel_download);
                }

                // Display current speed and percentage
                if (downloadSpeedTextView != null) {
                    downloadSpeedTextView.setText(formatSpeedAndEta(update));
                }
                if (downloadPercentageTextView != null) {
                    downloadPercentageTextView.setText(formatPercentage(update));
                }
                break;

            case VERIFIED:
                mDownloadButton.setVisibility(View.GONE);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.VISIBLE);

                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;

            case PAUSED:
            case PAUSED_ERROR:
                mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setText(R.string.resume_download);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.GONE);

                if (downloadInfoContainer != null) {
                    downloadInfoContainer.setVisibility(View.VISIBLE);
                }

                if (cancelButton != null) {
                    cancelButton.setVisibility(View.VISIBLE);
                    cancelButton.setText(R.string.cancel_download);
                }

                // Display current speed and percentage (paused)
                if (downloadSpeedTextView != null) {
                    // When paused, speed = 0
                    downloadSpeedTextView.setText(formatSpeed(0));
                }
                if (downloadPercentageTextView != null) {
                    downloadPercentageTextView.setText(formatPercentage(update));
                }
                break;

            case VERIFICATION_FAILED:
                mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setText(R.string.retry_download);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.GONE);
                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;

            case INSTALLING:
                mDownloadButton.setVisibility(View.GONE);
                mDownloadProgress.setVisibility(View.VISIBLE);
                mDownloadProgress.setIndeterminate(true);
                mFabInstall.setVisibility(View.GONE);
                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;

            case VERIFYING:
                mDownloadButton.setVisibility(View.GONE);
                mDownloadProgress.setVisibility(View.VISIBLE);
                mDownloadProgress.setIndeterminate(true);
                mFabInstall.setVisibility(View.GONE);

                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;

            case INSTALLED:
                mDownloadButton.setVisibility(View.VISIBLE);
                mDownloadButton.setText(R.string.reboot_to_complete);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.GONE);
                
                // reboot action
                mDownloadButton.setOnClickListener(v -> {
                    Utils.rebootDevice(this);
                });
                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;
            case INSTALLATION_FAILED:
            case INSTALLATION_CANCELLED:
            case INSTALLATION_SUSPENDED:
                mDownloadButton.setVisibility(View.GONE);
                mDownloadProgress.setVisibility(View.GONE);
                mFabInstall.setVisibility(View.GONE);
                if (downloadInfoContainer != null) {
                    downloadInfoContainer.setVisibility(View.GONE);
                }
                if (cancelButton != null) {
                    cancelButton.setVisibility(View.GONE);
                }
                break;
        }
    }

    // Method for formatting the downloaded/total size (same as in the adapter)
    private String formatDownloadProgress(UpdateInfo update) {
        if (update == null) return "";

        String downloaded = android.text.format.Formatter.formatShortFileSize(this,
                update.getFile().length());
        String total = android.text.format.Formatter.formatShortFileSize(this, update.getFileSize());

        return getString(R.string.list_download_progress_newer, downloaded, total);
    }

    // Method for formatting the percentage (same as in the adapter)
    private String formatPercentage(UpdateInfo update) {
        if (update == null) return "0%";

        float progress = update.getProgress();
        int progressPercent = (int) progress;
        return NumberFormat.getPercentInstance().format(progressPercent / 100.f);
    }

    // Method for formatting speed + ETA
    private String formatSpeedAndEta(UpdateInfo update) {
        if (update == null) return "";

        long speed = update.getSpeed();
        long eta = update.getEta();

        String speedText = formatSpeed(speed);

        if (eta > 0) {
            CharSequence etaString = StringGenerator.formatETA(this, eta * 1000);
            return getString(R.string.download_speed_with_eta, speedText, etaString);
        } else {
            return speedText;
        }
    }

    private void updateDownloadProgress(String downloadId) {
        if (mUpdaterService == null) return;

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) return;

        mx.xperience.updater.model.UpdateStatus status = update.getStatus();

        RelativeLayout downloadInfoContainer = findViewById(R.id.download_info_container);
        TextView downloadSpeedTextView = findViewById(R.id.download_speed);
        TextView downloadPercentageTextView = findViewById(R.id.download_percentage);

        if (status == mx.xperience.updater.model.UpdateStatus.DOWNLOADING ||
            status == mx.xperience.updater.model.UpdateStatus.STARTING) {

            // Display download information
            if (downloadInfoContainer != null) {
                downloadInfoContainer.setVisibility(View.VISIBLE);
            }

            // Show progress
            mDownloadProgress.setVisibility(View.VISIBLE);
            mDownloadProgress.setIndeterminate(status == mx.xperience.updater.model.UpdateStatus.STARTING);

            // Calculate actual progress (0-100)
            float progress = update.getProgress();
            int progressPercent = (int) progress;
            mDownloadProgress.setProgress(progressPercent);

            // Update speed (with ETA if available)
            if (downloadSpeedTextView != null) {
                downloadSpeedTextView.setText(formatSpeedAndEta(update));
            }

            // Update percentage (same as in the adapter)
            if (downloadPercentageTextView != null) {
                downloadPercentageTextView.setText(formatPercentage(update));
            }

            // Update button text (same as in the adapter)
            if (status == mx.xperience.updater.model.UpdateStatus.STARTING) {
                mDownloadButton.setText(R.string.download_starting);
            } else {
                mDownloadButton.setText(R.string.pause_download);
            }

        } else if (status == mx.xperience.updater.model.UpdateStatus.VERIFYING) {
            // VERIFICANDO
            mDownloadProgress.setVisibility(View.VISIBLE);
            mDownloadProgress.setIndeterminate(true);
            mDownloadButton.setVisibility(View.GONE);
            mFabInstall.setVisibility(View.GONE);
            
            if (downloadInfoContainer != null) {
                downloadInfoContainer.setVisibility(View.VISIBLE);
            }
            
            if (downloadSpeedTextView != null) {
                downloadSpeedTextView.setText(R.string.list_verifying_update);
            }
            if (downloadPercentageTextView != null) {
                downloadPercentageTextView.setText("");
            }
            
        } else if (status == mx.xperience.updater.model.UpdateStatus.INSTALLING) {
            // INSTALANDO
            mDownloadProgress.setVisibility(View.VISIBLE);
            mDownloadButton.setVisibility(View.GONE);
            mFabInstall.setVisibility(View.GONE);
            
            if (downloadInfoContainer != null) {
                downloadInfoContainer.setVisibility(View.VISIBLE);
            }
            
            // Get installation progress
            int installProgress = update.getInstallProgress();
            boolean isFinalizing = update.getFinalizing();
            
            // Update progress
            mDownloadProgress.setIndeterminate(false);
            mDownloadProgress.setProgress(installProgress);
            
            // Display messages as notifications
            if (downloadSpeedTextView != null) {
                if (mUpdaterService.getUpdaterController().isInstallingABUpdate()) {
                    // For A/B updates
                    if (isFinalizing) {
                        downloadSpeedTextView.setText(R.string.finalizing_package);
                    } else {
                        downloadSpeedTextView.setText(R.string.preparing_ota_first_boot);
                    }
                } else {
                    // For non-A/B updates
                    downloadSpeedTextView.setText(R.string.dialog_prepare_zip_message);
                }
            }
            
            // Display installation percentage
            if (downloadPercentageTextView != null) {
                String percentage = NumberFormat.getPercentInstance().format(installProgress / 100.f);
                downloadPercentageTextView.setText(percentage);
            }
            
        } else {
            // Hide download information in other states
            if (downloadInfoContainer != null) {
                downloadInfoContainer.setVisibility(View.GONE);
            }
            mDownloadProgress.setVisibility(View.GONE);
        }
    }

    private String formatSpeed(long bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        } else if (bytesPerSecond < 1024 * 1024) {
            return String.format("%.1f KB/s", bytesPerSecond / 1024.0);
        } else {
            return String.format("%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0));
        }
    }

    private void handleDownloadButtonClick(UpdateInfo update) {
        if (mUpdaterService == null) return;
        
        UpdaterController controller = mUpdaterService.getUpdaterController();
        UpdateStatus status = update.getStatus();

        switch (status) {
            case UNKNOWN:
            case DELETED:
            case VERIFICATION_FAILED:
                // Start download
                controller.startDownload(update.getDownloadId());
                break;

            case STARTING:
            case DOWNLOADING:
                // Pause download
                controller.pauseDownload(update.getDownloadId());
                break;

            case PAUSED:
            case PAUSED_ERROR:
                // Resume download
                try {
                    File file = update.getFile();
                    if (file == null || !file.exists()) {
                        showFileDeletedDialog(update);
                        return;
                    }

                    if (update.getFileSize() > 0 && file.length() == 0) {
                       showFileCorruptedDialog(update);
                        return;
                    }

                    controller.resumeDownload(update.getDownloadId());
                } catch (Exception e) {
                    Log.e(TAG, "Error resuming download", e);
                    showSnackbar(R.string.error_resuming_download, Snackbar.LENGTH_LONG);

                }
                
                break;

            case VERIFIED:
                // Install update
                if (mUpdaterService != null) {
                    
                    // Check whether an installation is already in progress
                    if (controller.isInstallingUpdate()) {
                        showSnackbar(R.string.already_installing, Snackbar.LENGTH_LONG);
                        return;
                    }
                    
                    // Check whether this specific update is already being installed.
                    if (controller.isInstallingUpdate(update.getDownloadId())) {
                        showSnackbar(R.string.update_already_installing, Snackbar.LENGTH_LONG);
                        return;
                    }
                    
                    Utils.triggerUpdate(this, update.getDownloadId());
                }
                break;

            case INSTALLING:
                showCancelInstallationDialog();
                break;
            case INSTALLED:
                break;
            case INSTALLATION_FAILED:
            case INSTALLATION_CANCELLED:
            case INSTALLATION_SUSPENDED:
                // These states do not require action from the download button.
                break;
        }
    }

    private void showFileDeletedDialog(UpdateInfo update) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_deleted_title)
                .setMessage(R.string.file_deleted_message)
                .setPositiveButton(R.string.redownload, (dialog, which) -> {
                    // Delete the update and download it again.
                    mUpdaterService.getUpdaterController().deleteUpdate(update.getDownloadId());
                    // Please wait a moment and try downloading again.
                    new Handler().postDelayed(() -> {
                        mUpdaterService.getUpdaterController().startDownload(update.getDownloadId());
                    }, 500);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showFileCorruptedDialog(UpdateInfo update) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_corrupted_title)
                .setMessage(R.string.file_corrupted_message)
                .setPositiveButton(R.string.redownload, (dialog, which) -> {
                    // Delete the update and download it again.
                    mUpdaterService.getUpdaterController().deleteUpdate(update.getDownloadId());
                    // Please wait a moment and try downloading again.
                    new Handler().postDelayed(() -> {
                        mUpdaterService.getUpdaterController().startDownload(update.getDownloadId());
                    }, 500);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCancelDownloadDialog(UpdateInfo update) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_download_title)
                .setMessage(R.string.cancel_download_message)
                .setPositiveButton(R.string.cancel_download, (dialog, which) -> {
                    // Cancelar la descarga
                    mUpdaterService.getUpdaterController().pauseDownload(update.getDownloadId());
                    mUpdaterService.getUpdaterController().deleteUpdate(update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCancelInstallationDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cancel_installation_title)
                .setMessage(R.string.cancel_installation_message)
                .setPositiveButton(R.string.cancel_installation, (dialog, which) -> {
                    // Cancel installation
                    Intent intent = new Intent(this, UpdaterService.class);
                    intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                    startService(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showNoUpdatesView() {
        // Hide new design
        findViewById(R.id.update_container).setVisibility(View.GONE);
        findViewById(R.id.changelog_container).setVisibility(View.GONE);

        // Show original view when there are no updates
        findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
        findViewById(R.id.recycler_view).setVisibility(View.GONE);

        updateLastCheckedView();

        if (mFabInstall != null) mFabInstall.setVisibility(View.GONE);
        if (mDownloadButton != null) mDownloadButton.setVisibility(View.GONE);
        if (mDownloadProgress != null) mDownloadProgress.setVisibility(View.GONE);

        // Display message in changelog
        mChangelogText.setText(getString(R.string.no_updates_available));
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_refresh) {
            downloadUpdatesList(true);
            return true;
        } else if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            // Display message already displayed in the app
            Toast.makeText(this, R.string.changelog_already_displayed, Toast.LENGTH_SHORT).show();
            return true;
        } else if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();
        updateUIForCurrentUpdate();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
            .setTitle(R.string.local_update_import)
            .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
            .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                mAdapter.addItem(update.getDownloadId());
                // Update UI
                getUpdatesList();
                
                // Hide download button and show progress
                if (mDownloadButton != null) {
                    mDownloadButton.setVisibility(View.GONE);
                }
                if (mDownloadProgress != null) {
                    mDownloadProgress.setVisibility(View.VISIBLE);
                    mDownloadProgress.setIndeterminate(true);
                }
                
                Utils.triggerUpdate(this, update.getDownloadId());
                
                // Start checking installation status
                setupInstallationListener(update.getDownloadId());
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                // Only delete if installation has NOT been started.
                UpdaterController.getInstance(this).deleteUpdate(update.getDownloadId());
                // Update UI to show that there are no updates
                showNoUpdatesView();
            })
            .setOnCancelListener((dialog) -> {
                UpdaterController.getInstance(this).deleteUpdate(update.getDownloadId());
                showNoUpdatesView();
            })
            .show();
    }

    private void setupInstallationListener(String downloadId) {
        Handler handler = new Handler();

        final int MAX_CHECKS = 60;
        final int[] checkCount = {0};

        Runnable checkInstallationStatus = new Runnable() {
            @Override
            public void run() {
                checkCount[0]++;
                
                if (checkCount[0] > MAX_CHECKS) {
                    Log.w(TAG, "Timeout checking installation status");
                    return;
                }
                
                if (mUpdaterService != null) {
                    UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                    if (update != null) {
                        if (update.getStatus() == UpdateStatus.INSTALLED) {
                            // Installation completed
                            showRebootDialog();
                        } else if (update.getStatus() == UpdateStatus.INSTALLING) {
                            // Continue checking every 2 seconds
                            handler.postDelayed(this, 2000);
                        } else if (update.getStatus() == UpdateStatus.INSTALLATION_FAILED) {
                            // Installation failed
                            showSnackbar(R.string.installing_update_error, Snackbar.LENGTH_LONG);
                        }
                    } else {
                        Log.w(TAG, "Update not found, stopping verification");
                    }
                }
            }
        };
        
        // Start checking after 3 seconds
        handler.postDelayed(checkInstallationStatus, 3000);
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            showNoUpdatesView();
        } else {
            // Usar nuevo diseño
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.recycler_view).setVisibility(View.GONE);
            findViewById(R.id.update_container).setVisibility(View.VISIBLE);
            findViewById(R.id.changelog_container).setVisibility(View.VISIBLE);

            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
            updateUIForCurrentUpdate();
        }
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            //updateLastCheckedString();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
                setUpdateAvailableSetting(this, true);
            } else {
                setUpdateAvailableSetting(this, false);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    /*private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        String lastCheckString = getString(R.string.header_last_updates_check,
                StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                StringGenerator.getTimeLocalized(this, lastCheck));
        TextView headerLastCheck = findViewById(R.id.header_last_check);
        headerLastCheck.setText(lastCheckString);
    }*/

    private void handleDownloadStatusChange(String downloadId) {
        if (downloadId != null && downloadId.equals(Update.LOCAL_ID)) {
            return;
        }

        if (mUpdaterService == null) return;

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) return;
        mx.xperience.updater.model.UpdateStatus status = update.getStatus();

        switch (status) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
            case INSTALLED:
                if (!isFinishing() && !isDestroyed()) {
                    showRebootDialog();
                }
                break;
            case INSTALLATION_FAILED:
                showSnackbar(R.string.installing_update_error, Snackbar.LENGTH_LONG);
                break;
        }

        updateUIForCurrentUpdate();
    }

    /**
     * Clears the Settings.Global that MyDeviceInfoFragment (com.android.settings) reads
     * to show the "update available" card in About Phone. The update is already installed
     * and pending reboot at this point, so there's nothing left to prompt the user about
     * from that card.
     */
    private static void setUpdateAvailableSetting(Context context, boolean available) {
        try {
            Settings.Global.putInt(context.getContentResolver(), Constants.SETTING_XPE_UPDATE_AVAILABLE, available ? 1 : 0);
            context.getContentResolver().notifyChange(
                    Settings.Global.getUriFor(Constants.SETTING_XPE_UPDATE_AVAILABLE),
                    null
            );
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS, cannot write update available flag", e);
        }
    }

    private void showRebootDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.install_complete_title)
                .setMessage(R.string.install_complete_message)
                .setPositiveButton(R.string.reboot, (dialog, which) -> {
                    // Restart the device
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm != null) {
                        pm.reboot(null);
                    }
                })
                .setNegativeButton(R.string.later, (dialog, which) -> {
                     if (mUpdaterService != null) {
                        List<UpdateInfo> updates = mUpdaterService.getUpdaterController().getUpdates();
                        if (!updates.isEmpty()) {
                            updateUIForCurrentUpdate(); // This will update the button
                        }
                    }
                })
                .setCancelable(false) // The user must choose an option.
                .setOnDismissListener(dialog -> {
                    // Ensure that the UI is updated after the dialogue box is closed.
                    updateUIForCurrentUpdate();
                })
                .show();
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar.make(findViewById(R.id.main_container), stringId, duration).show();
    }

    private void refreshAnimationStart() {
        if (!mIsTV) {
            if (mRefreshIconView == null) {
                mRefreshIconView = findViewById(R.id.menu_refresh);
            }
            if (mRefreshIconView != null && mRefreshAnimation != null) {
                mRefreshAnimation.setRepeatCount(Animation.INFINITE);
                mRefreshIconView.startAnimation(mRefreshAnimation);
                mRefreshIconView.setEnabled(false);
            }
        } else {
            findViewById(R.id.update_container).setVisibility(View.GONE);
            findViewById(R.id.changelog_container).setVisibility(View.GONE);
            findViewById(R.id.no_new_updates_view).setVisibility(View.GONE);
            findViewById(R.id.refresh_progress).setVisibility(View.VISIBLE);
        }
    }

    private void refreshAnimationStop() {
        if (!mIsTV) {
            if (mRefreshIconView != null) {
                mRefreshAnimation.setRepeatCount(0);
                mRefreshIconView.setEnabled(true);
            }
        } else {
            findViewById(R.id.refresh_progress).setVisibility(View.GONE);
            List<UpdateInfo> updates = mUpdaterService != null ?
                mUpdaterService.getUpdaterController().getUpdates() : new ArrayList<>();
            if (!updates.isEmpty()) {
                findViewById(R.id.update_container).setVisibility(View.VISIBLE);
                findViewById(R.id.changelog_container).setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.no_new_updates_view).setVisibility(View.VISIBLE);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        Spinner autoCheckInterval = view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        SwitchCompat meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        if (!Utils.isABDevice()) {
            abPerfMode.setVisibility(View.GONE);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        autoCheckInterval.setSelection(Utils.getUpdateCheckSetting(this));
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            updateRecovery.setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_preferences)
                .setView(view)
                .setOnDismissListener(dialogInterface -> {
                    prefs.edit()
                            .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                                    autoCheckInterval.getSelectedItemPosition())
                            .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                            .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                                    meteredNetworkWarning.isChecked())
                            .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                            .apply();

                    if (Utils.isUpdateCheckEnabled(this)) {
                        UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
                    } else {
                        UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                        UpdatesCheckReceiver.cancelUpdatesCheck(this);
                    }

                    if (Utils.isABDevice()) {
                        boolean enableABPerfMode = abPerfMode.isChecked();
                        mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                    }
                    if (Utils.isRecoveryUpdateExecPresent()) {
                        boolean enableRecoveryUpdate = updateRecovery.isChecked();
                        SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                                String.valueOf(enableRecoveryUpdate));
                    }
                })
                .show();
    }

    private void maybeShowWelcomeMessage() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.welcome_title)
                .setMessage(R.string.welcome_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_WELCOME_MESSAGE, true)
                        .apply())
                .show();
    }
}