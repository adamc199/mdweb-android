package fm.curry.mdweb;

import android.content.SharedPreferences;
import android.text.Html;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The river of news (Adam 2026-11-18): the feeds you subscribe to, newest items first, all in one list.
 * Subscriptions live in prefs ("feeds": [{u, t}]); the items in files/river.json so the river shows at once on start.
 * Reads RSS 2.0, RSS 1.0 (RDF) and Atom.
 */
class River {
    static final int KEEP = 400;            // items kept in all
    static final long STALE_MS = 5 * 60_000L;

    static class Item {
        String feed, feedTitle, title, link, snippet, key;
        long t;
    }

    static class Feed {
        String title = "";
        final List<Item> items = new ArrayList<>();
    }

    final SharedPreferences prefs;
    final File cacheFile;
    final List<Item> items = new ArrayList<>();
    final Map<String, String[]> validators = new HashMap<>();   // feed -> {etag, last-modified}
    long lastRefresh;
    String lastErrors = "";

    River(SharedPreferences prefs, File dir) {
        this.prefs = prefs;
        this.cacheFile = new File(dir, "river.json");
        load();
    }

    // ───────── subscriptions

    synchronized JSONArray subs() {
        try { return new JSONArray(prefs.getString("feeds", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    synchronized boolean subscribed(String url) {
        JSONArray a = subs();
        for (int i = 0; i < a.length(); i++) if (url.equals(a.optJSONObject(i).optString("u"))) return true;
        return false;
    }

    synchronized void subscribe(String url, String title) {
        if (subscribed(url)) return;
        JSONArray a = subs();
        try { a.put(new JSONObject().put("u", url).put("t", title == null || title.isEmpty() ? url : title)); } catch (Exception ignored) { }
        prefs.edit().putString("feeds", a.toString()).apply();
    }

    synchronized void unsubscribe(String url) {
        JSONArray a = subs(), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!url.equals(a.optJSONObject(i).optString("u"))) b.put(a.optJSONObject(i));
        prefs.edit().putString("feeds", b.toString()).apply();
        items.removeIf(it -> url.equals(it.feed));
        validators.remove(url);
        save();
    }

    // ───────── reading feeds

    /** Fetch every feed (conditional GET), merge new items. Blocking: call off the UI thread. */
    synchronized void refresh() {
        JSONArray a = subs();
        Set<String> have = new HashSet<>();
        for (Item it : items) have.add(it.key);
        StringBuilder errs = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            String url = a.optJSONObject(i).optString("u");
            try {
                Map<String, String> h = new HashMap<>();
                String[] v = validators.get(url);
                if (v != null && v[0] != null) h.put("If-None-Match", v[0]);
                if (v != null && v[1] != null) h.put("If-Modified-Since", v[1]);
                MainActivity.Resp r = MainActivity.fetch(url, "application/rss+xml, application/atom+xml, application/xml, text/xml, */*", h);
                if (r.code == 304) continue;
                if (r.code >= 400) { errs.append(a.optJSONObject(i).optString("t")).append(": ").append(r.code).append('\n'); continue; }
                validators.put(url, new String[]{r.etag, r.lastModified});
                Feed f = parse(r.body, r.finalUrl);
                if (f == null) { errs.append(a.optJSONObject(i).optString("t")).append(": not a feed\n"); continue; }
                String ft = f.title.isEmpty() ? a.optJSONObject(i).optString("t") : f.title;
                long now = System.currentTimeMillis();
                for (Item it : f.items) {
                    it.feed = url; it.feedTitle = ft;
                    it.key = url + "|" + it.key;
                    if (have.add(it.key)) {
                        if (it.t <= 0 || it.t > now + 3600_000L) it.t = now;        // undated / future-dated: when we first saw it
                        items.add(it);
                    }
                }
            } catch (Exception e) {
                errs.append(a.optJSONObject(i).optString("t")).append(": ").append(e.getMessage()).append('\n');
            }
        }
        items.sort((x, y) -> Long.compare(y.t, x.t));
        while (items.size() > KEEP) items.remove(items.size() - 1);
        lastRefresh = System.currentTimeMillis();
        lastErrors = errs.toString().trim();
        save();
    }

    boolean stale() { return System.currentTimeMillis() - lastRefresh > STALE_MS; }

    /** The feed in these bytes, or null if they are not RSS / Atom. */
    static Feed parse(byte[] body, String base) {
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8);
        if (!(head.contains("<rss") || head.contains("<feed") || head.contains("<rdf:RDF") || head.contains("<RDF"))) return null;
        Feed f = new Feed();
        try {
            XmlPullParser x = Xml.newPullParser();
            x.setInput(new ByteArrayInputStream(body), null);
            Item cur = null;
            int depthItem = -1;
            String desc = null;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = x.getName();
                    if ("item".equals(n) || "entry".equals(n)) { cur = new Item(); depthItem = x.getDepth(); desc = null; continue; }
                    if (cur == null) {
                        if ("title".equals(n) && f.title.isEmpty()) f.title = text(x).trim();
                        continue;
                    }
                    switch (n) {
                        case "title": cur.title = Html.fromHtml(text(x), Html.FROM_HTML_MODE_LEGACY).toString().trim(); break;
                        case "link": {
                            String href = x.getAttributeValue(null, "href"), rel = x.getAttributeValue(null, "rel");
                            if (href != null) { if (cur.link == null || "alternate".equals(rel)) cur.link = MainActivity.resolve(base, href); }
                            else { String t = text(x).trim(); if (!t.isEmpty()) cur.link = MainActivity.resolve(base, t); }
                            break;
                        }
                        case "guid": case "id": {
                            boolean perma = !"false".equals(x.getAttributeValue(null, "isPermaLink"));
                            String g = text(x).trim();
                            cur.key = g;
                            if (cur.link == null && perma && g.startsWith("http")) cur.link = g;
                            break;
                        }
                        case "pubDate": case "published": case "updated": case "dc:date": case "date": {
                            long t = date(text(x).trim());
                            if (t > 0 && (cur.t <= 0 || !"updated".equals(n))) cur.t = t;
                            break;
                        }
                        case "description": case "summary": case "content": case "content:encoded":
                            if (desc == null || "description".equals(n) || "summary".equals(n)) desc = text(x);
                            break;
                        default: break;
                    }
                } else if (ev == XmlPullParser.END_TAG && cur != null && x.getDepth() == depthItem
                        && ("item".equals(x.getName()) || "entry".equals(x.getName()))) {
                    cur.snippet = snippet(desc);
                    if (cur.key == null || cur.key.isEmpty()) cur.key = cur.link != null ? cur.link : (cur.title + "|" + cur.snippet);
                    if ((cur.title == null || cur.title.isEmpty()) && (cur.snippet == null || cur.snippet.isEmpty())) { cur = null; continue; }
                    f.items.add(cur);
                    cur = null;
                }
            }
        } catch (Exception e) {
            if (f.items.isEmpty()) return null;
        }
        return f;
    }

    static String text(XmlPullParser x) throws Exception {
        StringBuilder b = new StringBuilder();
        int d = x.getDepth();
        for (int ev = x.next(); !(ev == XmlPullParser.END_TAG && x.getDepth() == d); ev = x.next()) {
            if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) b.append(x.getText());
            else if (ev == XmlPullParser.END_DOCUMENT) break;
        }
        return b.toString();
    }

    static String snippet(String html) {
        if (html == null) return "";
        String t = Html.fromHtml(html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", ""), Html.FROM_HTML_MODE_LEGACY).toString()
                .replace('￼', ' ').replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 297).replaceAll("\\s+\\S*$", "") + "…" : t;
    }

    static final String[] RFC = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm Z", "dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z"};

    static long date(String s) {
        if (s.isEmpty()) return 0;
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch (Exception ignored) { }
        for (String f : RFC) {
            try { return new SimpleDateFormat(f, Locale.US).parse(s).getTime(); } catch (Exception ignored) { }
        }
        return 0;
    }

    // ───────── the river for the viewer, and the cache

    synchronized JSONArray json(int max) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.size() && i < max; i++) {
            Item it = items.get(i);
            try {
                out.put(new JSONObject().put("feed", it.feed).put("feedTitle", it.feedTitle).put("title", it.title == null ? "" : it.title)
                        .put("link", it.link == null ? "" : it.link).put("snippet", it.snippet == null ? "" : it.snippet).put("t", it.t));
            } catch (Exception ignored) { }
        }
        return out;
    }

    synchronized void save() {
        try {
            JSONObject o = new JSONObject().put("lastRefresh", lastRefresh);
            JSONArray a = new JSONArray();
            for (Item it : items) a.put(new JSONObject().put("feed", it.feed).put("feedTitle", it.feedTitle).put("title", it.title)
                    .put("link", it.link).put("snippet", it.snippet).put("key", it.key).put("t", it.t));
            o.put("items", a);
            File tmp = new File(cacheFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            tmp.renameTo(cacheFile);
        } catch (Exception ignored) { }
    }

    synchronized void load() {
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8));
            lastRefresh = o.optLong("lastRefresh");
            JSONArray a = o.optJSONArray("items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Item it = new Item();
                it.feed = j.optString("feed"); it.feedTitle = j.optString("feedTitle"); it.title = j.optString("title");
                it.link = j.optString("link"); it.snippet = j.optString("snippet"); it.key = j.optString("key"); it.t = j.optLong("t");
                items.add(it);
            }
        } catch (Exception ignored) { }
    }
}
