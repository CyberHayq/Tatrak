package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.content.Context;
import android.media.MediaRecorder;
import android.os.Environment;
import androidx.test.core.app.ApplicationProvider;
import eu.siacs.conversations.R;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowEnvironment;

/** Tatrak: file_paths.xml covers the folders the app shares from, and nothing else. */
@RunWith(RobolectricTestRunner.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application.class, sdk = 35)
public class FileProviderPathsTest {

    // every public folder FileBackend.getSharedStorageLocation can choose
    private static final String[] PUBLIC_DIRECTORIES = {
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DCIM,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_AUDIOBOOKS,
        Environment.DIRECTORY_RECORDINGS,
    };

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void sharedStorageRoot() {
        am.cyberhayq.chat.testing.FileProviderCache.clear();
        // Robolectric keeps the public folders apart from the storage root; on a device they are
        // <root>/<type>, which is what FileProvider's external-path assumes
        ShadowEnvironment.setExternalStorageDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        .getParentFile()
                        .toPath());
    }

    // one test: FileProvider caches its roots per authority, and Robolectric gives each test a new root
    @Test
    public void sharesOnlyAppFolders() {
        appFoldersResolve();
        restoredBackupFoldersResolve();
        otherSharedStorageIsRejected();
    }

    private void appFoldersResolve() {
        final String appName = context.getString(R.string.app_name);
        for (final String directory : PUBLIC_DIRECTORIES) {
            assertShared(
                    new File(
                            new File(Environment.getExternalStoragePublicDirectory(directory), appName),
                            "a.jpg"));
        }
        assertShared(new File(FileBackend.getBackupDirectory(context), "x@example.com.ceb"));
        assertShared(FileBackend.getLegacyFileForFilename(context, "legacy.jpg"));
        // attachments belong to the media store and are shared by its own provider; a plain name in
        // its folder is no attachment and is shared by nobody
        final File attachments = new File(context.getFilesDir(), "attachments");
        assertEquals(
                context.getPackageName() + ".media",
                FileBackend.getUriForFile(
                                context,
                                new File(attachments, "tatrak_3f2b8a52-6f0e-4d43-9d0c-0d6a6c2b1e11_" + "ab".repeat(16) + ".jpg"))
                        .getAuthority());
        assertThrows(SecurityException.class, () -> FileBackend.getUriForFile(context, new File(attachments, "a.jpg")));
        final var cache = new FileBackend.Cache(context);
        assertShared(cache.takePicture());
        assertShared(cache.recording(MediaRecorder.OutputFormat.MPEG_4));
    }

    private void restoredBackupFoldersResolve() {
        for (final String name : new String[] {"Conversations", "monocles chat"}) {
            for (final String directory : PUBLIC_DIRECTORIES) {
                assertShared(
                        new File(
                                new File(Environment.getExternalStoragePublicDirectory(directory), name),
                                "a.jpg"));
            }
        }
        assertShared(
                new File(
                        Environment.getExternalStorageDirectory(),
                        "Conversations/Media/Conversations Images/a.jpg"));
    }

    private void otherSharedStorageIsRejected() {
        final File root = Environment.getExternalStorageDirectory();
        for (final String path :
                new String[] {
                    "secret.txt",
                    "Download/other.pdf",
                    "DCIM/Camera/IMG_1.jpg",
                    "Android/data/com.example/files/db",
                    "Download/Tatrak/../other.pdf",
                    "Download/TatrakEvil/a.jpg",
                }) {
            assertThrows(
                    path,
                    SecurityException.class,
                    () -> FileBackend.getUriForFile(context, new File(root, path)));
        }
    }

    private void assertShared(final File file) {
        assertEquals(
                file.getPath(),
                context.getPackageName() + ".files",
                FileBackend.getUriForFile(context, file).getAuthority());
    }
}
