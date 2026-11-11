package com.abema.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "127.0.0.1";
    private static final String ORIGIN = "http://" + HOST + ":" + Engine.PORT;
    private static final int REQ_FILES = 11;
    private static final int REQ_PERM = 12;

    private WebView web;
    private Engine engine;
    private ValueCallback<Uri[]> fileCallback;
    private TextToSpeech tts;
    private volatile boolean ttsOk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        engine = Engine.get(this);
        try {
            tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override public void onInit(int status) { ttsOk = status == TextToSpeech.SUCCESS; }
            });
        } catch (Exception e) {
            tts = null;
        }
        getWindow().setStatusBarColor(0xFF070B12);
        getWindow().setNavigationBarColor(0xFF070B12);
        web = new WebView(this);
        web.setBackgroundColor(0xFF070B12);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "JarvisApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        if (state == null) web.loadUrl(ORIGIN + "/");
        else web.restoreState(state);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) engine.stop();
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private File root() {
        return new File(Environment.getExternalStorageDirectory(), "Jarvis");
    }

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
        }
    }

    private void ensureDirs() {
        if (!hasAccess()) return;
        new File(root(), "models").mkdirs();
        new File(root(), "notes").mkdirs();
    }

    /* ---------- ответы приложения на запросы интерфейса /app/... ---------- */

    private JSONObject handleApp(Uri u) throws Exception {
        String path = u.getPath();
        JSONObject o = new JSONObject();
        if ("/app/status".equals(path)) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            o.put("app", true);
            o.put("perm", hasAccess());
            o.put("state", engine.state());
            o.put("model", engine.model());
            o.put("totalMem", mi.totalMem);
            o.put("availMem", mi.availMem);
            o.put("cores", Runtime.getRuntime().availableProcessors());
            JSONArray lg = new JSONArray();
            for (String line : engine.logTail()) lg.put(line);
            o.put("log", lg);
        } else if ("/app/models".equals(path)) {
            ensureDirs();
            JSONArray arr = new JSONArray();
            File[] fs = hasAccess() ? new File(root(), "models").listFiles() : null;
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    String n = f.getName().toLowerCase();
                    if (!f.isFile() || !n.endsWith(".gguf") || n.startsWith("mmproj") || n.endsWith(".mmproj.gguf")) continue;
                    JSONObject m = new JSONObject();
                    m.put("name", f.getName());
                    m.put("size", f.length());
                    arr.put(m);
                }
            }
            o.put("models", arr);
        } else if ("/app/start".equals(path)) {
            String name = u.getQueryParameter("name");
            int c = num(u.getQueryParameter("ctx"), 8192, 512, 262144);
            int t = num(u.getQueryParameter("threads"), 4, 1, 32);
            File f = name == null ? null : new File(new File(root(), "models"), new File(name).getName());
            if (f == null || !f.isFile()) {
                o.put("error", new JSONObject().put("message", "файл модели не найден"));
            } else {
                engine.start(f, c, t);
                o.put("ok", true);
            }
        } else if ("/app/stop".equals(path)) {
            engine.stop();
            o.put("ok", true);
        } else if ("/app/grant".equals(path)) {
            runOnUiThread(new Runnable() {
                @Override public void run() { askAccess(); }
            });
            o.put("ok", true);
        } else {
            o.put("error", new JSONObject().put("message", "неизвестная команда"));
        }
        return o;
    }

    private static int num(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s);
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }

    private static WebResourceResponse respond(String mime, int code, InputStream body) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "utf-8", code, code == 200 ? "OK" : "Error", h, body);
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !HOST.equals(u.getHost()) || u.getPort() != Engine.PORT) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            try {
                if ("/".equals(path) || "/index.html".equals(path)) {
                    return respond("text/html", 200, getAssets().open("ui/index.html"));
                }
                if (path.startsWith("/app/")) {
                    JSONObject o = handleApp(u);
                    int code = o.has("error") ? 400 : 200;
                    return respond("application/json", code, new ByteArrayInputStream(o.toString().getBytes("UTF-8")));
                }
            } catch (Exception e) {
                try {
                    String msg = new JSONObject().put("error", new JSONObject().put("message", String.valueOf(e))).toString();
                    return respond("application/json", 500, new ByteArrayInputStream(msg.getBytes("UTF-8")));
                } catch (Exception ignored) {
                }
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u != null && HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(Intent.createChooser(i, "Файлы для библиотеки"), REQ_FILES);
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null && cd.getItemCount() > 0) {
                out = new Uri[cd.getItemCount()];
                for (int i = 0; i < out.length; i++) out[i] = cd.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    private List<Voice> voiceList() {
        List<Voice> out = new ArrayList<Voice>();
        if (tts == null || !ttsOk) return out;
        try {
            Set<Voice> all = tts.getVoices();
            if (all != null) {
                for (Voice v : all) {
                    if (v == null || v.getLocale() == null) continue;
                    String lang = v.getLocale().getLanguage();
                    if ("ru".equals(lang) || "en".equals(lang)) out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                int ra = "ru".equals(a.getLocale().getLanguage()) ? 0 : 1;
                int rb = "ru".equals(b.getLocale().getLanguage()) ? 0 : 1;
                if (ra != rb) return ra - rb;
                return a.getName().compareTo(b.getName());
            }
        });
        return out;
    }

    /** Мост для интерфейса: заметки файлами в Jarvis/notes и голос телефона. */
    private final class Bridge {
        @JavascriptInterface
        public String voices() {
            try {
                JSONArray arr = new JSONArray();
                for (Voice v : voiceList()) {
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    String where = v.isNetworkConnectionRequired() ? "нужен интернет" : "офлайн";
                    o.put("label", v.getLocale().getDisplayName(new Locale("ru")) + " · " + v.getName() + " · " + where);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public boolean speak(String text, String voice, double rate, double pitch) {
            if (tts == null || !ttsOk || text == null) return false;
            try {
                Voice chosen = null;
                if (voice != null && voice.length() > 0) {
                    for (Voice v : voiceList()) if (voice.equals(v.getName())) { chosen = v; break; }
                }
                if (chosen != null) {
                    tts.setVoice(chosen);
                } else {
                    int r = tts.setLanguage(new Locale("ru", "RU"));
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false;
                }
                tts.setSpeechRate((float) Math.max(0.3, Math.min(3.0, rate)));
                tts.setPitch((float) Math.max(0.3, Math.min(2.0, pitch)));
                tts.stop();
                int max = 3000, pos = 0, n = 0;
                while (pos < text.length()) {
                    int end = Math.min(text.length(), pos + max);
                    if (end < text.length()) {
                        int cut = Math.max(text.lastIndexOf(". ", end), text.lastIndexOf(' ', end));
                        if (cut > pos + 200) end = cut + 1;
                    }
                    tts.speak(text.substring(pos, end), n == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "jarvis" + n);
                    pos = end;
                    n++;
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public boolean speaking() {
            try { return tts != null && tts.isSpeaking(); } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public String saveText(String name, String text) {
            try {
                if (!hasAccess()) return "";
                ensureDirs();
                String safe = new File(name == null ? "note.md" : name).getName().replace(':', '-');
                File f = new File(new File(root(), "notes"), safe);
                Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
                try {
                    w.write(text == null ? "" : text);
                } finally {
                    w.close();
                }
                return "Jarvis/notes/" + safe;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
