package com.abema.jarvis;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Запускает движок llama-server отдельным процессом и следит за ним. */
final class Engine {
    static final int PORT = 8080;
    private static Engine inst;

    private final Context ctx;
    private final ArrayDeque<String> log = new ArrayDeque<String>();
    private Process proc;
    private String state = "stopped";   // stopped | running | failed
    private String model = "";

    private Engine(Context c) { ctx = c; }

    static synchronized Engine get(Context c) {
        if (inst == null) inst = new Engine(c.getApplicationContext());
        return inst;
    }

    synchronized String state() { return state; }
    synchronized String model() { return model; }

    List<String> logTail() {
        synchronized (log) { return new ArrayList<String>(log); }
    }

    private void addLog(String line) {
        synchronized (log) {
            log.addLast(line.length() > 400 ? line.substring(0, 400) : line);
            while (log.size() > 60) log.removeFirst();
        }
    }

    synchronized void start(File file, int ctxSize, int threads) {
        stopLocked();
        synchronized (log) { log.clear(); }
        model = file.getName();
        String dir = ctx.getApplicationInfo().nativeLibraryDir;
        File bin = new File(dir, "libllama_server.so");
        if (!bin.exists()) {
            state = "failed";
            addLog("Не найден движок: " + bin.getAbsolutePath());
            return;
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-m"); cmd.add(file.getAbsolutePath());
        String name = file.getName();
        String stem = name.toLowerCase().endsWith(".gguf") ? name.substring(0, name.length() - 5) : name;
        File mm = new File(file.getParentFile(), "mmproj-" + name);
        if (!mm.exists()) mm = new File(file.getParentFile(), stem + ".mmproj.gguf");
        if (mm.exists()) { cmd.add("--mmproj"); cmd.add(mm.getAbsolutePath()); }
        cmd.add("--host"); cmd.add("127.0.0.1");
        cmd.add("--port"); cmd.add(String.valueOf(PORT));
        cmd.add("-c"); cmd.add(String.valueOf(ctxSize));
        cmd.add("-t"); cmd.add(String.valueOf(threads));
        cmd.add("-np"); cmd.add("1");
        cmd.add("--cache-ram"); cmd.add("0");
        cmd.add("--jinja");
        cmd.add("--no-webui");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(ctx.getFilesDir());
            Map<String, String> env = pb.environment();
            env.put("LD_LIBRARY_PATH", dir);
            env.put("HOME", ctx.getFilesDir().getAbsolutePath());
            env.put("TMPDIR", ctx.getCacheDir().getAbsolutePath());
            env.put("LLAMA_CACHE", ctx.getCacheDir().getAbsolutePath());
            final Process p = pb.start();
            proc = p;
            state = "running";
            addLog("Запуск: " + name + ", контекст " + ctxSize + ", потоков " + threads);
            Thread t = new Thread(new Runnable() {
                @Override public void run() { pump(p); }
            }, "llama-log");
            t.setDaemon(true);
            t.start();
        } catch (Exception e) {
            state = "failed";
            addLog("Ошибка запуска: " + e);
        }
    }

    private void pump(Process p) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) addLog(line);
        } catch (Exception ignored) {
        }
        int code = -1;
        try { code = p.waitFor(); } catch (InterruptedException ignored) { }
        synchronized (this) {
            if (proc == p) {
                proc = null;
                state = "failed";
                addLog("Движок остановился, код " + code);
            }
        }
    }

    synchronized void stop() {
        stopLocked();
        state = "stopped";
        model = "";
    }

    private void stopLocked() {
        Process p = proc;
        proc = null;
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
    }
}
