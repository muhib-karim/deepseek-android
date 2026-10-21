/*
 * The WebView host logic is ported from dsh-android (https://github.com/yhm080315-sudo/dsh-android), commit d799c76,
 * app/src/com/dsh/android/MainActivity.java (WebView settings, onShowFileChooser that always answers its callback,
 * onConsoleMessage into the log, resource error logging).
 * Changes for DeepSeek Android: a standalone class driven by EngineActivity (no launcher console, background image or
 * theme); picked files copied into app cache with JPEG metadata (EXIF, GPS) removed; downloads and blob exports saved
 * and shared through the app; links outside the harness open in the browser; a nonce- and origin-checked bridge for
 * the Settings > Plugins "Installed plugins" section; the opaque-UI script (assets/engine/android-ui.js) injected.
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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.exifinterface.media.ExifInterface;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The harness's own web UI, full screen, made clean for a phone (see assets/engine/android-ui.js). */
public final class EngineWebView {
    public static final int REQUEST_FILE_CHOOSER = 4101;
    public static final int REQUEST_PLUGIN_FILE = 4102;
    public static final int REQUEST_PLUGIN_FOLDER = 4103;

    /** What the activity does with the page's events. */
    public interface Host {
        void onPageShown();

        void onFileSaved(File file, String mime, boolean inDownloads);

        void onMessage(String text);

        void onRestartEngine(String why);
    }

    private final Activity activity;
    private final Host host;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final String nonce;
    private volatile String origin = "";
    private volatile String pageOrigin = "";
    private ValueCallback<Uri[]> pendingFiles;
    private String script;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    public EngineWebView(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        this.nonce = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
        web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // The page is the local harness; it needs no file:// or content:// access of its own.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new Bridge(), "DsAndroid");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, length) -> io.execute(() -> download(url, contentDisposition, mimetype)));
    }

    public WebView view() {
        return web;
    }

    /** Load the harness page (http://127.0.0.1:<port>/?token=…). */
    public void load(String url) {
        Uri u = Uri.parse(url);
        origin = u.getScheme() + "://" + u.getHost() + ":" + u.getPort();
        web.loadUrl(url);
    }

    public boolean isOnHarness() {
        String current = web.getUrl();
        return current != null && current.startsWith(origin) && !origin.isEmpty();
    }

    public boolean goBack() {
        if (web.canGoBack()) {
            web.goBack();
            return true;
        }
        return false;
    }

    public void reload() {
        web.reload();
    }

    public void setStats(boolean show) {
        web.evaluateJavascript("window.__dsaSetStats && window.__dsaSetStats(" + show + ")", null);
    }

    public void destroy() {
        io.shutdownNow();
        web.destroy();
    }

    private void inject() {
        if (script == null) {
            try {
                InputStream in = activity.getAssets().open("engine/android-ui.js");
                try {
                    script = new String(PluginStore.readAll(in, 1 << 20), StandardCharsets.UTF_8);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                EngineLog.append(activity, "android-ui.js missing: " + e);
                return;
            }
        }
        String options = "{\"showStats\":" + Engine.showStats(activity) + "}";
        web.evaluateJavascript(script.replace("\"__DSA_NONCE__\"", JSONObject.quote(nonce)).replace("__DSA_OPTIONS__", options), null);
    }

    // ---------------------------------------------------------------- clients

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            String target = u.getScheme() + "://" + u.getHost() + ":" + u.getPort();
            if (target.equals(origin)) {
                return false;
            }
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme()) || "mailto".equals(u.getScheme())) {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (Exception e) {
                    host.onMessage("No app can open " + u.getHost());
                }
            }
            return true; // nothing else navigates the engine view away from the harness
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageOrigin = originOf(url);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            pageOrigin = originOf(url);
            if (pageOrigin.equals(origin)) {
                inject();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageOrigin = originOf(url);
            if (pageOrigin.equals(origin)) {
                inject();
                host.onPageShown();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                EngineLog.append(activity, "web[load-fail] " + NodeServer.redact(String.valueOf(request.getUrl())) + " code="
                        + (error == null ? "?" : error.getErrorCode() + " " + error.getDescription()));
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            EngineLog.append(activity, "web renderer gone (crash=" + detail.didCrash() + "); restarting the view");
            host.onRestartEngine(null);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (message != null && (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR || message.message().contains("[dsa]"))) {
                EngineLog.append(activity, "web[" + message.messageLevel() + "] " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
            }
            return true;
        }

        /** A WebView ignores <input type=file> unless the host answers this; the callback must always be answered. */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFiles != null) {
                pendingFiles.onReceiveValue(null);
            }
            pendingFiles = callback;
            try {
                Intent pick = params.createIntent();
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                activity.startActivityForResult(pick, REQUEST_FILE_CHOOSER);
                return true;
            } catch (Exception e) {
                EngineLog.append(activity, "file chooser failed: " + e);
                pendingFiles = null;
                return false;
            }
        }
    }

    /** Forwarded from the activity. Returns true when the request was ours. */
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            final ValueCallback<Uri[]> callback = pendingFiles;
            pendingFiles = null;
            if (callback == null) {
                return true;
            }
            if (resultCode != Activity.RESULT_OK || data == null) {
                callback.onReceiveValue(null);
                return true;
            }
            final java.util.ArrayList<Uri> picked = new java.util.ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    picked.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            io.execute(() -> {
                java.util.ArrayList<Uri> copies = new java.util.ArrayList<Uri>();
                for (Uri uri : picked) {
                    try {
                        copies.add(Uri.fromFile(copyIn(uri, new File(activity.getCacheDir(), "uploads"))));
                    } catch (IOException e) {
                        EngineLog.append(activity, "upload copy failed: " + e);
                    }
                }
                ui.post(() -> callback.onReceiveValue(copies.isEmpty() ? null : copies.toArray(new Uri[0])));
            });
            return true;
        }
        if (requestCode == REQUEST_PLUGIN_FILE || requestCode == REQUEST_PLUGIN_FOLDER) {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                finishPlugins("", null);
                return true;
            }
            final Uri uri = data.getData();
            final boolean folder = requestCode == REQUEST_PLUGIN_FOLDER;
            io.execute(() -> {
                File dir = new File(activity.getCacheDir(), "plugins/picked-" + System.nanoTime());
                try {
                    File local = folder ? copyTree(uri, dir) : copyIn(uri, dir);
                    pluginJob(() -> Engine.plugins(activity).install(local.getAbsolutePath()), "Installed");
                } catch (IOException e) {
                    finishPlugins(null, "Could not read the picked " + (folder ? "folder" : "file") + ": " + e.getMessage());
                }
            });
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- bridge

    private interface Job {
        List<PluginStore.Entry> run() throws PluginStore.PluginException;
    }

    private void pluginJob(Job job, String verb) {
        try {
            List<PluginStore.Entry> changed = job.run();
            StringBuilder names = new StringBuilder();
            for (PluginStore.Entry e : changed) {
                names.append(names.length() == 0 ? "" : ", ").append(e.name);
            }
            boolean restart = PluginStore.needsRestart(changed);
            finishPlugins(verb + " " + names + (restart ? ". Restarting the engine to load it…" : ". Reloading…"), null);
            if (restart) {
                host.onRestartEngine("Loading " + names);
            } else {
                // Skills are watched by the harness: a page reload rebuilds the "/" palette from its live registry.
                ui.postDelayed(this::reload, 1200);
            }
        } catch (PluginStore.PluginException e) {
            finishPlugins(null, e.getMessage());
        }
    }

    private void finishPlugins(String message, String error) {
        String list;
        try {
            list = Engine.plugins(activity).listJson().toString();
        } catch (JSONException e) {
            list = "[]";
        }
        final String js = "window.__dsaPluginsDone && window.__dsaPluginsDone({list:" + list + ",message:" + JSONObject.quote(message == null ? "" : message)
                + ",error:" + JSONObject.quote(error == null ? "" : error) + "})";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    /** Exposed to the harness page as window.DsAndroid. Every call needs this view's nonce and the harness origin. */
    private final class Bridge {
        private boolean allowed(String n) {
            return nonce.equals(n) && !origin.isEmpty() && origin.equals(pageOrigin);
        }

        @JavascriptInterface
        public String plugins(String n) {
            if (!allowed(n)) {
                return "[]";
            }
            try {
                return Engine.plugins(activity).listJson().toString();
            } catch (JSONException e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void install(String n, final String source) {
            if (allowed(n)) {
                io.execute(() -> pluginJob(() -> Engine.plugins(activity).install(source), "Installed"));
            }
        }

        @JavascriptInterface
        public void update(String n, final String name) {
            if (allowed(n)) {
                io.execute(() -> pluginJob(() -> Engine.plugins(activity).update(name), "Updated"));
            }
        }

        @JavascriptInterface
        public void setEnabled(String n, final String name, final boolean enabled) {
            if (allowed(n)) {
                io.execute(() -> pluginJob(() -> {
                    PluginStore store = Engine.plugins(activity);
                    store.setEnabled(name, enabled);
                    java.util.ArrayList<PluginStore.Entry> changed = new java.util.ArrayList<PluginStore.Entry>();
                    PluginStore.Entry e = store.find(name);
                    if (e != null) {
                        changed.add(e);
                    }
                    return changed;
                }, enabled ? "Enabled" : "Disabled"));
            }
        }

        @JavascriptInterface
        public void remove(String n, final String name) {
            if (allowed(n)) {
                io.execute(() -> pluginJob(() -> {
                    PluginStore store = Engine.plugins(activity);
                    PluginStore.Entry e = store.find(name);
                    store.remove(name);
                    java.util.ArrayList<PluginStore.Entry> changed = new java.util.ArrayList<PluginStore.Entry>();
                    if (e != null) {
                        changed.add(e);
                    }
                    return changed;
                }, "Removed"));
            }
        }

        @JavascriptInterface
        public void pick(String n, final String kind) {
            if (!allowed(n)) {
                return;
            }
            ui.post(() -> {
                try {
                    if ("folder".equals(kind)) {
                        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_PLUGIN_FOLDER);
                    } else {
                        activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("*/*"), REQUEST_PLUGIN_FILE);
                    }
                } catch (Exception e) {
                    finishPlugins(null, "No file picker is available on this phone.");
                }
            });
        }

        @JavascriptInterface
        public void saveDownload(String n, final String name, final String mime, final String base64) {
            if (!allowed(n)) {
                return;
            }
            io.execute(() -> {
                try {
                    byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                    File out = uniqueFile(new File(activity.getCacheDir(), "downloads"), safeName(name));
                    try (OutputStream o = new FileOutputStream(out)) {
                        o.write(bytes);
                    }
                    saved(out, mime);
                } catch (IOException | IllegalArgumentException e) {
                    host.onMessage("The download could not be saved.");
                }
            });
        }
    }

    // ---------------------------------------------------------------- files

    private void download(String url, String disposition, String mime) {
        HttpURLConnection c = null;
        try {
            if (!originOf(url).equals(origin)) {
                ui.post(() -> activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
                return;
            }
            c = (HttpURLConnection) new URL(url).openConnection();
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) {
                c.setRequestProperty("Cookie", cookies);
            }
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            File out = uniqueFile(new File(activity.getCacheDir(), "downloads"), safeName(name));
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                }
            }
            saved(out, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
        } catch (IOException e) {
            host.onMessage("The download failed.");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** Also copy into the public Downloads folder where the platform allows it without a permission (Android 10+). */
    private void saved(File file, String mime) {
        boolean inDownloads = false;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                Uri target = activity.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (target != null) {
                    try (OutputStream o = activity.getContentResolver().openOutputStream(target)) {
                        java.nio.file.Files.copy(file.toPath(), o);
                    }
                    inDownloads = true;
                }
            } catch (Exception e) {
                EngineLog.append(activity, "Downloads copy failed: " + e);
            }
        }
        final boolean done = inDownloads;
        ui.post(() -> host.onFileSaved(file, mime, done));
    }

    /** Copy a picked document into dir; JPEGs are re-encoded upright without their metadata (EXIF, GPS). */
    private File copyIn(Uri uri, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        ContentResolver cr = activity.getContentResolver();
        String name = displayName(uri);
        File out = uniqueFile(dir, safeName(name));
        try (InputStream in = cr.openInputStream(uri); OutputStream o = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("cannot open " + name);
            }
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
            }
        }
        String type = cr.getType(uri);
        String lower = out.getName().toLowerCase(Locale.US);
        if ("image/jpeg".equals(type) || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            stripJpegMetadata(out);
        }
        return out;
    }

    static void stripJpegMetadata(File jpeg) {
        try {
            int orientation = new ExifInterface(jpeg.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 4096) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeFile(jpeg.getAbsolutePath(), opts);
            if (bitmap == null) {
                return;
            }
            Matrix m = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                default: break;
            }
            Bitmap upright = m.isIdentity() ? bitmap : Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
            try (OutputStream o = new FileOutputStream(jpeg)) {
                upright.compress(Bitmap.CompressFormat.JPEG, 92, o);
            }
        } catch (IOException | RuntimeException e) {
            // an unreadable image goes as picked; the harness decides whether it can use it
        }
    }

    /** Copy a picked folder (document tree) into dir/<folder name>. */
    private File copyTree(Uri tree, File dir) throws IOException {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        String name = displayName(root);
        File out = new File(dir, safeName(name));
        copyChildren(tree, docId, out, 0);
        return out;
    }

    private void copyChildren(Uri tree, String parentId, File into, int depth) throws IOException {
        if (depth > 12) {
            return;
        }
        if (!into.isDirectory() && !into.mkdirs()) {
            throw new IOException("cannot create " + into);
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = activity.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String id = c.getString(0);
                String childName = safeName(c.getString(1));
                if (childName.equals(".git") || childName.equals("node_modules")) {
                    continue;
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    copyChildren(tree, id, new File(into, childName), depth + 1);
                } else {
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    try (InputStream in = activity.getContentResolver().openInputStream(doc);
                         OutputStream o = new FileOutputStream(new File(into, childName))) {
                        if (in == null) {
                            continue;
                        }
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            o.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // fall back to the path
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last.substring(last.lastIndexOf('/') + 1);
    }

    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[/\\\\\\x00-\\x1f:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "file";
        }
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

    static File uniqueFile(File dir, String name) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; f.exists(); i++) {
            f = new File(dir, stem + " (" + i + ")" + ext);
        }
        return f;
    }

    private static String originOf(String url) {
        if (url == null) {
            return "";
        }
        Uri u = Uri.parse(url);
        return u.getScheme() + "://" + u.getHost() + ":" + u.getPort();
    }
}
