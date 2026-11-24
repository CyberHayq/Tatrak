package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.databinding.DataBindingUtil;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityImportBackupBinding;
import eu.siacs.conversations.databinding.DialogEnterPasswordBinding;
import eu.siacs.conversations.services.QuickConversationsService;
import eu.siacs.conversations.ui.adapter.BackupFileAdapter;
import eu.siacs.conversations.utils.BackupFile;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.worker.ImportBackupWorker;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ImportBackupActivity extends ActionBarActivity
        implements BackupFileAdapter.OnItemClickedListener {

    private ActivityImportBackupBinding binding;

    private BackupFileAdapter backupFileAdapter;

    private LiveData<Boolean> inProgressImport;
    private Uri currentRestoreDialog;
    // Tatrak: the dialog's OMEMO choice, kept across recreation
    private ImportBackupWorker.OmemoImport currentRestoreOmemo = ImportBackupWorker.OmemoImport.FULL;
    private UUID currentWorkRequest;

    private final ActivityResultLauncher<String[]> requestPermissions =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        if (results.containsValue(Boolean.TRUE)) {
                            loadBackupFiles();
                        }
                    });

    private final ActivityResultLauncher<String> openBackup =
            registerForActivityResult(
                    new ActivityResultContracts.GetContent(),
                    uri -> openBackupFileFromUri(uri, false));

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_import_backup);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);

        final var workManager = WorkManager.getInstance(this);
        final var imports =
                workManager.getWorkInfosByTagLiveData(ImportBackupWorker.TAG_IMPORT_BACKUP);
        this.inProgressImport =
                Transformations.map(
                        imports, infos -> Iterables.any(infos, i -> !i.getState().isFinished()));

        this.inProgressImport.observe(
                this, inProgress -> setLoadingState(Boolean.TRUE.equals(inProgress)));
        // Tatrak: status line under the spinner (reading percentage, then saving)
        imports.observe(this, infos -> {
            final var running = Iterables.tryFind(infos, i -> !i.getState().isFinished());
            final String status = running.isPresent()
                    ? ImportBackupWorker.status(this, running.get().getProgress()) : null;
            binding.tatrakRestoreStatus.setText(status == null ? getString(R.string.restoring_backup) : status);
        });

        if (savedInstanceState != null) {
            final var currentWorkRequest = savedInstanceState.getString("current-work-request");
            if (currentWorkRequest != null) {
                this.currentWorkRequest = UUID.fromString(currentWorkRequest);
            }
            final var currentRestoreDialog = savedInstanceState.getString("current-restore-dialog");
            if (currentRestoreDialog != null) {
                this.currentRestoreDialog = Uri.parse(currentRestoreDialog);
            }
            this.currentRestoreOmemo = ImportBackupWorker.OmemoImport.of(
                    savedInstanceState.getString("current-restore-omemo"));
        }
        monitorWorkRequest(this.currentWorkRequest);

        this.backupFileAdapter = new BackupFileAdapter();
        this.binding.list.setAdapter(this.backupFileAdapter);
        this.backupFileAdapter.setOnItemClickedListener(this);
        if (this.currentRestoreDialog != null) {
            openBackupFileFromUri(this.currentRestoreDialog, false, this.currentRestoreOmemo);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.import_backup, menu);
        final MenuItem openBackup = menu.findItem(R.id.action_open_backup_file);
        final var inProgress =
                this.inProgressImport == null ? null : this.inProgressImport.getValue();
        openBackup.setVisible(!Boolean.TRUE.equals(inProgress));
        return true;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle bundle) {
        if (this.currentWorkRequest != null) {
            bundle.putString("current-work-request", this.currentWorkRequest.toString());
        }
        if (this.currentRestoreDialog != null) {
            bundle.putString("current-restore-dialog", this.currentRestoreDialog.toString());
            bundle.putString("current-restore-omemo", this.currentRestoreOmemo.name());
        }
        super.onSaveInstanceState(bundle);
    }

    @Override
    public void onStart() {
        super.onStart();

        final var intent = getIntent();
        final var action = intent == null ? null : intent.getAction();
        final var data = intent == null ? null : intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            // Tatrak: another app must not make Tatrak read its own private files as a backup
            if (isOwnData(this, data)) {
                showBackupThrowable(new java.io.FileNotFoundException(data.toString()));
                return;
            }
            openBackupFileFromUri(data, true);
            return;
        }

        final List<String> desiredPermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            desiredPermission =
                    ImmutableList.of(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO,
                            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            desiredPermission =
                    ImmutableList.of(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            desiredPermission = ImmutableList.of(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        final Set<String> declaredPermission = getDeclaredPermission();
        if (declaredPermission.containsAll(desiredPermission)) {
            requestPermissions.launch(desiredPermission.toArray(new String[0]));
        } else {
            Log.d(Config.LOGTAG, "Manifest is lacking some desired permission. not requesting");
        }
    }

    private Set<String> getDeclaredPermission() {
        final String[] permissions;
        try {
            permissions =
                    getPackageManager()
                            .getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS)
                            .requestedPermissions;
        } catch (final PackageManager.NameNotFoundException e) {
            return Collections.emptySet();
        }
        if (permissions == null) {
            return Collections.emptySet();
        }
        return ImmutableSet.copyOf(permissions);
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    private void loadBackupFiles() {
        final var future = BackupFile.listAsync(getApplicationContext());
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(List<BackupFile> files) {
                        runOnUiThread(() -> backupFileAdapter.setFiles(files));
                    }

                    @Override
                    public void onFailure(@NonNull Throwable t) {}
                },
                ContextCompat.getMainExecutor(getApplication()));
    }

    @Override
    public void onClick(final BackupFile backupFile) {
        showEnterPasswordDialog(backupFile, false, ImportBackupWorker.OmemoImport.FULL);
    }

    /**
     * Tatrak: whether a URI handed in by another app points into Tatrak itself: one of its own
     * content providers, a file in its private data directories, or another scheme. Only
     * content:// of other apps and file:// outside those directories may be read as a backup.
     */
    @androidx.annotation.VisibleForTesting
    static boolean isOwnData(final android.content.Context context, final Uri uri) {
        final String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            final String authority = uri.getAuthority();
            if (authority == null || authority.isEmpty()) return true;
            final var provider = context.getPackageManager().resolveContentProvider(authority, 0);
            return provider != null && context.getPackageName().equals(provider.packageName);
        }
        if (!"file".equals(scheme) || uri.getPath() == null) return true;
        try {
            final String path = new java.io.File(uri.getPath()).getCanonicalPath();
            final var info = context.getApplicationInfo();
            for (final String dir : new String[] {info.dataDir, info.deviceProtectedDataDir}) {
                if (dir == null) continue;
                final String own = new java.io.File(dir).getCanonicalPath();
                if (path.equals(own) || path.startsWith(own + java.io.File.separator)) return true;
            }
            return false;
        } catch (final IOException e) {
            return true;
        }
    }

    private void openBackupFileFromUri(final Uri uri, final boolean finishOnCancel) {
        openBackupFileFromUri(uri, finishOnCancel, ImportBackupWorker.OmemoImport.FULL);
    }

    // Tatrak: omemoImport restores the choice when the dialog asks for a lost password again
    private void openBackupFileFromUri(
            final Uri uri, final boolean finishOnCancel, final ImportBackupWorker.OmemoImport omemoImport) {
        final var backupFileFuture = BackupFile.readAsync(this, uri);
        Futures.addCallback(
                backupFileFuture,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(final BackupFile backupFile) {
                        if (QuickConversationsService.isQuicksy()) {
                            if (!backupFile
                                    .getHeader()
                                    .getJid()
                                    .getDomain()
                                    .equals(Config.QUICKSY_DOMAIN)) {
                                Snackbar.make(
                                                binding.coordinator,
                                                R.string.non_quicksy_backup,
                                                Snackbar.LENGTH_LONG)
                                        .show();
                                return;
                            }
                        }
                        showEnterPasswordDialog(backupFile, finishOnCancel, omemoImport);
                    }

                    @Override
                    public void onFailure(@NonNull final Throwable throwable) {
                        Log.d(Config.LOGTAG, "could not open backup file " + uri, throwable);
                        showBackupThrowable(throwable);
                    }
                },
                ContextCompat.getMainExecutor(getApplication()));
    }

    private void showBackupThrowable(final Throwable throwable) {
        if (throwable instanceof BackupFileHeader.OutdatedBackupFileVersion) {
            Snackbar.make(
                            binding.coordinator,
                            R.string.outdated_backup_file_format,
                            Snackbar.LENGTH_LONG)
                    .show();
        } else if (throwable instanceof IOException
                || throwable instanceof IllegalArgumentException) {
            Snackbar.make(binding.coordinator, R.string.not_a_backup_file, Snackbar.LENGTH_LONG)
                    .show();
        } else if (throwable instanceof SecurityException e) {
            Log.d(Config.LOGTAG, "not able to parse backup file", e);
            Snackbar.make(
                            binding.coordinator,
                            R.string.sharing_application_not_grant_permission,
                            Snackbar.LENGTH_LONG)
                    .show();
        }
    }

    private void showEnterPasswordDialog(
            final BackupFile backupFile, final boolean finishOnCancel, final ImportBackupWorker.OmemoImport omemoImport) {
        this.currentRestoreDialog = backupFile.getUri();
        final DialogEnterPasswordBinding enterPasswordBinding =
                DataBindingUtil.inflate(
                        LayoutInflater.from(this), R.layout.dialog_enter_password, null, false);
        Log.d(Config.LOGTAG, "attempting to import " + backupFile.getUri());
        enterPasswordBinding.explain.setText(
                getString(
                        R.string.enter_password_to_restore,
                        backupFile.getHeader().getJid().toString()));
        final MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        // Tatrak: full screen, so the password field stays in view below the three OMEMO choices
        builder.setBackgroundInsetTop(0).setBackgroundInsetBottom(0).setBackgroundInsetStart(0).setBackgroundInsetEnd(0);
        this.currentRestoreOmemo = omemoImport;
        enterPasswordBinding.omemoImport.check(omemoButton(omemoImport));
        enterPasswordBinding.omemoImport.setOnCheckedChangeListener(
                (group, checked) -> this.currentRestoreOmemo = omemoImport(checked));
        builder.setView(enterPasswordBinding.getRoot());
        builder.setTitle(R.string.enter_password);
        builder.setNegativeButton(
                R.string.cancel,
                (dialog, which) -> {
                    this.currentRestoreDialog = null;
                    if (finishOnCancel) {
                        finish();
                    }
                });
        builder.setPositiveButton(R.string.restore, null);
        builder.setCancelable(false);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener((d) -> onDialogShow(backupFile, d, enterPasswordBinding));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void onDialogShow(
            final BackupFile backupFile,
            final DialogInterface d,
            final DialogEnterPasswordBinding enterPasswordBinding) {
        if (d instanceof AlertDialog alertDialog) {
            alertDialog
                    .getButton(DialogInterface.BUTTON_POSITIVE)
                    .setOnClickListener(v -> onRestoreClick(backupFile, d, enterPasswordBinding));
        }
    }

    private void onRestoreClick(
            final BackupFile backupFile,
            final DialogInterface d,
            final DialogEnterPasswordBinding enterPasswordBinding) {
        final String password = enterPasswordBinding.accountPassword.getEditableText().toString();
        if (password.isEmpty()) {
            enterPasswordBinding.accountPasswordLayout.setError(
                    getString(R.string.please_enter_password));
            return;
        }

        importBackup(backupFile, password,
                omemoImport(enterPasswordBinding.omemoImport.getCheckedRadioButtonId()));
        // Tatrak: the dialog is done; a recreated activity must not show it again over the import
        this.currentRestoreDialog = null;
        d.dismiss();
    }

    // Tatrak: the dialog's radio buttons for the worker's OMEMO modes
    private static int omemoButton(final ImportBackupWorker.OmemoImport omemoImport) {
        return switch (omemoImport) {
            case FULL -> R.id.omemo_full;
            case TRUST -> R.id.omemo_trust;
            case NONE -> R.id.omemo_none;
        };
    }

    private static ImportBackupWorker.OmemoImport omemoImport(final int button) {
        if (button == R.id.omemo_trust) return ImportBackupWorker.OmemoImport.TRUST;
        if (button == R.id.omemo_none) return ImportBackupWorker.OmemoImport.NONE;
        return ImportBackupWorker.OmemoImport.FULL;
    }

    private void importBackup(
            final BackupFile backupFile, final String password, final ImportBackupWorker.OmemoImport omemoImport) {
        final var data = ImportBackupWorker.data(password, backupFile.getUri(), omemoImport);
        final OneTimeWorkRequest importBackupWorkRequest;
        // Tatrak: drop the in-memory password if the request never gets enqueued
        try {
            importBackupWorkRequest =
                    new OneTimeWorkRequest.Builder(ImportBackupWorker.class)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .setInputData(data)
                            .addTag(ImportBackupWorker.TAG_IMPORT_BACKUP)
                            .build();
            WorkManager.getInstance(this).enqueue(importBackupWorkRequest);
        } catch (final RuntimeException e) {
            ImportBackupWorker.takePassword(data);
            throw e;
        }

        final var id = importBackupWorkRequest.getId();
        this.currentWorkRequest = id;
        monitorWorkRequest(id);
    }

    private void monitorWorkRequest(final UUID uuid) {
        if (uuid == null) {
            return;
        }
        Log.d(Config.LOGTAG, "monitorWorkRequest(" + uuid + ")");
        final var workInfoLiveData = WorkManager.getInstance(this).getWorkInfoByIdLiveData(uuid);
        workInfoLiveData.observe(
                this,
                workInfo -> {
                    final var state = workInfo.getState();
                    final int skippedRows = ImportBackupWorker.skippedRows(workInfo.getOutputData());
                    // Tatrak: retain the work id until the partial-restore warning is acknowledged
                    if (state.isFinished()
                            && !(state == WorkInfo.State.SUCCEEDED && skippedRows > 0)) {
                        this.currentWorkRequest = null;
                    }
                    if (state == WorkInfo.State.FAILED) {
                        final var data = workInfo.getOutputData();
                        final var reason =
                                ImportBackupWorker.Reason.valueOfOrGeneric(
                                        data.getString("reason"));
                        switch (reason) {
                            case DECRYPTION_FAILED -> onBackupDecryptionFailed();
                            case ACCOUNT_ALREADY_EXISTS -> onAccountAlreadySetup();
                            // Tatrak: the password only lived in memory; ask for it again
                            case PASSWORD_MISSING -> {
                                final var uri = ImportBackupWorker.uri(data);
                                if (uri != null) {
                                    openBackupFileFromUri(
                                            uri, false, ImportBackupWorker.omemoImport(data));
                                }
                            }
                            // Tatrak: the restore can be retried without the device's own OMEMO keys
                            case UNSAFE_OMEMO_KEYS -> Snackbar.make(binding.coordinator,
                                    R.string.tatrak_backup_unsafe_omemo_keys, Snackbar.LENGTH_LONG).show();
                            default -> onBackupRestoreFailed();
                        }
                    } else if (state == WorkInfo.State.SUCCEEDED) {
                        final String preview = ImportBackupWorker.previewId(workInfo.getOutputData());
                        if (preview != null) {
                            this.currentWorkRequest = uuid;
                            showImportPreview(preview);
                        } else if (skippedRows > 0) {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle(R.string.tatrak_backup_partial_title)
                                    .setMessage(
                                            getString(
                                                    R.string.tatrak_backup_partial_summary,
                                                    skippedRows))
                                    .setPositiveButton(
                                            R.string.ok, (dialog, which) -> onBackupRestored())
                                    .setCancelable(false)
                                    .show();
                        } else {
                            onBackupRestored();
                        }
                    }
                });
    }

    // Tatrak: authenticated encrypted staging is the source of this consent, not a mutable URI.
    private void showImportPreview(final String id) {
        setLoadingState(false);
        final String omissions;
        try { omissions = ImportBackupWorker.previewDetails(this, id); }
        catch (RuntimeException e) { onBackupRestoreFailed(); return; }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tatrak_backup_preview_title)
                .setMessage(omissions.isEmpty() ? getString(R.string.tatrak_backup_preview_clean)
                        : getString(R.string.tatrak_backup_preview_omissions, omissions))
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    this.currentWorkRequest = null;
                    java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                        try { am.cyberhayq.chat.storage.DatabaseStorage.discardImport(this, id); }
                        catch (Exception ignored) { }
                    });
                })
                .setPositiveButton(R.string.tatrak_backup_accept, (dialog, which) -> {
                    final var request = new OneTimeWorkRequest.Builder(ImportBackupWorker.class)
                            .setInputData(ImportBackupWorker.commitData(id))
                            .addTag(ImportBackupWorker.TAG_IMPORT_BACKUP).build();
                    WorkManager.getInstance(this).enqueue(request);
                    this.currentWorkRequest = request.getId();
                    monitorWorkRequest(request.getId());
                }).setCancelable(false).show();
    }

    private void setLoadingState(final boolean loadingState) {
        Log.d(Config.LOGTAG, "setLoadingState(" + loadingState + ")");
        binding.coordinator.setVisibility(loadingState ? View.GONE : View.VISIBLE);
        binding.inProgress.setVisibility(loadingState ? View.VISIBLE : View.GONE);
        setTitle(loadingState ? R.string.restoring_backup : R.string.restore_backup);
        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        configureActionBar(getSupportActionBar(), !loadingState);
        invalidateOptionsMenu();
    }

    private void onAccountAlreadySetup() {
        Snackbar.make(binding.coordinator, R.string.account_already_setup, Snackbar.LENGTH_LONG)
                .show();
    }

    private void onBackupRestored() {
        this.currentWorkRequest = null;
        final var intent = new Intent(this, ConversationActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void onBackupDecryptionFailed() {
        Snackbar.make(binding.coordinator, R.string.unable_to_decrypt_backup, Snackbar.LENGTH_LONG)
                .show();
    }

    private void onBackupRestoreFailed() {
        Snackbar.make(binding.coordinator, R.string.unable_to_restore_backup, Snackbar.LENGTH_LONG)
                .show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_open_backup_file) {
            this.openBackup.launch("*/*");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
