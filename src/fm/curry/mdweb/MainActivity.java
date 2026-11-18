package fm.curry.mdweb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MD Web: a reader for the web of Markdown pages, written from the open BonBon charter
 * (https://github.com/scripting/bonBon/blob/main/docs/charter.md). One activity, no libraries
 * except markdown-it (MIT) in assets for rendering.
 */
public class MainActivity extends Activity {

    static final String HOME = "mdweb:home";
    static final String ACCEPT = "text/markdown, text/x-opml";
    static final String UA = "MDWeb/1.0 (Android; Markdown web reader)";
    static final String[] STYLES = {"Reader", "Newsprint", "Classic", "Mono", "Book"};
    static final int MAX_BYTES = 15 * 1024 * 1024;

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    SharedPreferences prefs;

    // ui
    LinearLayout tabStrip, bookmarkBar, findBar;
    EditText address, findText;
    FrameLayout webHolder;
    LinearLayout arrows;
    Button prevBtn, nextBtn, calBtn;
    ProgressBar progress;

    final ArrayList<Tab> tabs = new ArrayList<>();
    Tab active;
    String template, mdit;
    final Map<String, String> styleCss = new LinkedHashMap<>();

    // site caches: folder -> toc url / bonbon.json (or "" when that folder has none)
    final Map<String, List<String>> tocCache = new HashMap<>();
    final Map<String, List<String[]>> tocEntries = new HashMap<>();   // {text, type, url} for the calendar
    final Map<String, String> dirToc = new HashMap<>();
    final Map<String, JSONObject> dirInfo = new HashMap<>();

    static class Page {
        String mode;          // markdown | text | image | download
        String url;           // the address that was asked for
        String finalUrl;      // after redirects
        String contentLocation;
        String text = "";     // rendered text (macros done) or text to show
        String raw = "";      // source as it came
        String title;
        Map<String, String> front = new HashMap<>();
        String prev, next, socket, search, mirror, siteBase, tocUrl;
        long size;
    }

    class Tab {
        final ArrayList<String> stack = new ArrayList<>();
        int idx = -1;
        WebView web;
        Page page;
        int seq;
        boolean showingSource;
        TextView label;
        String pendingUrl;    // restored tab, not loaded yet

        String url() { return idx >= 0 ? stack.get(idx) : HOME; }
    }

    // ───────────────────────────── setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        prefs = getSharedPreferences("mdweb", MODE_PRIVATE);
        template = asset("viewer.html");
        mdit = asset("markdown-it.min.js") + "\n" + asset("markdown-it-emoji.min.js");
        try {
            String[] files = getAssets().list("styles");
            Arrays.sort(files);
            for (String f : files) styleCss.put(f.replaceAll("^\\d+-|\\.css$", ""), asset("styles/" + f));
        } catch (Exception e) { /* no styles: base layout only */ }
        buildUi();
        restoreTabs();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    /** a .md link tapped in another app, or a link shared to MD Web: open it in a new tab */
    void handleIntent(Intent i) {
        if (i == null) return;
        String u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) u = i.getData().toString();
        else if (Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            Matcher m = Pattern.compile("https?://\\S+").matcher(t == null ? "" : t);
            if (m.find()) u = m.group();
        }
        if (u != null) newTab(u, true);
    }

    int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    Button smallButton(String text, View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setTextSize(17);
        bt.setMinWidth(0); bt.setMinimumWidth(0); bt.setMinHeight(0); bt.setMinimumHeight(0);
        bt.setPadding(dp(10), dp(6), dp(10), dp(6));
        bt.setBackgroundColor(Color.TRANSPARENT);
        bt.setOnClickListener(l);
        return bt;
    }

    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        // tabs
        HorizontalScrollView ts = new HorizontalScrollView(this);
        ts.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        ts.addView(tabStrip);
        root.addView(ts);

        // address row: back, forward, address, A, menu
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(smallButton("◀", v -> back()));
        row.addView(smallButton("▶", v -> forward()));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(15);
        address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setHint("Address of a Markdown page");
        address.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                typed(address.getText().toString().trim());
                hideKeyboard();
                return true;
            }
            return false;
        });
        row.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button aBtn = smallButton("A", this::styleMenu);
        aBtn.setTypeface(Typeface.SERIF, Typeface.BOLD);
        row.addView(aBtn);
        row.addView(smallButton("⋮", this::mainMenu));
        root.addView(row);

        // bookmarks bar
        HorizontalScrollView bs = new HorizontalScrollView(this);
        bs.setHorizontalScrollBarEnabled(false);
        bookmarkBar = new LinearLayout(this);
        bs.addView(bookmarkBar);
        root.addView(bs);

        // find bar (hidden)
        findBar = new LinearLayout(this);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setVisibility(View.GONE);
        findText = new EditText(this);
        findText.setSingleLine(true);
        findText.setHint("Find in page");
        findText.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        findText.setOnEditorActionListener((v, id, ev) -> { findNext(true); return true; });
        findBar.addView(findText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        findBar.addView(smallButton("▲", v -> findNext(false)));
        findBar.addView(smallButton("▼", v -> findNext(true)));
        findBar.addView(smallButton("✕", v -> closeFind()));
        root.addView(findBar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        // pages + the before/after arrows over the top right
        webHolder = new FrameLayout(this);
        arrows = new LinearLayout(this);
        arrows.setBackgroundColor(0x33808080);
        prevBtn = smallButton("‹", v -> { if (active != null && active.page != null && active.page.prev != null) go(active, active.page.prev, "link", true); });
        nextBtn = smallButton("›", v -> { if (active != null && active.page != null && active.page.next != null) go(active, active.page.next, "link", true); });
        prevBtn.setTextSize(24); nextBtn.setTextSize(24);
        arrows.addView(prevBtn);
        arrows.addView(nextBtn);
        calBtn = smallButton("📅", v -> calendarDialog());     // the site's calendar, one tap (Adam: it was hidden in ⋮)
        calBtn.setTextSize(20);
        arrows.addView(calBtn, 0);
        arrows.setVisibility(View.GONE);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ap.setMargins(0, dp(6), dp(6), 0);
        webHolder.addView(arrows, ap);
        root.addView(webHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        refreshBookmarkBar();
    }

    // ───────────────────────────── tabs

    Tab newTab(String url, boolean select) {
        Tab t = new Tab();
        t.web = new WebView(this);
        WebSettings s = t.web.getSettings();
        s.setJavaScriptEnabled(true);           // our viewer script only; page HTML/JS is thrown away
        s.setDomStorageEnabled(false);
        s.setAllowFileAccess(false);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUserAgentString(UA);
        t.web.addJavascriptInterface(new Bridge(t), "App");
        t.web.setWebViewClient(new Client());
        t.web.setOnLongClickListener(v -> linkMenu(t));
        t.web.setVisibility(View.GONE);
        webHolder.addView(t.web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs.add(t);
        TextView lab = new TextView(this);
        lab.setPadding(dp(12), dp(8), dp(12), dp(8));
        lab.setSingleLine(true);
        lab.setEllipsize(TextUtils.TruncateAt.END);
        lab.setMaxWidth(dp(150));
        lab.setTextSize(13);
        lab.setOnClickListener(v -> select(t));
        lab.setOnLongClickListener(v -> { closeTab(t); return true; });
        t.label = lab;
        tabStrip.addView(lab, tabStrip.getChildCount() - (tabStrip.getChildCount() > 0 ? 1 : 0));
        ensurePlus();
        label(t, "New tab");
        if (url != null) {
            if (select) { select(t); go(t, url, "typed", true); }
            else { t.pendingUrl = url; label(t, HOME.equals(url) ? "MD Web" : lastSegment(url)); }
        }
        return t;
    }

    void ensurePlus() {
        int n = tabStrip.getChildCount();
        if (n > 0 && "+".equals(tabStrip.getChildAt(n - 1).getTag())) return;
        Button plus = smallButton("+", v -> newTab(HOME, true));
        plus.setTag("+");
        tabStrip.addView(plus);
    }

    void label(Tab t, String title) {
        t.label.setText(title == null || title.isEmpty() ? "Untitled" : title);
    }

    void select(Tab t) {
        active = t;
        for (Tab o : tabs) {
            o.web.setVisibility(o == t ? View.VISIBLE : View.GONE);
            o.label.setTypeface(null, o == t ? Typeface.BOLD : Typeface.NORMAL);
            o.label.setBackgroundColor(o == t ? 0x22808080 : Color.TRANSPARENT);
        }
        if (t.pendingUrl != null) { String u = t.pendingUrl; t.pendingUrl = null; go(t, u, "typed", true); }
        showAddress(t);
        showArrows();
        saveTabs();
    }

    void closeTab(Tab t) {
        if (tabs.size() == 1) { go(t, HOME, "typed", true); return; }
        int i = tabs.indexOf(t);
        tabs.remove(t);
        tabStrip.removeView(t.label);
        webHolder.removeView(t.web);
        t.web.destroy();
        if (active == t) select(tabs.get(Math.max(0, i - 1)));
        saveTabs();
    }

    void showAddress(Tab t) {
        if (t != active) return;
        String u = t.url();
        address.setText(HOME.equals(u) ? "" : u);
    }

    void showArrows() {
        Page p = active == null ? null : active.page;
        boolean ok = p != null && !active.showingSource;
        boolean nav = ok && (p.prev != null || p.next != null);
        boolean cal = ok && !calendarMonths().isEmpty();
        arrows.setVisibility(nav || cal ? View.VISIBLE : View.GONE);
        prevBtn.setVisibility(nav ? View.VISIBLE : View.GONE);
        nextBtn.setVisibility(nav ? View.VISIBLE : View.GONE);
        calBtn.setVisibility(cal ? View.VISIBLE : View.GONE);
        if (nav) {
            prevBtn.setEnabled(p.prev != null); prevBtn.setAlpha(p.prev != null ? 1f : .3f);
            nextBtn.setEnabled(p.next != null); nextBtn.setAlpha(p.next != null ? 1f : .3f);
        }
    }

    // ───────────────────────────── navigation

    void typed(String s) {
        if (s.isEmpty() || active == null) return;
        if (!s.contains(".") && !s.contains("/") && !s.contains(":")) {
            // one word: search the site you're on (bonbon.json urlSearch)
            Page p = active.page;
            if (p != null && p.search != null) {
                try { go(active, p.search + (p.search.contains("?") ? "&" : "?") + "q=" + URLEncoder.encode(s, "UTF-8"), "typed", true); }
                catch (Exception ignored) { }
            } else toast("This site has no search.");
            return;
        }
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) s = "https://" + s;
        go(active, s, "typed", true);
    }

    void back() {
        if (active == null) return;
        if (active.showingSource) { active.showingSource = false; render(active); return; }
        if (active.idx > 0) { active.idx--; go(active, active.url(), "history", false); }
    }

    void forward() {
        if (active != null && active.idx < active.stack.size() - 1) { active.idx++; go(active, active.url(), "history", false); }
    }

    @Override
    public void onBackPressed() {
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        if (active != null && (active.showingSource || active.idx > 0)) back();
        else super.onBackPressed();
    }

    void push(Tab t, String url) {
        while (t.stack.size() > t.idx + 1) t.stack.remove(t.stack.size() - 1);
        if (t.idx >= 0 && t.stack.get(t.idx).equals(url)) return;
        t.stack.add(url);
        t.idx = t.stack.size() - 1;
    }

    /** how: typed | link | history | reload | update | download */
    void go(Tab t, String url, String how, boolean pushIt) {
        if (HOME.equals(url)) {
            Page p = new Page();
            p.mode = "markdown"; p.url = p.finalUrl = HOME; p.raw = p.text = asset("home.md"); p.title = "MD Web";
            if (pushIt) push(t, url);
            t.page = p; t.showingSource = false;
            render(t);
            return;
        }
        String scheme = Uri.parse(url).getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            external(url);
            return;
        }
        if ("download".equals(how)) { external(url); return; }
        final int my = ++t.seq;
        if (t == active) progress.setVisibility(View.VISIBLE);
        pool.execute(() -> {
            Page p;
            try {
                // urlMirrorOf: a link to the HTML site this one mirrors goes to the same page here, when there is one
                Resp r = null;
                String target = url;
                Page from = t.page;
                if ("link".equals(how) && from != null && from.mirror != null && from.siteBase != null) {
                    for (String cand : mirrorCandidates(from.mirror, from.siteBase, url)) {
                        try {
                            Resp rr = fetch(cand, ACCEPT);
                            if (rr.code == 200 && isMarkdown(rr)) { r = rr; target = cand; break; }
                        } catch (Exception ignored) { }
                    }
                }
                if (r == null) r = fetch(url, ACCEPT);
                if (r.code >= 400) {
                    String why = r.text().trim();
                    if (why.length() > 400 || why.startsWith("<")) why = "";
                    fail(t, my, url, "The server said " + r.code + (r.message != null ? " " + r.message : "") + (why.isEmpty() ? "." : ":\n\n" + why));
                    return;
                }
                p = classify(target, r, how);
            } catch (Exception e) {
                fail(t, my, url, "Couldn't get the page: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return;
            }
            if (p == null) { ui.post(() -> { if (t.seq == my) progress.setVisibility(View.INVISIBLE); }); return; }
            final Page page = p;
            ui.post(() -> {
                if (t.seq != my) return;
                int keepScroll = ("update".equals(how) || "reload".equals(how)) ? t.web.getScrollY() : 0;
                if (pushIt) push(t, page.url);
                t.page = page;
                t.showingSource = false;
                render(t, keepScroll);
                if (t == active) progress.setVisibility(View.INVISIBLE);
                addHistory(page);
                if ("markdown".equals(page.mode)) siteInfo(t, my, page);
            });
        });
    }

    void fail(Tab t, int my, String url, String msg) {
        ui.post(() -> {
            if (t.seq != my) return;
            if (t == active) {
                progress.setVisibility(View.INVISIBLE);
                address.setText(url);   // keep the asked-for address so it can be fixed
                new AlertDialog.Builder(this).setTitle("The page didn't come").setMessage(msg).setPositiveButton("OK", null).show();
            }
        });
    }

    /** Decide what the response is, per the charter. Returns null when nothing should change in the tab. */
    Page classify(String url, Resp r, String how) {
        String ct = r.type();
        String path = Uri.parse(r.finalUrl).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.US);
        boolean mdPath = path.endsWith(".md") || path.endsWith(".markdown");
        Page p = new Page();
        p.url = url; p.finalUrl = r.finalUrl; p.contentLocation = r.contentLocation; p.size = r.body.length;
        p.title = lastSegment(r.finalUrl);
        if (isMarkdown(r)) {
            p.mode = "markdown";
            p.raw = r.text();
            frontMatter(p);
            return p;
        }
        if (ct.equals("text/html") || ct.equals("application/xhtml+xml")) {
            if ("link".equals(how)) { ui.post(() -> external(url)); return null; }   // HTML link: the phone's browser
            p.mode = "text"; p.raw = p.text = r.text(); p.title = "Source: " + p.title;
            return p;
        }
        if (ct.equals("image/png") || ct.equals("image/jpeg") || ct.equals("image/gif") || ct.equals("image/webp")) {
            p.mode = "image";
            return p;
        }
        if (ct.startsWith("text/") || ct.contains("xml") || ct.contains("json") || ct.contains("javascript") || ct.contains("opml")) {
            p.mode = "text"; p.raw = p.text = r.text();
            return p;
        }
        p.mode = "download";
        return p;
    }

    static boolean isMarkdown(Resp r) {
        String ct = r.type();
        String path = Uri.parse(r.finalUrl).getPath();
        path = path == null ? "" : path.toLowerCase(Locale.US);
        boolean mdPath = path.endsWith(".md") || path.endsWith(".markdown");
        return ct.equals("text/markdown") || ct.equals("text/x-markdown")
                || ((ct.equals("text/plain") || ct.equals("application/octet-stream") || ct.isEmpty()) && mdPath);
    }

    /** where a page of the mirrored HTML site may live here: same path, .html as .md, a folder's index.md, and for a
     *  blog's day page with an anchor (scripting.com/2026/11/18.html#a144537) the post file in the day folder */
    static List<String> mirrorCandidates(String mirror, String base, String link) {
        List<String> out = new ArrayList<>();
        String m = mirror.replaceFirst("(?i)^https?://", ""), l = link.replaceFirst("(?i)^https?://", "");
        if (!m.endsWith("/")) m += "/";
        if (!l.toLowerCase(Locale.US).startsWith(m.toLowerCase(Locale.US)) && !(l + "/").equalsIgnoreCase(m)) return out;
        String rest = l.length() >= m.length() ? l.substring(m.length()) : "";
        String frag = null;
        int h = rest.indexOf('#');
        if (h >= 0) { frag = rest.substring(h + 1); rest = rest.substring(0, h); }
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        if (!base.endsWith("/")) base += "/";
        String stem = rest.replaceFirst("(?i)\\.html?$", "");
        if (frag != null && frag.matches("[A-Za-z0-9_-]+") && !stem.isEmpty()) out.add(base + stem + "/" + frag + ".md");
        if (!rest.equals(stem)) out.add(base + stem + ".md");
        if (rest.isEmpty() || rest.endsWith("/")) out.add(base + rest + "index.md");
        else { out.add(base + rest); out.add(base + stem + "/index.md"); }
        return out;
    }

    static final Pattern MACRO = Pattern.compile("\\{\\{\\s*page\\.([A-Za-z0-9_]+)\\s*\\}\\}");

    void frontMatter(Page p) {
        String s = p.raw;
        if (s.startsWith("﻿")) s = s.substring(1);
        String body = s;
        if (s.startsWith("---\n") || s.startsWith("---\r\n")) {
            String[] lines = s.split("\r?\n", -1);
            int end = -1;
            for (int i = 1; i < lines.length; i++) if (lines[i].trim().equals("---") || lines[i].trim().equals("...")) { end = i; break; }
            if (end > 0) {
                for (int i = 1; i < end; i++) {
                    int c = lines[i].indexOf(':');
                    if (c <= 0 || Character.isWhitespace(lines[i].charAt(0))) continue;
                    String k = lines[i].substring(0, c).trim(), v = lines[i].substring(c + 1).trim();
                    if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
                        v = v.substring(1, v.length() - 1);
                    p.front.put(k, v);
                }
                StringBuilder b = new StringBuilder();
                for (int i = end + 1; i < lines.length; i++) b.append(lines[i]).append('\n');
                body = b.toString();
            }
        }
        Matcher m = MACRO.matcher(body);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = p.front.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(out);
        p.text = out.toString();
        String title = p.front.get("title");
        if (title == null) {
            Matcher h = Pattern.compile("(?m)^#\\s+(.+)$").matcher(p.text);
            if (h.find()) title = h.group(1).replaceAll("[*_`]", "").trim();
        }
        if (title != null && !title.isEmpty()) p.title = title;
        if (p.front.get("urlPrev") != null) p.prev = resolve(p.finalUrl, p.front.get("urlPrev"));
        if (p.front.get("urlNext") != null) p.next = resolve(p.finalUrl, p.front.get("urlNext"));
    }

    /** After the page is up: the site's toc.opml (before/after arrows) and bonbon.json (search, live updates). */
    void siteInfo(Tab t, int my, Page p) {
        if (HOME.equals(p.url)) return;
        pool.execute(() -> {
            JSONObject info = findInfo(p.finalUrl);
            if (info != null) {
                p.search = optUrl(info, "urlSearch", p.finalUrl);
                p.socket = optUrl(info, "urlUpdateSocket", p.finalUrl);
                p.mirror = optUrl(info, "urlMirrorOf", p.finalUrl);
                p.siteBase = info.optString("_base", null);
            }
            p.tocUrl = findTocUrl(p.finalUrl);
            if (p.prev == null && p.next == null) {
                List<String> toc = p.tocUrl == null ? null : loadToc(p.tocUrl, 0);
                if (toc != null) {
                    List<String> keys = new ArrayList<>();
                    if (p.contentLocation != null) keys.add(resolve(p.finalUrl, p.contentLocation));
                    keys.add(p.finalUrl); keys.add(p.url);
                    if (p.finalUrl.endsWith("/")) keys.add(p.finalUrl + "index.md");
                    for (String k : keys) {
                        int i = toc.indexOf(norm(k));
                        if (i >= 0) {
                            if (i > 0) p.prev = toc.get(i - 1);
                            if (i < toc.size() - 1) p.next = toc.get(i + 1);
                            break;
                        }
                    }
                }
            }
            ui.post(() -> {
                if (t.seq != my || t.page != p) return;
                if (t == active) showArrows();
                if (p.socket != null) t.web.evaluateJavascript("window.startWatch && startWatch(" + JSONObject.quote(p.socket) + ")", null);
            });
        });
    }

    static String optUrl(JSONObject o, String k, String base) {
        String v = o.optString(k, "");
        return v.isEmpty() ? null : resolve(base, v);
    }

    /** folders from the page's own up to the top of the site */
    static List<String> folders(String pageUrl) {
        List<String> out = new ArrayList<>();
        try {
            URI u = new URI(pageUrl);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            String dir = path.endsWith("/") ? path : path.substring(0, path.lastIndexOf('/') + 1);
            String origin = u.getScheme() + "://" + u.getRawAuthority();
            while (true) {
                out.add(origin + dir);
                if (dir.equals("/")) break;
                dir = dir.substring(0, dir.lastIndexOf('/', dir.length() - 2) + 1);
            }
        } catch (Exception ignored) { }
        return out;
    }

    JSONObject findInfo(String pageUrl) {
        for (String d : folders(pageUrl)) {
            JSONObject cached;
            synchronized (dirInfo) { cached = dirInfo.get(d); }
            if (cached != null) { if (cached.has("_none")) continue; return cached; }
            JSONObject got = null;
            try {
                Resp r = fetch(d + ".well-known/bonbon.json", "application/json");
                if (r.code == 200) { got = new JSONObject(r.text()); got.put("_base", d); }
            } catch (Exception ignored) { }
            JSONObject store = got;
            if (store == null) { store = new JSONObject(); try { store.put("_none", true); } catch (Exception ignored) { } }
            synchronized (dirInfo) { dirInfo.put(d, store); }
            if (got != null) return got;
        }
        return null;
    }

    String findTocUrl(String pageUrl) {
        for (String d : folders(pageUrl)) {
            String known;
            synchronized (dirToc) { known = dirToc.get(d); }
            if (known != null) { if (known.isEmpty()) continue; return known; }
            String tu = d + ".well-known/toc.opml";
            List<String> list = loadToc(tu, 0);
            synchronized (dirToc) { dirToc.put(d, list == null ? "" : tu); }
            if (list != null) return tu;
        }
        return null;
    }

    /** the outline's lines that link somewhere, in order, with include nodes pulled in */
    List<String> loadToc(String tocUrl, int depth) {
        synchronized (tocCache) { if (tocCache.containsKey(tocUrl)) return tocCache.get(tocUrl); }
        List<String> out = null;
        List<String[]> ents = new ArrayList<>();
        try {
            Resp r = fetch(tocUrl, "text/x-opml, text/xml");
            if (r.code == 200 && r.body.length > 0) {
                out = new ArrayList<>();
                XmlPullParser x = Xml.newPullParser();
                x.setInput(new ByteArrayInputStream(r.body), null);
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev != XmlPullParser.START_TAG || !"outline".equals(x.getName())) continue;
                    String type = x.getAttributeValue(null, "type");
                    String u = x.getAttributeValue(null, "url");
                    if (u == null) u = x.getAttributeValue(null, "htmlUrl");
                    if (u == null) continue;
                    u = resolve(tocUrl, u);
                    if ("include".equals(type)) {
                        if (depth < 4) {
                            List<String> sub = loadToc(u, depth + 1);
                            if (sub != null) out.addAll(sub);
                            synchronized (tocCache) { List<String[]> se = tocEntries.get(u); if (se != null) ents.addAll(se); }
                        }
                    } else {
                        out.add(norm(u));
                        String text = x.getAttributeValue(null, "text");
                        ents.add(new String[]{text == null ? u : text, type == null ? "" : type, norm(u)});
                    }
                }
            }
        } catch (Exception e) { out = null; }
        synchronized (tocCache) { tocCache.put(tocUrl, out); tocEntries.put(tocUrl, out == null ? null : ents); }
        return out;
    }

    static String norm(String u) {
        int h = u.indexOf('#');
        return h >= 0 ? u.substring(0, h) : u;
    }

    static String resolve(String base, String rel) {
        try { return new URL(new URL(base), rel).toString(); } catch (Exception e) { return rel; }
    }

    static String lastSegment(String u) {
        String p = Uri.parse(u).getLastPathSegment();
        return p == null || p.isEmpty() ? Uri.parse(u).getHost() : p;
    }

    // ───────────────────────────── rendering

    void render(Tab t) { render(t, 0); }

    void render(Tab t, int scrollY) {
        Page p = t.page;
        if (p == null) return;
        try {
            JSONObject d = new JSONObject();
            String mode = t.showingSource ? "text" : p.mode;
            d.put("mode", mode);
            d.put("url", p.finalUrl);
            d.put("text", t.showingSource ? p.raw : p.text);
            d.put("name", lastSegment(p.finalUrl));
            d.put("size", humanSize(p.size));
            if (scrollY > 0) d.put("scrollY", scrollY);
            if (!t.showingSource && p.front.get("rightMarginImage") != null) d.put("rightMarginImage", resolve(p.finalUrl, p.front.get("rightMarginImage")));
            if (p.socket != null && !t.showingSource) d.put("socket", p.socket);
            String userCss = prefs.getString("userCss", "");
            String html = template
                    .replace("%%CSS%%", styleCss.getOrDefault(prefs.getString("style", "Reader"), ""))
                    .replace("%%USERCSS%%", userCss.isEmpty() ? "" : "<link rel=\"stylesheet\" href=\"" + userCss.replace("\"", "%22") + "\">")
                    .replace("%%MDIT%%", "markdown".equals(mode) ? mdit : "")
                    .replace("%%DATA%%", d.toString().replace("</", "<\\/"));
            String base = HOME.equals(p.finalUrl) ? "https://mdweb.invalid/" : p.finalUrl;
            t.web.loadDataWithBaseURL(base, html, "text/html", "utf-8", null);
        } catch (Exception e) {
            toast("Couldn't show the page: " + e.getMessage());
        }
        label(t, t.showingSource ? "Source" : p.title);
        showAddress(t);
        if (t == active) showArrows();
        saveTabs();
    }

    static String humanSize(long n) {
        if (n < 1024) return n + " bytes";
        if (n < 1024 * 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            // the viewer handles clicks itself; anything that slips through goes to the app's navigation
            Tab t = tabFor(view);
            if (t != null) go(t, req.getUrl().toString(), "link", true);
            return true;
        }

        /** Pictures only, and only PNG, JPEG, GIF and WebP, judged by the bytes, not the name (charter). */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            String u = req.getUrl().toString();
            String userCss = prefs.getString("userCss", "");
            if (!userCss.isEmpty() && u.equals(userCss)) return null;
            if (!(u.startsWith("http://") || u.startsWith("https://"))) return empty();
            try {
                Resp r = fetch(u, "image/png, image/jpeg, image/gif, image/webp");
                String mime = sniff(r.body);
                if (r.code == 200 && mime != null) return new WebResourceResponse(mime, null, new ByteArrayInputStream(r.body));
            } catch (Exception ignored) { }
            return empty();
        }

        WebResourceResponse empty() { return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0])); }
    }

    static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    Tab tabFor(WebView v) { for (Tab t : tabs) if (t.web == v) return t; return null; }

    class Bridge {
        final Tab t;
        Bridge(Tab t) { this.t = t; }

        @JavascriptInterface
        public void open(String url, String kind) { ui.post(() -> go(t, url, "download".equals(kind) ? "download" : "link", true)); }

        @JavascriptInterface
        public void updated(String url) {
            ui.post(() -> {
                if (t.page != null && url.equals(t.page.finalUrl) && !t.showingSource) go(t, t.url(), "update", false);
            });
        }
    }

    boolean linkMenu(Tab t) {
        WebView.HitTestResult h = t.web.getHitTestResult();
        if (h == null || h.getExtra() == null) return false;
        int type = h.getType();
        String url;
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE) url = h.getExtra();
        else if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            android.os.Message msg = ui.obtainMessage();
            t.web.requestFocusNodeHref(msg);
            url = msg.getData().getString("url");
            if (url == null) url = h.getExtra();
        } else return false;
        final String link = url;
        new AlertDialog.Builder(this).setTitle(link)
                .setItems(new String[]{"Open in new tab", "Copy link address", "Open in phone's browser", "Share link"}, (dlg, which) -> {
                    if (which == 0) newTab(link, true);
                    else if (which == 1) copy(link);
                    else if (which == 2) external(link);
                    else share(link);
                }).show();
        return true;
    }

    // ───────────────────────────── menus

    void styleMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        String cur = prefs.getString("style", "Reader");
        int i = 0;
        for (String name : styleCss.keySet()) {
            m.getMenu().add(0, i++, 0, name).setCheckable(true).setChecked(name.equals(cur));
        }
        m.getMenu().setGroupCheckable(0, true, true);
        m.setOnMenuItemClickListener(item -> {
            prefs.edit().putString("style", item.getTitle().toString()).apply();
            for (Tab t : tabs) if (t.page != null) render(t, t.web.getScrollY());
            return true;
        });
        m.show();
    }

    void mainMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu mm = m.getMenu();
        boolean marked = active != null && isBookmarked(active.url());
        String[] items = {"Home", marked ? "Remove bookmark" : "Add bookmark", "Bookmarks…", "History…", "Find in page",
                "View source", "Reload", "Open in phone's browser", "Share link", "Your own stylesheet…", "Close tab", "About",
                "Calendar…"};
        boolean cal = !calendarMonths().isEmpty();
        for (int i = 0; i < items.length; i++) if (i != 12 || cal) mm.add(0, i, i == 12 ? 2 : i, items[i]);
        m.setOnMenuItemClickListener(item -> {
            if (active == null) return true;
            String u = active.url();
            switch (item.getItemId()) {
                case 0: go(active, HOME, "typed", true); break;
                case 1: if (marked) removeBookmark(u); else addBookmark(active.page != null ? active.page.title : u, u); break;
                case 2: bookmarksDialog(); break;
                case 3: historyDialog(); break;
                case 4: findBar.setVisibility(View.VISIBLE); findText.requestFocus(); break;
                case 5: if (active.page != null) { active.showingSource = !active.showingSource; render(active); } break;
                case 6: go(active, u, "reload", false); break;
                case 7: if (!HOME.equals(u)) external(u); break;
                case 8: if (!HOME.equals(u)) share(u); break;
                case 9: userCssDialog(); break;
                case 10: closeTab(active); break;
                case 11: about(); break;
                case 12: calendarDialog(); break;
            }
            return true;
        });
        m.show();
    }

    void about() {
        new AlertDialog.Builder(this).setTitle("MD Web 1.2")
                .setMessage("A reader for the web of Markdown pages, built from the open charter Dave Winer published for BonBon. "
                        + "Pages are Markdown; any HTML in them is thrown away and no page code runs. You choose how pages look.\n\n"
                        + "Markdown rendering: markdown-it 14.1.0 and markdown-it-emoji 2.0.2 (MIT).")
                .setPositiveButton("OK", null)
                .setNeutralButton("Read the charter", (d, w) -> go(active, "https://raw.githubusercontent.com/scripting/bonBon/main/docs/charter.md", "typed", true))
                .show();
    }

    void userCssDialog() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setText(prefs.getString("userCss", ""));
        e.setHint("https://example.com/my.css");
        new AlertDialog.Builder(this).setTitle("Your own stylesheet")
                .setMessage("The address of a CSS file. Its rules win over the chosen style.")
                .setView(e)
                .setPositiveButton("Use it", (d, w) -> { prefs.edit().putString("userCss", e.getText().toString().trim()).apply(); rerenderAll(); })
                .setNeutralButton("None", (d, w) -> { prefs.edit().remove("userCss").apply(); rerenderAll(); })
                .setNegativeButton("Cancel", null).show();
    }

    void rerenderAll() { for (Tab t : tabs) if (t.page != null) render(t, t.web.getScrollY()); }

    void findNext(boolean forward) {
        if (active == null) return;
        String q = findText.getText().toString();
        if (q.isEmpty()) return;
        if (!q.equals(findText.getTag())) { findText.setTag(q); active.web.findAllAsync(q); }
        else active.web.findNext(forward);
        hideKeyboard();
    }

    void closeFind() {
        findBar.setVisibility(View.GONE);
        findText.setTag(null);
        if (active != null) active.web.clearMatches();
        hideKeyboard();
    }

    // ───────────────────────────── bookmarks + history (SharedPreferences, JSON)

    JSONArray list(String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    boolean isBookmarked(String u) {
        JSONArray a = list("bookmarks");
        for (int i = 0; i < a.length(); i++) if (u.equals(a.optJSONObject(i).optString("u"))) return true;
        return false;
    }

    void addBookmark(String title, String u) {
        if (HOME.equals(u) || isBookmarked(u)) return;
        JSONArray a = list("bookmarks");
        try { a.put(new JSONObject().put("t", title).put("u", u)); } catch (Exception ignored) { }
        prefs.edit().putString("bookmarks", a.toString()).apply();
        refreshBookmarkBar();
        toast("Bookmarked");
    }

    void removeBookmark(String u) {
        JSONArray a = list("bookmarks"), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!u.equals(a.optJSONObject(i).optString("u"))) b.put(a.optJSONObject(i));
        prefs.edit().putString("bookmarks", b.toString()).apply();
        refreshBookmarkBar();
    }

    void seedBookmarks() {
        if (prefs.contains("bookmarks")) return;
        JSONArray a = new JSONArray();
        String[][] seeds = {{"Dave", "https://dave.writersweb.org/"}, {"Scripting News", "https://dave.writersweb.org/scripting/"},
                {"inessential", "https://brent.writersweb.org/"}, {"Bloatware", "https://demo.bloatware.org/"}};
        try { for (String[] s : seeds) a.put(new JSONObject().put("t", s[0]).put("u", s[1])); } catch (Exception ignored) { }
        prefs.edit().putString("bookmarks", a.toString()).apply();
    }

    void refreshBookmarkBar() {
        seedBookmarks();
        bookmarkBar.removeAllViews();
        JSONArray a = list("bookmarks");
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String u = o.optString("u"), t = o.optString("t");
            TextView chip = new TextView(this);
            chip.setText(t);
            chip.setTextSize(13);
            chip.setSingleLine(true);
            chip.setMaxWidth(dp(160));
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setPadding(dp(10), dp(4), dp(10), dp(6));
            chip.setOnClickListener(v -> { if (active != null) go(active, u, "typed", true); });
            chip.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(t).setMessage(u)
                        .setPositiveButton("Remove bookmark", (d, w) -> removeBookmark(u))
                        .setNeutralButton("Open in new tab", (d, w) -> newTab(u, true))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            bookmarkBar.addView(chip);
        }
    }

    void bookmarksDialog() {
        JSONArray a = list("bookmarks");
        if (a.length() == 0) { toast("No bookmarks yet"); return; }
        String[] names = new String[a.length()];
        for (int i = 0; i < a.length(); i++) names[i] = a.optJSONObject(i).optString("t");
        new AlertDialog.Builder(this).setTitle("Bookmarks")
                .setItems(names, (d, w) -> go(active, a.optJSONObject(w).optString("u"), "typed", true))
                .setNeutralButton("Edit…", (d, w) -> editBookmarksDialog()).show();
    }

    void editBookmarksDialog() {
        JSONArray a = list("bookmarks");
        if (a.length() == 0) { toast("No bookmarks yet"); return; }
        String[] names = new String[a.length()];
        for (int i = 0; i < a.length(); i++) names[i] = a.optJSONObject(i).optString("t");
        new AlertDialog.Builder(this).setTitle("Edit bookmarks (tap one)")
                .setItems(names, (d, w) -> editBookmark(w)).setNegativeButton("Done", null).show();
    }

    void editBookmark(int i) {
        JSONArray a = list("bookmarks");
        JSONObject o = a.optJSONObject(i);
        if (o == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText name = new EditText(this); name.setSingleLine(true); name.setText(o.optString("t")); name.setHint("Name");
        EditText addr = new EditText(this); addr.setSingleLine(true); addr.setText(o.optString("u")); addr.setHint("Address");
        addr.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name); box.addView(addr);
        new AlertDialog.Builder(this).setTitle("Bookmark").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    try { o.put("t", name.getText().toString().trim()).put("u", addr.getText().toString().trim()); a.put(i, o); } catch (Exception ignored) { }
                    prefs.edit().putString("bookmarks", a.toString()).apply(); refreshBookmarkBar(); editBookmarksDialog();
                })
                .setNeutralButton(i > 0 ? "Move left" : "Delete", (d, w) -> {
                    JSONArray b = new JSONArray();
                    if (i > 0) {
                        for (int k = 0; k < a.length(); k++) b.put(k == i - 1 ? a.opt(i) : k == i ? a.opt(i - 1) : a.opt(k));
                    } else for (int k = 1; k < a.length(); k++) b.put(a.opt(k));
                    prefs.edit().putString("bookmarks", b.toString()).apply(); refreshBookmarkBar(); editBookmarksDialog();
                })
                .setNegativeButton(i > 0 ? "Delete" : "Cancel", (d, w) -> {
                    if (i == 0) return;
                    JSONArray b = new JSONArray();
                    for (int k = 0; k < a.length(); k++) if (k != i) b.put(a.opt(k));
                    prefs.edit().putString("bookmarks", b.toString()).apply(); refreshBookmarkBar(); editBookmarksDialog();
                }).show();
    }

    /** the site's table of contents by date (toc.opml calendarMonth / calendarDay lines), newest first as the site has it */
    List<String[]> calendarEntries() {
        if (active == null || active.page == null || active.page.tocUrl == null) return new ArrayList<>();
        List<String[]> e;
        synchronized (tocCache) { e = tocEntries.get(active.page.tocUrl); }
        return e == null ? new ArrayList<>() : e;
    }

    List<Integer> calendarMonths() {
        List<Integer> out = new ArrayList<>();
        List<String[]> e = calendarEntries();
        for (int i = 0; i < e.size(); i++) if ("calendarMonth".equals(e.get(i)[1])) out.add(i);
        return out;
    }

    void calendarDialog() {
        List<String[]> e = calendarEntries();
        List<Integer> months = calendarMonths();
        if (months.isEmpty()) { toast("This site has no calendar."); return; }
        String[] names = new String[months.size()];
        for (int k = 0; k < months.size(); k++) names[k] = e.get(months.get(k))[0];
        new AlertDialog.Builder(this).setTitle("Calendar").setItems(names, (d, w) -> {
            int mi = months.get(w);
            List<String[]> days = new ArrayList<>();
            days.add(new String[]{"The whole month", "", e.get(mi)[2]});
            for (int j = mi + 1; j < e.size() && !"calendarMonth".equals(e.get(j)[1]); j++)
                if ("calendarDay".equals(e.get(j)[1])) days.add(e.get(j));
            String[] dn = new String[days.size()];
            for (int k = 0; k < days.size(); k++) dn[k] = days.get(k)[0];
            new AlertDialog.Builder(this).setTitle(e.get(mi)[0])
                    .setItems(dn, (d2, w2) -> go(active, days.get(w2)[2], "link", true)).show();
        }).show();
    }

    void addHistory(Page p) {
        if (HOME.equals(p.url)) return;
        JSONArray a = list("history"), b = new JSONArray();
        try { b.put(new JSONObject().put("t", p.title).put("u", p.url)); } catch (Exception ignored) { }
        for (int i = 0; i < a.length() && b.length() < 200; i++)
            if (!p.url.equals(a.optJSONObject(i).optString("u"))) b.put(a.optJSONObject(i));
        prefs.edit().putString("history", b.toString()).apply();
    }

    void historyDialog() {
        JSONArray a = list("history");
        if (a.length() == 0) { toast("No history yet"); return; }
        String[] names = new String[a.length()];
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            names[i] = o.optString("t") + "\n" + o.optString("u");
        }
        new AlertDialog.Builder(this).setTitle("History")
                .setItems(names, (d, w) -> go(active, a.optJSONObject(w).optString("u"), "typed", true))
                .setNeutralButton("Clear history", (d, w) -> prefs.edit().remove("history").apply()).show();
    }

    void saveTabs() {
        JSONArray a = new JSONArray();
        for (Tab t : tabs) a.put(t.pendingUrl != null ? t.pendingUrl : t.url());
        prefs.edit().putString("tabs", a.toString()).putInt("activeTab", Math.max(0, tabs.indexOf(active))).apply();
    }

    void restoreTabs() {
        JSONArray a = list("tabs");
        if (a.length() == 0) { newTab(HOME, true); return; }
        int act = Math.min(prefs.getInt("activeTab", 0), a.length() - 1);
        for (int i = 0; i < a.length(); i++) newTab(a.optString(i, HOME), false);
        select(tabs.get(act));
    }

    // ───────────────────────────── helpers

    void external(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { toast("No app can open " + url); }
    }

    void share(String url) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(i, "Share link"));
    }

    void copy(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", s));
        toast("Copied");
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (f != null) im.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    String asset(String name) {
        try (InputStream in = getAssets().open(name)) { return new String(readAll(in, MAX_BYTES), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new Exception("file too big");
        }
        return o.toByteArray();
    }

    static class Resp {
        int code; String message, finalUrl, contentType = "", contentLocation; byte[] body = new byte[0];

        String type() {
            String t = contentType == null ? "" : contentType;
            int s = t.indexOf(';');
            return (s >= 0 ? t.substring(0, s) : t).trim().toLowerCase(Locale.US);
        }

        String text() {
            Charset cs = StandardCharsets.UTF_8;
            Matcher m = Pattern.compile("(?i)charset=\"?([A-Za-z0-9_.:-]+)").matcher(contentType == null ? "" : contentType);
            if (m.find()) try { cs = Charset.forName(m.group(1)); } catch (Exception ignored) { }
            return new String(body, cs);
        }
    }

    /** GET with the charter's Accept header; follows up to 6 redirects, http and https both. */
    static Resp fetch(String url, String accept) throws Exception {
        String cur = url;
        for (int hop = 0; hop < 7; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                cur = new URL(new URL(cur), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            Resp r = new Resp();
            r.code = code;
            r.message = c.getResponseMessage();
            r.finalUrl = cur;
            r.contentType = c.getContentType() == null ? "" : c.getContentType();
            r.contentLocation = c.getHeaderField("Content-Location");
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) try (InputStream s = in) { r.body = readAll(s, MAX_BYTES); }
            c.disconnect();
            return r;
        }
        throw new Exception("too many redirects");
    }
}
