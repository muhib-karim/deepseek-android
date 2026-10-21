/*
 * Ported from dsh-android (https://github.com/yhm080315-sudo/dsh-android), commit d799c76, app/src/com/dsh/android/FileBridge.java.
 * Changes for DeepSeek Android: package name; authority from the application id; only files
 * inside the app's own files and cache directories are served (a granted URI can no longer name any path).
 *
 * MIT License
 *
 * Copyright (c) 2026 DSH for Android contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.dsandroid.engine;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves a file read-only as a content:// URI so another app can open it.
 *
 * Android 7+ refuses file:// URIs in an outgoing Intent (FileUriExposedException),
 * so handing a file to a viewer requires a provider. The authority is declared in
 * the manifest with exported="false" and grantUriPermissions="true": only URIs
 * handed out through an intent grant are readable, and only for as long as the
 * receiving app is in the foreground.
 */
public class FileBridge extends ContentProvider {


    /** content:// URI for an absolute path. */
    public static Uri uriFor(android.content.Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".engine.files")
                .path(file.getAbsolutePath())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) {
            throw new FileNotFoundException("no path in " + uri);
        }
        File file = new File(path);
        if (!inside(file)) {
            throw new FileNotFoundException("not an app file: " + path);
        }
        if (!file.isFile()) {
            throw new FileNotFoundException("not a file: " + path);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Only the app's own storage: harness workspace, downloads staged in cache. */
    private boolean inside(File file) {
        try {
            String path = file.getCanonicalPath();
            android.content.Context c = getContext();
            if (c == null) {
                return false;
            }
            for (File root : new File[]{c.getFilesDir(), c.getCacheDir()}) {
                String r = root.getCanonicalPath();
                if (path.startsWith(r + File.separator)) {
                    return true;
                }
            }
        } catch (java.io.IOException ignored) {
            // unresolvable: refuse
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        String path = uri.getPath();
        if (path == null) {
            return "application/octet-stream";
        }
        String lower = path.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
