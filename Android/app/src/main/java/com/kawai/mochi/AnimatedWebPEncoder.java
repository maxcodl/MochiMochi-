package com.kawai.mochi;

import android.content.Context;
import android.graphics.Bitmap;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.Session;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

final class AnimatedWebPEncoder {
    private static final int[] QUALITY_LADDER = {80, 70, 60, 50, 40, 30, 20};

    private AnimatedWebPEncoder() {
    }

    static byte[] encode(Context context, List<Bitmap> frames, int frameDurationMs, long maxBytes) throws IOException {
        if (frames == null || frames.isEmpty()) {
            throw new IOException("No frames to encode");
        }

        File cacheDir = context.getCacheDir();
        File workDir = new File(cacheDir, "tg_webp_" + System.currentTimeMillis());
        if (!workDir.mkdirs() && !workDir.exists()) {
            throw new IOException("Failed to create temp directory");
        }

        try {
            writeFrames(frames, workDir);
            double fps = Math.max(1.0, 1000.0 / Math.max(1, frameDurationMs));

            for (int quality : QUALITY_LADDER) {
                File outFile = new File(workDir, "out.webp");
                if (outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }

                String cmd = buildCommand(workDir, fps, quality, outFile);
                Session session = FFmpegKit.execute(cmd);
                if (!ReturnCode.isSuccess(session.getReturnCode())) {
                    continue;
                }

                byte[] encoded = readAllBytes(outFile);
                if (encoded.length > 0 && encoded.length <= maxBytes) {
                    return encoded;
                }
            }

            throw new IOException("FFmpeg encode failed to meet size limits");
        } finally {
            deleteRecursive(workDir);
        }
    }

    private static void writeFrames(List<Bitmap> frames, File workDir) throws IOException {
        for (int i = 0; i < frames.size(); i++) {
            File frameFile = new File(workDir, String.format(Locale.US, "frame_%04d.png", i));
            try (FileOutputStream out = new FileOutputStream(frameFile)) {
                if (!frames.get(i).compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("Failed to encode frame " + i);
                }
            }
        }
    }

    private static String buildCommand(File workDir, double fps, int quality, File outFile) {
        String input = new File(workDir, "frame_%04d.png").getAbsolutePath();
        return String.format(
                Locale.US,
                "-y -framerate %.3f -i \"%s\" -c:v libwebp -loop 0 -pix_fmt yuva420p -quality %d -compression_level 6 -an -vsync 0 \"%s\"",
                fps,
                input,
                quality,
                outFile.getAbsolutePath()
        );
    }

    private static byte[] readAllBytes(File file) throws IOException {
        if (file == null || !file.exists()) return new byte[0];
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
