package com.abema.jarvis;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Отдаёт файлы библиотеки другим приложениям (просмотрщикам) по временной ссылке. */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "com.abema.jarvis.files";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getAbsolutePath()).build();
    }

    static String mime(String name) {
        String e = Library.ext(name);
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (m != null) return m;
        if (e.equals("md") || e.equals("log") || e.equals("ini") || e.equals("yaml") || e.equals("yml")) return "text/plain";
        return "application/octet-stream";
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        try {
            String p = uri.getLastPathSegment();
            File f = new File(p).getCanonicalFile();
            File root = new File(Environment.getExternalStorageDirectory(), "Jarvis").getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + File.separator) || !f.isFile()) throw new FileNotFoundException();
            return f;
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return mime(resolve(uri).getName());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try {
            File f = resolve(uri);
            c.addRow(new Object[]{f.getName(), f.length()});
        } catch (Exception ignored) {
        }
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
