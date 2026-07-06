package de.danoeh.antennapod.podhead;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Writes a PodHead "clip" record — the exact episode + playback position the
 * user marked — as a JSON file. Because this app downloaded and played the
 * audio itself, the position maps 1:1 onto the file: no ad-insertion drift.
 *
 * Phase 1: records land in the app's external files dir (PodHeadClips/), which
 * is enough to prove capture and to pull off the emulator for verification.
 * Phase 2 will move this to a public, Syncthing-visible folder.
 */
public final class ClipExporter {
    private static final String TAG = "ClipExporter";
    public static final String CLIP_DIR = "PodHeadClips";

    private ClipExporter() {
    }

    public static File writeClip(Context context, String feedUrl, String episodeGuid,
                                 String episodeTitle, String podcast, int positionMs,
                                 long capturedAt) throws Exception {
        File dir = new File(context.getExternalFilesDir(null), CLIP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Could not create clip dir " + dir);
        }
        JSONObject json = new JSONObject();
        json.put("podcast", podcast == null ? JSONObject.NULL : podcast);
        json.put("episodeTitle", episodeTitle == null ? JSONObject.NULL : episodeTitle);
        json.put("episodeGuid", episodeGuid == null ? JSONObject.NULL : episodeGuid);
        json.put("feedUrl", feedUrl == null ? JSONObject.NULL : feedUrl);
        json.put("positionMs", positionMs);
        json.put("capturedAt", capturedAt);
        json.put("source", "podhead-player");

        File out = new File(dir, "clip-" + capturedAt + ".json");
        try (FileOutputStream fos = new FileOutputStream(out);
             OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            writer.write(json.toString(2));
        }
        Log.d(TAG, "Wrote clip " + out + " @" + positionMs + "ms");
        return out;
    }
}
