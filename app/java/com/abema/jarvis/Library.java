package com.abema.jarvis;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Библиотека на диске: разделы-папки, индекс в SQLite, поиск по словам и началам слов. */
final class Library {
    static final String[] DEFAULTS = {"Наука", "История", "Математика", "Физика", "Медицина",
            "Программирование", "Заметки", "Разное"};
    private static final int MAX_TEXT = 12 * 1024 * 1024;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> TEXT = set("txt", "md", "markdown", "csv", "tsv", "json", "log", "ini", "yaml", "yml",
            "toml", "tex", "py", "js", "ts", "java", "kt", "c", "h", "cpp", "hpp", "cs", "rs", "go", "rb", "php", "sh",
            "bat", "sql", "css", "lua", "swift", "srt");
    private static final Set<String> MARKUP = set("html", "htm", "xhtml", "xml", "fb2");
    private static final Set<String> IMAGE = set("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg");
    private static final Set<String> VIDEO = set("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v");
    private static final Set<String> AUDIO = set("mp3", "wav", "ogg", "m4a", "flac", "aac", "opus");

    private final File root;
    private final SQLiteDatabase db;
    volatile boolean indexing;
    volatile int done;
    volatile int total;
    volatile String current = "";
    volatile String lastError = "";

    Library(Context c, File root) {
        this.root = root;
        db = c.openOrCreateDatabase("library.db", Context.MODE_PRIVATE, null);
        db.enableWriteAheadLogging();
        db.execSQL("CREATE TABLE IF NOT EXISTS docs(id INTEGER PRIMARY KEY, path TEXT UNIQUE, section TEXT, name TEXT, "
                + "lname TEXT, size INTEGER, mtime INTEGER, kind TEXT, nchunks INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS chunks(id INTEGER PRIMARY KEY, doc INTEGER, text TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(doc)");
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts4(body, tokenize=unicode61)");
    }

    private static Set<String> set(String... a) {
        return new HashSet<String>(Arrays.asList(a));
    }

    File root() { return root; }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static String kindOf(String name) {
        String e = ext(name);
        if (TEXT.contains(e) || MARKUP.contains(e)) return "text";
        if (e.equals("docx") || e.equals("odt") || e.equals("epub")) return "doc";
        if (e.equals("pdf")) return "pdf";
        if (IMAGE.contains(e)) return "image";
        if (VIDEO.contains(e)) return "video";
        if (AUDIO.contains(e)) return "audio";
        return "other";
    }

    void ensureDirs() {
        root.mkdirs();
        for (String s : DEFAULTS) new File(root, s).mkdirs();
    }

    /* ---------- индексация ---------- */

    synchronized void scan() {
        if (indexing) return;
        indexing = true;
        done = 0;
        total = 0;
        current = "";
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runScan();
                    lastError = "";
                } catch (Throwable e) {
                    lastError = String.valueOf(e);
                } finally {
                    indexing = false;
                    current = "";
                }
            }
        }, "library-scan");
        t.setDaemon(true);
        t.start();
    }

    private void runScan() {
        ensureDirs();
        List<File> files = new ArrayList<File>();
        walk(root, files, 0);
        total = files.size();
        Set<String> seen = new HashSet<String>();
        for (File f : files) {
            current = f.getName();
            seen.add(f.getAbsolutePath());
            try {
                upsert(f);
            } catch (Throwable e) {
                lastError = f.getName() + ": " + e;
            }
            done++;
        }
        List<Long> gone = new ArrayList<Long>();
        Cursor c = db.rawQuery("SELECT id, path FROM docs", null);
        try {
            while (c.moveToNext()) if (!seen.contains(c.getString(1))) gone.add(c.getLong(0));
        } finally {
            c.close();
        }
        for (Long id : gone) deleteDoc(id);
    }

    private void walk(File dir, List<File> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 8) return;
        Arrays.sort(fs);
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            if (f.isDirectory()) walk(f, out, depth + 1);
            else if (f.isFile()) out.add(f);
        }
    }

    private String sectionOf(File f) {
        String rp = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        if (!p.startsWith(rp + "/")) return "Разное";
        String rel = p.substring(rp.length() + 1);
        int i = rel.indexOf('/');
        return i < 0 ? "Разное" : rel.substring(0, i);
    }

    private void upsert(File f) throws Exception {
        String path = f.getAbsolutePath();
        long id = -1;
        Cursor c = db.rawQuery("SELECT id, size, mtime FROM docs WHERE path=?", new String[]{path});
        try {
            if (c.moveToFirst()) {
                id = c.getLong(0);
                if (c.getLong(1) == f.length() && c.getLong(2) == f.lastModified()) return;
            }
        } finally {
            c.close();
        }
        String kind = kindOf(f.getName());
        String text = null;
        if (kind.equals("text") || kind.equals("doc")) {
            try {
                text = extract(f);
            } catch (Throwable e) {
                lastError = f.getName() + ": " + e;
            }
        }
        db.beginTransaction();
        try {
            if (id >= 0) deleteDocRows(id);
            ContentValues v = new ContentValues();
            v.put("path", path);
            v.put("section", sectionOf(f));
            v.put("name", f.getName());
            v.put("lname", norm(f.getName()));
            v.put("size", f.length());
            v.put("mtime", f.lastModified());
            v.put("kind", kind);
            long doc = db.insertOrThrow("docs", null, v);
            int n = 0;
            if (text != null) {
                for (String part : split(text)) {
                    ContentValues cv = new ContentValues();
                    cv.put("doc", doc);
                    cv.put("text", part);
                    long cid = db.insertOrThrow("chunks", null, cv);
                    db.execSQL("INSERT INTO fts(docid, body) VALUES(?, ?)", new Object[]{cid, norm(part)});
                    n++;
                }
            }
            db.execSQL("UPDATE docs SET nchunks=? WHERE id=?", new Object[]{n, doc});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void deleteDocRows(long id) {
        db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM chunks WHERE doc=?)", new Object[]{id});
        db.execSQL("DELETE FROM chunks WHERE doc=?", new Object[]{id});
        db.execSQL("DELETE FROM docs WHERE id=?", new Object[]{id});
    }

    void deleteDoc(long id) {
        db.beginTransaction();
        try {
            deleteDocRows(id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    File fileOf(long id) {
        Cursor c = db.rawQuery("SELECT path FROM docs WHERE id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? new File(c.getString(0)) : null;
        } finally {
            c.close();
        }
    }

    /* ---------- извлечение текста ---------- */

    private static byte[] readBytes(InputStream in, int max) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, Math.min(n, max - bo.size()));
        return bo.toByteArray();
    }

    private static String decode(byte[] b) {
        try {
            return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(b, "windows-1251");
            } catch (Exception e2) {
                return new String(b);
            }
        }
    }

    private static String strip(String s) {
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|section|title)>|<br\\s*/?>", "\n\n");
        s = s.replaceAll("<[^>]+>", " ");
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#160;", " ");
    }

    private static String extract(File f) throws Exception {
        String e = ext(f.getName());
        if (e.equals("docx") || e.equals("odt") || e.equals("epub")) {
            StringBuilder sb = new StringBuilder();
            ZipFile z = new ZipFile(f);
            try {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements() && sb.length() < MAX_TEXT) {
                    ZipEntry ze = en.nextElement();
                    String n = ze.getName().toLowerCase(Locale.ROOT);
                    boolean take = e.equals("docx") ? n.equals("word/document.xml")
                            : e.equals("odt") ? n.equals("content.xml")
                            : (n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm"));
                    if (!take) continue;
                    InputStream in = z.getInputStream(ze);
                    try {
                        String x = decode(readBytes(in, MAX_TEXT));
                        x = x.replace("</w:p>", "\n\n").replace("</text:p>", "\n\n");
                        sb.append(strip(x)).append("\n\n");
                    } finally {
                        in.close();
                    }
                }
            } finally {
                z.close();
            }
            return sb.toString();
        }
        InputStream in = new FileInputStream(f);
        String s;
        try {
            s = decode(readBytes(in, MAX_TEXT));
        } finally {
            in.close();
        }
        return MARKUP.contains(e) ? strip(s) : s;
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String p : text.replace("\r", "").split("\n\\s*\n")) {
            String s = p.trim().replaceAll("[ \\t]+", " ");
            if (s.length() == 0) continue;
            if (cur.length() > 0 && cur.length() + s.length() > 1200) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (s.length() > 1500) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                for (int i = 0; i < s.length(); i += 1200) out.add(s.substring(i, Math.min(s.length(), i + 1200)));
            } else {
                if (cur.length() > 0) cur.append("\n\n");
                cur.append(s);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /* ---------- поиск ---------- */

    private static List<String> tokens(String q) {
        List<String> out = new ArrayList<String>();
        Matcher m = WORD.matcher(norm(q));
        while (m.find() && out.size() < 8) {
            String t = m.group();
            if (t.length() >= 2 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String stem(String t) {
        if (t.length() >= 7) return t.substring(0, t.length() - 2);
        if (t.length() >= 5) return t.substring(0, t.length() - 1);
        return t;
    }

    private static final class Hit {
        long doc;
        String name, section, kind, text;
        int score;
        boolean byName;
    }

    JSONObject search(String q, String section, String kind, int limit, boolean full) throws Exception {
        List<String> toks = tokens(q == null ? "" : q);
        JSONObject res = new JSONObject();
        JSONArray arr = new JSONArray();
        res.put("hits", arr);
        if (toks.isEmpty()) return res;
        List<String> stems = new ArrayList<String>();
        StringBuilder match = new StringBuilder();
        for (String t : toks) {
            String s = stem(t);
            stems.add(s);
            if (match.length() > 0) match.append(" OR ");
            match.append(s).append('*');
        }
        String filt = "";
        List<String> fa = new ArrayList<String>();
        if (section != null && section.length() > 0) {
            filt += " AND d.section=?";
            fa.add(section);
        }
        if (kind != null && kind.length() > 0) {
            if (kind.equals("text")) filt += " AND d.kind IN ('text','doc')";
            else {
                filt += " AND d.kind=?";
                fa.add(kind);
            }
        }
        List<Hit> hits = new ArrayList<Hit>();
        Map<Long, Integer> perDoc = new HashMap<Long, Integer>();
        List<String> a1 = new ArrayList<String>();
        a1.add(match.toString());
        a1.addAll(fa);
        Cursor c = db.rawQuery("SELECT c.doc, c.text, d.name, d.section, d.kind FROM fts JOIN chunks c ON c.id = fts.docid "
                + "JOIN docs d ON d.id = c.doc WHERE fts.body MATCH ?" + filt + " LIMIT 500", a1.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.doc = c.getLong(0);
                h.text = c.getString(1);
                h.name = c.getString(2);
                h.section = c.getString(3);
                h.kind = c.getString(4);
                String n = norm(h.text);
                String ln = norm(h.name);
                for (String s : stems) {
                    int i = n.indexOf(s);
                    if (i >= 0) {
                        h.score += 10;
                        int k = 0;
                        while (i >= 0 && k < 4) {
                            k++;
                            i = n.indexOf(s, i + s.length());
                        }
                        h.score += k;
                    }
                    if (ln.contains(s)) h.score += 6;
                }
                hits.add(h);
            }
        } finally {
            c.close();
        }
        StringBuilder like = new StringBuilder();
        List<String> a2 = new ArrayList<String>();
        for (String t : toks) {
            if (like.length() > 0) like.append(" OR ");
            like.append("d.lname LIKE ?");
            a2.add("%" + t.replace("%", "").replace("_", "") + "%");
        }
        a2.addAll(fa);
        c = db.rawQuery("SELECT d.id, d.name, d.section, d.kind, d.lname FROM docs d WHERE (" + like + ")" + filt + " LIMIT 300",
                a2.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                Hit h = new Hit();
                h.doc = c.getLong(0);
                h.name = c.getString(1);
                h.section = c.getString(2);
                h.kind = c.getString(3);
                h.byName = true;
                String ln = c.getString(4);
                for (String t : toks) if (ln.contains(t)) h.score += 12;
                hits.add(h);
            }
        } finally {
            c.close();
        }
        Collections.sort(hits, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.score != b.score) return b.score - a.score;
                return (a.byName ? 1 : 0) - (b.byName ? 1 : 0);
            }
        });
        Set<Long> named = new HashSet<Long>();
        for (Hit h : hits) {
            if (arr.length() >= limit) break;
            Integer n = perDoc.get(h.doc);
            if (h.byName) {
                if (n != null || named.contains(h.doc) || full) continue;
                named.add(h.doc);
            } else {
                if (named.contains(h.doc) || (n != null && n >= 2)) continue;
                perDoc.put(h.doc, n == null ? 1 : n + 1);
            }
            JSONObject o = new JSONObject();
            o.put("doc", h.doc);
            o.put("name", h.name);
            o.put("section", h.section);
            o.put("kind", h.kind);
            o.put("byName", h.byName);
            if (h.text != null) {
                if (full) o.put("text", h.text);
                o.put("snippet", snippet(h.text, stems));
            }
            arr.put(o);
        }
        JSONArray st = new JSONArray();
        for (String s : stems) st.put(s);
        res.put("stems", st);
        return res;
    }

    private static String snippet(String text, List<String> stems) {
        String n = norm(text);
        int pos = -1;
        for (String s : stems) {
            int i = n.indexOf(s);
            if (i >= 0 && (pos < 0 || i < pos)) pos = i;
        }
        int from = Math.max(0, pos - 90);
        int to = Math.min(text.length(), from + 320);
        return (from > 0 ? "…" : "") + text.substring(from, to).trim() + (to < text.length() ? "…" : "");
    }

    JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        Map<String, Integer> counts = new HashMap<String, Integer>();
        Cursor c = db.rawQuery("SELECT section, COUNT(*) FROM docs GROUP BY section", null);
        try {
            while (c.moveToNext()) counts.put(c.getString(0), c.getInt(1));
        } finally {
            c.close();
        }
        List<String> names = new ArrayList<String>();
        File[] dirs = root.listFiles();
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File d : dirs) if (d.isDirectory() && !d.getName().startsWith(".")) names.add(d.getName());
        }
        for (String s : counts.keySet()) if (!names.contains(s)) names.add(s);
        JSONArray arr = new JSONArray();
        int all = 0;
        for (String s : names) {
            int n = counts.containsKey(s) ? counts.get(s) : 0;
            all += n;
            arr.put(new JSONObject().put("name", s).put("count", n));
        }
        o.put("sections", arr);
        o.put("docs", all);
        o.put("indexing", indexing);
        o.put("done", done);
        o.put("total", total);
        o.put("current", current);
        o.put("error", lastError);
        return o;
    }

    JSONObject list(String section) throws Exception {
        JSONArray arr = new JSONArray();
        Cursor c = db.rawQuery("SELECT id, name, size, kind, nchunks FROM docs WHERE section=? ORDER BY lname LIMIT 1000",
                new String[]{section == null ? "" : section});
        try {
            while (c.moveToNext()) {
                arr.put(new JSONObject().put("doc", c.getLong(0)).put("name", c.getString(1)).put("size", c.getLong(2))
                        .put("kind", c.getString(3)).put("chunks", c.getInt(4)).put("section", section));
            }
        } finally {
            c.close();
        }
        return new JSONObject().put("files", arr);
    }
}
