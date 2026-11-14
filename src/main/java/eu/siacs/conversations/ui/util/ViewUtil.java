package eu.siacs.conversations.ui.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import com.google.common.base.Strings;
import eu.siacs.conversations.R;
import eu.siacs.conversations.persistance.FileBackend;
import eu.siacs.conversations.ui.MediaViewerActivity;
import eu.siacs.conversations.utils.MimeUtils;
import java.io.File;
import java.util.Objects;

public class ViewUtil {

    public static final String WILDCARD = "*/*";

    public static void view(final Context context, final Attachment attachment) {
        view(context, attachment, null, null);
    }

    /**
     * Tatrak: with the chat's account uuid and address the in-app viewer swipes through that chat's
     * media even when the message is not loaded (media browser).
     */
    public static void view(
            final Context context,
            final Attachment attachment,
            final String account,
            final String jid) {
        File file = new File(Objects.requireNonNull(attachment.getUri().getPath()));
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        final var mime = nullToWildcard(attachment.getMime());
        if (MediaViewerActivity.canShow(mime)) {
            context.startActivity(
                    MediaViewerActivity.intent(
                            context, file, attachment.getUuid().toString(), mime, account, jid));
            return;
        }
        view(context, file, attachment.getUuid().toString(), mime);
    }

    /** Tatrak: a message's file; the in-app viewer swipes through the message's chat. */
    public static void view(
            final Context context, final File file, final eu.siacs.conversations.entities.Message message) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        final var mime = nullToWildcard(MimeUtils.getMimeType(file));
        if (MediaViewerActivity.canShow(mime)) {
            final var chat = message.getConversation();
            context.startActivity(
                    MediaViewerActivity.intent(
                            context,
                            file,
                            message.getUuid(),
                            mime,
                            chat.getAccount().getUuid(),
                            chat.getAddress().asBareJid().toString()));
            return;
        }
        view(context, file, message.getUuid(), mime);
    }

    public static void view(final Context context, final File file, final String uuid) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        final var mime = nullToWildcard(MimeUtils.getMimeType(file));
        view(context, file, uuid, mime);
    }

    public static String nullToWildcard(final String mime) {
        return Strings.isNullOrEmpty(mime) ? WILDCARD : mime;
    }

    private static void view(
            final Context context, final File file, final String uuid, final String mime) {
        // Tatrak: photos and videos open in the app's own viewer
        if (MediaViewerActivity.canShow(mime)) {
            context.startActivity(MediaViewerActivity.intent(context, file, uuid, mime));
            return;
        }
        viewExternally(context, file, uuid, mime);
    }

    /** Hands the file to another app (the viewer's "Open with…"). */
    public static void viewExternally(
            final Context context, final File file, final String uuid, final String mime) {
        final Intent openIntent = new Intent(Intent.ACTION_VIEW);
        final Uri uri;
        try {
            uri =
                    FileBackend.getUriForFile(context, file)
                            .buildUpon()
                            .appendQueryParameter("uuid", uuid)
                            .build();
        } catch (final SecurityException e) {
            Toast.makeText(
                            context,
                            context.getString(
                                    R.string.no_permission_to_access_x, file.getAbsolutePath()),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        openIntent.setDataAndType(uri, mime);
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(openIntent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT)
                    .show();
        }
    }
}
