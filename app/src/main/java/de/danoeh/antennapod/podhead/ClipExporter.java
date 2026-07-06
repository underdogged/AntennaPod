package de.danoeh.antennapod.podhead;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Writes a PodHead "clip" record — the exact episode + playback position the
 * user marked — plus (once per episode) a copy of the exact downloaded audio,
 * into the public <b>Download/PodHead/</b> folder so Syncthing can carry both to
 * the laptop. Because this app played the audio itself, the position maps 1:1
 * onto that file: no ad-insertion drift.
 *
 * Whole episodes are copied for now; a later "prune" pass can delete old audio
 * once PodHead has verified the clips against it.
 */
public final class ClipExporter {
    private static final String TAG = "ClipExporter";
    public static final String CLIP_DIR = "PodHead";
    public static final String AUDIO_SUBDIR = "PodHead/audio";
    private static final String PREFS = "podhead_clips";
    private static final String KEY_COPIED = "copied_audio";

    private ClipExporter() {
    }

    /**
     * @param localAudioPath the downloaded episode's file path, or null if the
     *                       episode was streamed (then no exact audio exists).
     */
    public static void writeClip(Context context, String feedUrl, String episodeGuid,
                                 String episodeTitle, String podcast, int positionMs,
                                 long capturedAt, String localAudioPath,
                                 String mark, int backSeconds) throws Exception {
        String audioFile = null;
        if (localAudioPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                audioFile = copyEpisodeAudio(context, localAudioPath, episodeGuid, capturedAt);
            } catch (Exception e) {
                Log.e(TAG, "Audio copy failed (record still written)", e);
            }
        }

        JSONObject json = new JSONObject();
        json.put("podcast", podcast == null ? JSONObject.NULL : podcast);
        json.put("episodeTitle", episodeTitle == null ? JSONObject.NULL : episodeTitle);
        json.put("episodeGuid", episodeGuid == null ? JSONObject.NULL : episodeGuid);
        json.put("feedUrl", feedUrl == null ? JSONObject.NULL : feedUrl);
        json.put("positionMs", positionMs);
        json.put("capturedAt", capturedAt);
        json.put("audioFile", audioFile == null ? JSONObject.NULL : audioFile);
        // how far back the interesting bit was, so PodHead biases the window
        json.put("mark", mark == null ? "now" : mark);
        json.put("focusOffsetSec", backSeconds);
        json.put("source", "podhead-player");
        byte[] bytes = json.toString(2).getBytes(StandardCharsets.UTF_8);
        String fileName = "clip-" + capturedAt + ".json";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try (OutputStream os = openPublicDownload(context, CLIP_DIR, fileName, "application/json")) {
                os.write(bytes);
            }
        } else {
            writeToAppDir(context, fileName, bytes);
        }
        Log.d(TAG, "Wrote clip " + fileName + " @" + positionMs + "ms audio=" + audioFile);
    }

    /** Copy the exact downloaded episode into Download/PodHead/audio, once per episode. */
    private static String copyEpisodeAudio(Context context, String localAudioPath,
                                           String episodeGuid, long capturedAt) throws Exception {
        String ext = extensionOf(localAudioPath);
        String key = sanitize(episodeGuid != null ? episodeGuid : String.valueOf(capturedAt));
        String audioName = "episode-" + key + ext;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> copied = new HashSet<>(prefs.getStringSet(KEY_COPIED, new HashSet<>()));
        if (copied.contains(audioName)) {
            Log.d(TAG, "Episode audio already synced: " + audioName);
            return audioName;
        }

        try (InputStream in = openSource(context, localAudioPath);
             OutputStream out = openPublicDownload(context, AUDIO_SUBDIR, audioName, mimeOf(ext))) {
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            Log.d(TAG, "Copied episode audio " + audioName + " (" + total + " bytes)");
        }
        copied.add(audioName);
        prefs.edit().putStringSet(KEY_COPIED, copied).apply();
        return audioName;
    }

    private static InputStream openSource(Context context, String path) throws Exception {
        if (path.startsWith("content://")) {
            return context.getContentResolver().openInputStream(Uri.parse(path));
        }
        return new FileInputStream(path.startsWith("file://") ? Uri.parse(path).getPath() : path);
    }

    /** Android 10+: create a file in public Download/<subPath> and return its OutputStream. */
    private static OutputStream openPublicDownload(Context context, String subPath,
                                                   String fileName, String mime) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subPath);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new Exception("MediaStore insert returned null for " + fileName);
        }
        OutputStream os = resolver.openOutputStream(uri);
        if (os == null) {
            throw new Exception("Could not open output stream for " + uri);
        }
        return os;
    }

    /** Pre-Android 10 fallback: app-specific external files dir (record only). */
    private static void writeToAppDir(Context context, String fileName, byte[] bytes)
            throws Exception {
        File dir = new File(context.getExternalFilesDir(null), CLIP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Could not create clip dir " + dir);
        }
        try (FileOutputStream fos = new FileOutputStream(new File(dir, fileName));
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String extensionOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && name.length() - dot <= 6) {
            return name.substring(dot).toLowerCase();
        }
        return ".mp3";
    }

    private static String mimeOf(String ext) {
        switch (ext) {
            case ".m4a": case ".mp4": case ".aac": return "audio/mp4";
            case ".opus": return "audio/opus";
            case ".ogg": case ".oga": return "audio/ogg";
            case ".wav": return "audio/wav";
            case ".flac": return "audio/flac";
            default: return "audio/mpeg";
        }
    }

    private static String sanitize(String s) {
        String out = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return out.length() > 60 ? out.substring(0, 60) : (out.isEmpty() ? "clip" : out);
    }
}
