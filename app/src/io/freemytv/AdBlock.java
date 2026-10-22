package io.freemytv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AR2's ad blocker: the AdGuard DNS filter (ads, trackers and the pop-up/redirect networks that streaming sites use),
 * matched on host names like AdGuard DNS does. A copy ships in the app (assets/adblock); once a week the browser
 * downloads the latest list in the background and uses it from the next visit on.
 *
 * About 190,000 domains: kept as one sorted byte array plus offsets (a few MB), searched with binary search, so it
 * costs a fraction of what a HashSet of strings would on a TV.
 */
final class AdBlock {
    private AdBlock() {}

    static final String LIST_URL = "https://raw.githubusercontent.com/AdguardTeam/HostlistsRegistry/main/assets/filter_1.txt";
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final int MIN_DOMAINS = 20_000;   // a smaller download is broken: keep the old list

    private static volatile byte[] data;
    private static volatile int[] starts;
    private static volatile Set<String> allow = Collections.emptySet();
    private static boolean loading, refreshing;

    /** Loads the list in the background (the downloaded one if there is one, else the built-in copy). */
    static synchronized void load(Context c) {
        if (loading || data != null) return;
        loading = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] d = null;
                File f = new File(app.getFilesDir(), "adblock-domains.txt");
                if (f.length() > 100_000) d = readAll(new FileInputStream(f));
                if (d == null) d = readAll(app.getAssets().open("adblock/domains.txt"));
                Set<String> a = new HashSet<>();
                File fa = new File(app.getFilesDir(), "adblock-allow.txt");
                byte[] ad = fa.exists() ? readAll(new FileInputStream(fa)) : readAll(app.getAssets().open("adblock/allow.txt"));
                for (String line : new String(ad, StandardCharsets.US_ASCII).split("\n")) if (!line.isEmpty()) a.add(line);
                use(d, a);
            } catch (IOException | RuntimeException e) {
                AppLog.w("ad block list: " + e);
            } finally {
                synchronized (AdBlock.class) { loading = false; }
            }
        }, "adblock-load").start();
    }

    private static void use(byte[] d, Set<String> a) {
        int n = 0;
        for (byte b : d) if (b == '\n') n++;
        int[] s = new int[n + 1];
        int count = 0, at = 0;
        for (int i = 0; i < d.length; i++) {
            if (d[i] == '\n') {
                if (i > at) s[count++] = at;
                at = i + 1;
            }
        }
        if (at < d.length) s[count++] = at;
        int[] exact = new int[count];
        System.arraycopy(s, 0, exact, 0, count);
        allow = a;
        starts = exact;
        data = d;
        AppLog.w("ad block: " + count + " domains");
    }

    /** True when a request to `host` (or any domain above it) should be blocked. */
    static boolean blocked(String host) {
        byte[] d = data;
        int[] s = starts;
        if (d == null || s == null || host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        Set<String> a = allow;
        if (!a.isEmpty()) {
            // An exception covers the domain and everything under it, like in AdGuard.
            for (String part = h; ; ) {
                if (a.contains(part)) return false;
                int dot = part.indexOf('.');
                if (dot < 0) break;
                part = part.substring(dot + 1);
            }
        }
        for (String part = h; ; ) {
            if (contains(d, s, part)) return true;
            int dot = part.indexOf('.');
            if (dot < 0) return false;
            part = part.substring(dot + 1);
            if (part.indexOf('.') < 0) return false;   // never a whole top-level domain
        }
    }

    private static boolean contains(byte[] d, int[] s, String key) {
        int lo = 0, hi = s.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = compare(d, s[mid], key);
            if (c == 0) return true;
            if (c < 0) lo = mid + 1; else hi = mid - 1;
        }
        return false;
    }

    /** The entry starting at `at` (up to its newline) against `key`, byte by byte (the file is sorted the same way). */
    private static int compare(byte[] d, int at, String key) {
        int n = key.length();
        for (int i = 0; ; i++) {
            int p = at + i;
            boolean entryEnded = p >= d.length || d[p] == '\n';
            if (entryEnded) return i == n ? 0 : -1;
            if (i == n) return 1;
            int x = d[p] & 0xFF, y = key.charAt(i);
            if (x != y) return x - y;
        }
    }

    // MARK: - Weekly update

    private static final Pattern RULE = Pattern.compile("^(@@)?\\|\\|([a-z0-9][a-z0-9.-]*[a-z0-9])\\^(\\$important)?$");

    /** Downloads the newest list in the background when ours is a week old (used from the next visit). */
    static synchronized void refreshIfOld(Context c) {
        if (refreshing) return;
        File f = new File(c.getFilesDir(), "adblock-domains.txt");
        File stamp = new File(c.getFilesDir(), "adblock-checked");
        if (System.currentTimeMillis() - stamp.lastModified() < WEEK && f.exists()) return;
        refreshing = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                HttpURLConnection http = (HttpURLConnection) new URL(LIST_URL).openConnection();
                http.setConnectTimeout(15_000);
                http.setReadTimeout(30_000);
                byte[] raw;
                try (InputStream in = http.getInputStream()) {
                    raw = readAll(in);
                } finally {
                    http.disconnect();
                }
                List<String> domains = new ArrayList<>();
                List<String> exceptions = new ArrayList<>();
                for (String line : new String(raw, StandardCharsets.UTF_8).split("\n")) {
                    Matcher m = RULE.matcher(line.trim().toLowerCase(Locale.ROOT));
                    if (!m.matches()) continue;
                    if (m.group(1) != null) exceptions.add(m.group(2)); else domains.add(m.group(2));
                }
                if (domains.size() < MIN_DOMAINS) throw new IOException("list too small: " + domains.size());
                Collections.sort(domains);   // ASCII only, so String order = byte order
                write(new File(app.getFilesDir(), "adblock-allow.txt"), exceptions);
                write(f, domains);
                touch(stamp);
                AppLog.w("ad block list updated: " + domains.size() + " domains");
            } catch (IOException | RuntimeException e) {
                AppLog.w("ad block update: " + e);
            } finally {
                synchronized (AdBlock.class) { refreshing = false; }
            }
        }, "adblock-update").start();
    }

    private static void write(File f, List<String> lines) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        StringBuilder sb = new StringBuilder();
        String last = null;
        for (String l : lines) {
            if (l.equals(last)) continue;
            sb.append(l).append('\n');
            last = l;
        }
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }
        if (!tmp.renameTo(f)) throw new IOException("could not save " + f.getName());
    }

    private static void touch(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write('1');
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = i.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
