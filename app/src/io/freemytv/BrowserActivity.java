package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AR2's browser: one Chrome-style tab (tab strip, address bar, back/forward/reload/home), driven by the remote.
 * The arrows move a mouse pointer (faster the longer they're held; at the screen's edge they scroll the page),
 * OK clicks, Back goes back, the media keys play and pause the video. The built-in ad blocker (AdBlock: the AdGuard
 * DNS filter, plus pop-up and redirect blocking) is always on, separate from the TV-wide AdGuard DNS switch.
 *
 * Its own process (:web), opened only from the AR2 tab after the PIN, and kept out of Recents so the PIN can't be
 * skipped.
 */
public class BrowserActivity extends Activity {
    private static final String PREFS = "ar2-browser";

    private WebView web;
    private FrameLayout webHolder, fullHolder;
    private Bar bar;
    private Pointer pointer;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private final Handler main = new Handler(Looper.getMainLooper());
    private float k = 1;

    // Page state, for the bar
    private String url = "", title = "";
    private Bitmap favicon;
    private int progress = 100;
    private int blockedOnPage, blockedTotal;

    // The pointer
    private float px, py;
    private boolean left, right, up, down;
    private long heldSince, lastFrame, lastInput;
    private float scrollX, scrollY;
    private boolean okDown;
    private boolean framing;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        AdBlock.load(this);
        AdBlock.refreshIfOld(this);
        k = Math.min(getResources().getDisplayMetrics().widthPixels / 1920f, getResources().getDisplayMetrics().heightPixels / 1080f);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF202124);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        bar = new Bar(this);
        column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(Bar.H * k)));
        webHolder = new FrameLayout(this);
        column.addView(webHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        fullHolder = new FrameLayout(this);
        fullHolder.setBackgroundColor(Color.BLACK);
        fullHolder.setVisibility(View.GONE);
        root.addView(fullHolder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pointer = new Pointer(this);
        root.addView(pointer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        web = new WebView(this);
        setUp(web);
        webHolder.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        px = w / 2f;
        py = h / 2f;
        pointer.showHint();

        String start = prefs().getString("last", "");
        if (start.isEmpty()) start = home();
        if (start.isEmpty()) {
            main.postDelayed(this::askAddress, 400);
        } else {
            open(start);
        }
        main.postDelayed(keepAwake, 15_000);
    }

    /** Every page load goes through here: the app allows no plain-HTTP traffic, so http:// becomes https://. */
    private void open(String target) {
        if (web == null || target == null || target.isEmpty()) return;
        web.loadUrl(target.replaceFirst("(?i)^http://", "https://"));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String home() {
        String h = prefs().getString("home", "");
        return h.isEmpty() ? Config.AR2_URL.trim() : h;
    }

    private boolean desktop() {
        return prefs().getBoolean("desktop", true);
    }

    // MARK: - WebView

    private void setUp(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUserAgentString(userAgent(s));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(Color.WHITE);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        w.setWebViewClient(new Client());
        w.setWebChromeClient(new Chrome());
        w.setDownloadListener((u, ua, cd, mime, len) ->
                toast(Ui.tr("Downloads ficam desligados no AR2.", "Downloads are off in AR2.")));
    }

    /** Chrome's own user agent, without the WebView marks (some sites refuse "wv"); desktop = Chrome on Windows. */
    private String userAgent(WebSettings s) {
        String ua = WebSettings.getDefaultUserAgent(this);
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
        String chrome = m.find() ? m.group(1) : "120.0.0.0";
        if (desktop()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome + " Safari/537.36";
        }
        return ua.replace("; wv", "").replace("Version/4.0 ", "");
    }

    private final class Client extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
            if (r.isForMainFrame()) return null;
            String host = r.getUrl().getHost();
            if (AdBlock.blocked(host)) {
                main.post(() -> countBlocked());
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
            Uri u = r.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                // intent://, market://, app links: ads use them to jump out of the browser.
                if (!scheme.equals("about") && !scheme.equals("data") && !scheme.equals("blob") && !scheme.equals("javascript")) {
                    countBlocked();
                    return true;
                }
                return false;
            }
            if (!r.isForMainFrame()) return false;
            String host = u.getHost();
            if (AdBlock.blocked(host)) {
                countBlocked();
                toast(Ui.tr("Anúncio bloqueado: ", "Ad blocked: ") + host);
                return true;
            }
            // Scripts that send a loaded page to another site without a click: the usual ad redirect. (While the page
            // is still loading it's let through: that's how sites move to their new address.)
            String here = web.getUrl() == null ? null : Uri.parse(web.getUrl()).getHost();
            if (!r.hasGesture() && !r.isRedirect() && progress >= 100 && here != null && !sameSite(host, here)) {
                countBlocked();
                toast(Ui.tr("Redirecionamento bloqueado: ", "Redirect blocked: ") + host);
                AppLog.w("AR2 blocked a redirect to " + u);
                return true;
            }
            if (scheme.equals("http")) {   // the app allows no plain-HTTP traffic: try the same page over HTTPS
                view.loadUrl(u.buildUpon().scheme("https").build().toString());
                return true;
            }
            return false;
        }

        @Override public void onPageStarted(WebView view, String u, Bitmap icon) {
            url = u == null ? "" : u;
            favicon = icon;
            blockedOnPage = 0;
            bar.invalidate();
        }

        @Override public void onPageCommitVisible(WebView view, String u) {
            hideAds(view);
        }

        @Override public void onPageFinished(WebView view, String u) {
            url = u == null ? "" : u;
            hideAds(view);
            bar.invalidate();
        }

        @Override public void doUpdateVisitedHistory(WebView view, String u, boolean reload) {
            url = u == null ? "" : u;
            bar.invalidate();
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page's renderer died (out of memory on a busy page): start the tab again instead of crashing.
            AppLog.w("AR2 renderer gone, crashed=" + detail.didCrash());
            String last = url;
            webHolder.removeView(web);
            web.destroy();
            web = new WebView(BrowserActivity.this);
            setUp(web);
            webHolder.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (!last.isEmpty()) open(last);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override public void onProgressChanged(WebView view, int p) {
            progress = p;
            bar.invalidate();
        }

        @Override public void onReceivedTitle(WebView view, String t) {
            title = t == null ? "" : t;
            bar.invalidate();
        }

        @Override public void onReceivedIcon(WebView view, Bitmap icon) {
            favicon = icon;
            bar.invalidate();
        }

        @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
            if (!gesture) {
                countBlocked();
                return false;
            }
            // A link that opens a new tab: catch where it goes. Same site and not an ad: open it here; else block it.
            WebView temp = new WebView(BrowserActivity.this);
            final boolean[] done = {false};
            Runnable drop = () -> {   // went nowhere in 8 s: drop it
                if (done[0]) return;
                done[0] = true;
                countBlocked();
                temp.destroy();
            };
            temp.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    decide(r.getUrl().toString());
                    return true;
                }

                @Override public void onPageStarted(WebView v, String u, Bitmap f) {
                    if (u != null && !u.startsWith("about:")) decide(u);
                }

                @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                    done[0] = true;
                    return true;   // never take the app down with it
                }

                private void decide(String target) {
                    if (done[0]) return;
                    done[0] = true;
                    Uri t = Uri.parse(target);
                    String host = t.getHost();
                    String here = Uri.parse(web.getUrl() == null ? "" : web.getUrl()).getHost();
                    boolean web2 = "https".equals(t.getScheme()) || "http".equals(t.getScheme());
                    if (!web2 || AdBlock.blocked(host)) {
                        countBlocked();
                        toast(Ui.tr("Pop-up bloqueado", "Pop-up blocked") + (host == null ? "" : ": " + host));
                    } else if (sameSite(host, here)) {
                        open(target);
                    } else {
                        askToOpen(target, host);   // another site after a click: the person decides
                    }
                    main.post(temp::destroy);
                }
            });
            ((WebView.WebViewTransport) result.obj).setWebView(temp);
            result.sendToTarget();
            main.postDelayed(drop, 8000);
            return true;
        }

        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullHolder.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            fullHolder.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            pointer.idleHide();
        }

        @Override public void onHideCustomView() {
            leaveFullscreen();
        }

        @Override public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);   // no grey play icon before a video starts
        }

        @Override public void onPermissionRequest(PermissionRequest request) {
            // Protected video (Widevine) yes, so paid streams play; camera and microphone never.
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                    request.grant(new String[] {PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                    return;
                }
            }
            request.deny();
        }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override public boolean onJsBeforeUnload(WebView view, String u, String message, JsResult result) {
            result.confirm();
            return true;
        }
    }

    private void leaveFullscreen() {
        if (customView == null) return;
        fullHolder.removeView(customView);
        fullHolder.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pointer.show();
    }

    private void countBlocked() {
        blockedOnPage++;
        blockedTotal++;
        bar.invalidate();
    }

    /** Hides the usual ad boxes that come from the page's own domain (the blocker can't tell those apart by host). */
    private static final String HIDE_ADS = "(function(){if(document.getElementById('__ar2_ab'))return;var s=document.createElement('style');"
            + "s.id='__ar2_ab';s.textContent='ins.adsbygoogle,.adsbygoogle,[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],"
            + "iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication\"],iframe[id^=\"aswift_\"],div[id^=\"taboola-\"],"
            + ".trc_rbox_container,.OUTBRAIN,div[id^=\"ezoic-pub-ad\"],.ad-slot,.adslot,.ad-banner,.banner-ad,.advertisement"
            + "{display:none!important}';(document.head||document.documentElement).appendChild(s);})()";

    private static void hideAds(WebView v) {
        v.evaluateJavascript(HIDE_ADS, null);
    }

    /** Same site: the last two labels of the host match (three for "co.uk", "com.bd" and the like). */
    static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        return site(a).equals(site(b));
    }

    private static String site(String host) {
        String[] p = host.toLowerCase(Locale.ROOT).split("\\.");
        int n = p.length;
        if (n <= 2) return host.toLowerCase(Locale.ROOT);
        boolean shortSecond = p[n - 1].length() == 2 && p[n - 2].length() <= 3;
        int keep = shortSecond ? 3 : 2;
        return TextUtils.join(".", java.util.Arrays.copyOfRange(p, n - keep, n));
    }

    // MARK: - Keeping the TV awake while a video plays

    private static final String PLAYING = "(function(){var p=false;document.querySelectorAll('video').forEach(function(v){"
            + "if(!v.paused&&!v.ended&&v.readyState>2)p=true});return p})()";

    private final Runnable keepAwake = new Runnable() {
        @Override public void run() {
            if (web != null && customView == null) {
                web.evaluateJavascript(PLAYING, result -> {
                    boolean on = "true".equals(result) || SystemClock.uptimeMillis() - lastInput < 20 * 60_000;
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
            }
            main.postDelayed(this, 15_000);
        }
    };

    // MARK: - Remote

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean dn = e.getAction() == KeyEvent.ACTION_DOWN, upE = e.getAction() == KeyEvent.ACTION_UP;
        lastInput = SystemClock.uptimeMillis();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
                arrow(code, dn);
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Enter from a keyboard (or the on-screen one) submits the field; a remote's OK sent as Enter clicks.
                if ((e.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) != 0
                        || (e.getDevice() != null && e.getDevice().getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC)) {
                    return super.dispatchKeyEvent(e);
                }
                // fall through
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                if (dn && e.getRepeatCount() == 0) {
                    okDown = true;
                    pointer.press(true);
                } else if (upE && okDown) {
                    okDown = false;
                    pointer.press(false);
                    click();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (upE) back();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (dn) scrollBy(0, -webHolder.getHeight() * 0.8f);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (dn) scrollBy(0, webHolder.getHeight() * 0.8f);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_BOOKMARK:
            case KeyEvent.KEYCODE_PROG_RED:
                if (upE) menu();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (dn && e.getRepeatCount() == 0) media("'toggle'", e);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (dn && e.getRepeatCount() == 0) media("'play'", e);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (dn && e.getRepeatCount() == 0) media("'pause'", e);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD:
                if (dn) media("10", e);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
                if (dn) media("-10", e);
                return true;
            default:
                return super.dispatchKeyEvent(e);   // letters, numbers, delete: to the page
        }
    }

    /** Mice and air mice still work as they are; their wheel scrolls the page. */
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            px = e.getRawX();
            py = e.getRawY();
            pointer.show();
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void arrow(int code, boolean pressed) {
        boolean l = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN_LEFT;
        boolean r = code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT;
        boolean u = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_UP_LEFT || code == KeyEvent.KEYCODE_DPAD_UP_RIGHT;
        boolean d = code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_DOWN_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT;
        boolean was = left || right || up || down;
        boolean already = (l && left) || (r && right) || (u && up) || (d && down);
        if (l) left = pressed;
        if (r) right = pressed;
        if (u) up = pressed;
        if (d) down = pressed;
        pointer.show();
        if (pressed && !already) {
            // One press: one small step, right away. Held: the frame loop takes over and speeds up.
            float step = 22 * k;
            move((r ? step : 0) - (l ? step : 0), (d ? step : 0) - (u ? step : 0), true);
            if (!was) heldSince = SystemClock.uptimeMillis();
            startFrames();
        }
    }

    private void startFrames() {
        if (framing) return;
        framing = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long nanos) {
            long now = SystemClock.uptimeMillis();
            boolean held = left || right || up || down;
            if (held && lastFrame != 0 && now - heldSince > 220) {
                float t = (now - heldSince - 220) / 1000f;
                float speed = Math.min(1500f, 420f + 1500f * t) * k;   // px per second
                float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
                float dx = ((right ? 1 : 0) - (left ? 1 : 0)) * speed * dt;
                float dy = ((down ? 1 : 0) - (up ? 1 : 0)) * speed * dt;
                move(dx, dy, false);
            }
            flushScroll();
            lastFrame = now;
            if (held || scrollX != 0 || scrollY != 0 || dragging) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                framing = false;
            }
        }
    };

    /**
     * Moves the pointer. Pushing against an edge of the page scrolls it instead: the bottom, left and right edges of
     * the screen, and the top edge of the page (just under the bar). There, holding ↑ scrolls up; a single short ↑
     * steps up into the bar (back, address, menu).
     */
    private void move(float dx, float dy, boolean tap) {
        float w = pointer.getWidth(), h = pointer.getHeight();
        if (w <= 0 || h <= 0) return;
        float nx = px + dx, ny = py + dy;
        float edge = 2;
        if (customView == null) {
            float chunk = 160 * k;   // what one short press at an edge scrolls
            if (nx > w - edge && dx > 0) scrollX += tap ? chunk : (nx - (w - edge)) * 1.8f;
            if (nx < edge && dx < 0) scrollX -= tap ? chunk : (edge - nx) * 1.8f;
            if (ny > h - edge && dy > 0) scrollY += tap ? chunk : (ny - (h - edge)) * 1.8f;
            float pageTop = bar.getHeight();
            if (dy < 0 && py >= pageTop && ny < pageTop) {
                if (tap && py <= pageTop + 1) {
                    ny = rowCenter();   // short press at the top of the page: into the bar
                } else {
                    scrollY -= tap ? chunk : (pageTop - ny) * 1.8f;
                    ny = pageTop;
                }
            }
            if (py < pageTop && ny < edge && dy < 0) ny = edge;   // already in the bar: stop at the top
        }
        px = Math.max(0, Math.min(w - 1, nx));
        py = Math.max(0, Math.min(h - 1, ny));
        pointer.invalidate();
        bar.invalidate();
        hover();
    }

    private float rowCenter() {
        return (Bar.STRIP + (Bar.H - Bar.STRIP) / 2) * k;
    }

    private void scrollBy(float dx, float dy) {
        scrollX += dx;
        scrollY += dy;
        startFrames();
    }

    // Scrolling is a finger drag on the page, like on a phone: it reaches every scrolling part of the page, also
    // inside other sites' frames (video players, comment boxes) that a script can't touch.
    private boolean dragging;
    private float dragX, dragY;
    private long dragDown, dragLast;

    /** Moves the page by (part of) what's waiting, a little each frame so long scrolls are smooth. */
    private void flushScroll() {
        long now = SystemClock.uptimeMillis();
        if (scrollX == 0 && scrollY == 0) {
            if (dragging && now - dragLast > 130) endDrag(now);   // still long enough before lifting: no fling
            return;
        }
        if (web == null || customView != null || web.getWidth() == 0) {
            scrollX = scrollY = 0;
            return;
        }
        float max = 70 * k;
        float sx = Math.max(-max, Math.min(max, scrollX)), sy = Math.max(-max, Math.min(max, scrollY));
        scrollX -= sx;
        scrollY -= sy;
        if (Math.abs(scrollX) < 0.5f) scrollX = 0;
        if (Math.abs(scrollY) < 0.5f) scrollY = 0;
        float fx = -sx, fy = -sy;   // the finger moves the other way
        float w = web.getWidth(), h = web.getHeight(), in = 24 * k;
        if (!dragging) startDrag(now, fx, fy, w, h, in);
        float nx = dragX + fx, ny = dragY + fy;
        if (nx < in || nx > w - in || ny < in || ny > h - in) {
            // Out of room on the screen: lift and put the finger down again on the other side.
            endDrag(now);
            startDrag(now, fx, fy, w, h, in);
            nx = dragX + fx;
            ny = dragY + fy;
        }
        dragX = nx;
        dragY = ny;
        dragLast = now;
        touch(MotionEvent.ACTION_MOVE, dragDown, now, dragX, dragY);
    }

    private void startDrag(long now, float fx, float fy, float w, float h, float in) {
        int[] at = new int[2];
        web.getLocationOnScreen(at);
        float x = Math.max(in, Math.min(w - in, px - at[0]));
        float y = Math.max(in, Math.min(h - in, py - at[1]));
        if (fy < 0 && y < h * 0.6f) y = h * 0.8f;   // scrolling down: start low, drag up
        if (fy > 0 && y > h * 0.4f) y = h * 0.2f;   // scrolling up: start high, drag down
        if (fx < 0 && x < w * 0.6f) x = w * 0.8f;
        if (fx > 0 && x > w * 0.4f) x = w * 0.2f;
        dragX = x;
        dragY = y;
        dragDown = now;
        dragLast = now;
        dragging = true;
        touch(MotionEvent.ACTION_DOWN, now, now, x, y);
    }

    private void endDrag(long now) {
        if (!dragging) return;
        dragging = false;
        touch(MotionEvent.ACTION_UP, dragDown, Math.max(now, dragLast), dragX, dragY);
    }

    private void touch(int action, long down, long time, float x, float y) {
        if (web == null) return;
        MotionEvent m = MotionEvent.obtain(down, time, action, x, y, 0);
        m.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(m);
        m.recycle();
    }

    private long hoverDown;

    /** Mouse hover where the pointer is, so menus that open on hover work. */
    private void hover() {
        View target = customView != null ? fullHolder : web;
        if (target == null || dragging || py < bar.getHeight() && customView == null) return;
        int[] at = new int[2];
        target.getLocationOnScreen(at);
        long now = SystemClock.uptimeMillis();
        if (hoverDown == 0) hoverDown = now;
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = px - at[0];
        pc[0].y = py - at[1];
        MotionEvent m = MotionEvent.obtain(hoverDown, now, MotionEvent.ACTION_HOVER_MOVE, 1, pp, pc, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        target.dispatchGenericMotionEvent(m);
        m.recycle();
    }

    /** OK: a tap where the pointer is (on the page, the full-screen video, or the bar). */
    private void click() {
        if (customView == null && py < bar.getHeight()) {
            bar.click(px, py);
            return;
        }
        View target = customView != null ? fullHolder : web;
        if (target == null) return;
        endDrag(SystemClock.uptimeMillis());
        int[] at = new int[2];
        target.getLocationOnScreen(at);
        float x = px - at[0], y = py - at[1];
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, y, 0);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(u);
        u.recycle();
        if (customView == null) web.requestFocus();
        if (customView != null) pointer.idleHide();
    }

    private void back() {
        if (customView != null) {
            leaveFullscreen();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    /** Play/pause and seek on the page's biggest video; videos in other sites' frames get the key itself. */
    private void media(String action, KeyEvent key) {
        if (web == null) return;
        String js = "(function(a){var v=null,b=0;document.querySelectorAll('video').forEach(function(x){var r=x.getBoundingClientRect(),"
                + "s=r.width*r.height;if(s>=b){b=s;v=x;}});if(!v)return false;if(a==='toggle'){if(v.paused)v.play();else v.pause();}"
                + "else if(a==='play')v.play();else if(a==='pause')v.pause();else v.currentTime=Math.max(0,v.currentTime+a);return true;})(" + action + ")";
        KeyEvent down = new KeyEvent(key.getDownTime(), key.getEventTime(), KeyEvent.ACTION_DOWN, key.getKeyCode(), 0);
        KeyEvent up = new KeyEvent(key.getDownTime(), key.getEventTime(), KeyEvent.ACTION_UP, key.getKeyCode(), 0);
        web.evaluateJavascript(js, result -> {
            if (!"true".equals(result)) {
                View target = customView != null ? customView : web;
                target.dispatchKeyEvent(down);
                target.dispatchKeyEvent(up);
            }
        });
    }

    // MARK: - Address and menu

    private void askAddress() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setHint(Ui.tr("Digite um endereço ou pesquise", "Type an address or search"));
        field.setText(url.startsWith("about:") ? "" : url);
        field.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Endereço", "Address"))
                .setView(field)
                .setPositiveButton(Ui.tr("Abrir", "Go"), (d, w) -> go(field.getText().toString()))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .create();
        field.setOnEditorActionListener((v, action, ev) -> {
            dialog.dismiss();
            go(field.getText().toString());
            return true;
        });
        dialog.show();
        field.requestFocus();
    }

    private void go(String typed) {
        String s = typed.trim();
        if (s.isEmpty()) return;
        String target;
        if (s.matches("(?i)^https?://.*")) {
            target = s.replaceFirst("(?i)^http://", "https://");
        } else if (!s.contains(" ") && s.contains(".")) {
            target = "https://" + s;
        } else {
            target = "https://www.google.com/search?q=" + Uri.encode(s);
        }
        open(target);
        web.requestFocus();
    }

    /** A link that wants a new tab on another site: open it here only if the person says so (ads do this a lot). */
    private void askToOpen(String target, String host) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ui.tr("Abrir outro site?", "Open another site?"))
                .setMessage(host + Ui.tr("\n\nSe você não clicou num link para ele, é anúncio: cancele.",
                        "\n\nIf you didn't click a link to it, it's an ad: cancel."))
                .setPositiveButton(Ui.tr("Abrir", "Open"), (d, w) -> open(target))
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), (d, w) -> countBlocked())
                .show();
    }

    private void menu() {
        String[] items = {
            Ui.tr("Abrir endereço…", "Open address…"),
            Ui.tr("Usar esta página como início do AR2", "Set this page as AR2's home page"),
            (desktop() ? "✓ " : "") + Ui.tr("Versão para computador", "Desktop site"),
            Ui.tr("Aumentar zoom", "Zoom in"),
            Ui.tr("Diminuir zoom", "Zoom out"),
            Ui.tr("Bloqueador AR2: sempre ligado · ", "AR2 ad blocker: always on · ") + blockedTotal
                    + Ui.tr(" bloqueados", " blocked"),
            Ui.tr("Fechar AR2", "Close AR2"),
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(Ar2.NAME)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: askAddress(); break;
                        case 1:
                            if (!url.isEmpty() && !url.startsWith("about:")) {
                                prefs().edit().putString("home", url).apply();
                                toast(Ui.tr("Página inicial do AR2 salva.", "AR2's home page saved."));
                            }
                            break;
                        case 2:
                            prefs().edit().putBoolean("desktop", !desktop()).apply();
                            web.getSettings().setUserAgentString(userAgent(web.getSettings()));
                            web.reload();
                            break;
                        case 3: web.zoomBy(1.2f); break;
                        case 4: web.zoomBy(0.83f); break;
                        case 6: finish(); break;
                        default: break;
                    }
                })
                .show();
    }

    private void toast(String text) {
        main.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    // MARK: - Lifecycle

    @Override protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    /** A key's release can go to a dialog or get lost (Home, a remote that drops): never keep moving after that. */
    private void releaseKeys() {
        left = right = up = down = false;
        okDown = false;
        if (pointer != null) pointer.press(false);
        endDrag(SystemClock.uptimeMillis());
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus) releaseKeys();
    }

    @Override protected void onPause() {
        releaseKeys();
        if (web != null && web.getUrl() != null && !web.getUrl().startsWith("about:")) {
            prefs().edit().putString("last", web.getUrl()).commit();   // commit: the process may end right after
        }
        super.onPause();
    }

    @Override protected void onStop() {
        // Left AR2 (Home button): stop the sound. Coming back needs the AR2 tab and its PIN again.
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            webHolder.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
        // Closed for good: give the TV its memory back (this process only runs AR2's browser).
        if (isFinishing()) main.postAtFrontOfQueue(() -> android.os.Process.killProcess(android.os.Process.myPid()));
    }

    // MARK: - The bar: one tab, then back / forward / reload / home, the address and the menu

    private final class Bar extends View {
        static final float H = 112;   // tab strip 50 + toolbar 62, in 1080p pixels
        private static final float STRIP = 50;
        private static final int STRIP_BG = 0xFF1F1F22, TOOLBAR = 0xFF35363A, FIELD = 0xFF202124;
        private static final int BACK = 1, FWD = 2, RELOAD = 3, HOME = 4, ADDRESS = 5, MENU = 6, CLOSE = 7, SHIELD = 8;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tabText, urlHost, urlRest, small, badgeText;
        private android.text.TextPaint tabFit, hostFit, restFit;
        private final RectF r = new RectF();
        private final Path path = new Path();

        Bar(Context c) {
            super(c);
            tabText = Ui.text(Ui.regular, 21 * k, 0xFFE8EAED);
            urlHost = Ui.text(Ui.regular, 23 * k, 0xFFE8EAED);
            urlRest = Ui.text(Ui.regular, 23 * k, 0xFF9AA0A6);
            small = Ui.text(Ui.medium, 19 * k, 0xFFBDC1C6);
            badgeText = Ui.text(Ui.semibold, 15 * k, 0xFF0B3D1E);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setWillNotDraw(false);
            tabFit = new android.text.TextPaint(tabText);
            hostFit = new android.text.TextPaint(urlHost);
            restFit = new android.text.TextPaint(urlRest);
        }

        // Button centers on the toolbar row
        private float rowY() { return (STRIP + (H - STRIP) / 2) * k; }
        private float bx(int i) { return (44 + (i - 1) * 56) * k; }
        private float addressLeft() { return 262 * k; }
        private float addressRight() { return getWidth() - 160 * k; }
        private float shieldX() { return getWidth() - 110 * k; }
        private float menuX() { return getWidth() - 46 * k; }
        private float tabLeft() { return 14 * k; }
        private float tabRight() { return Math.min(getWidth() * 0.42f, 600 * k); }

        int hit(float x, float y) {
            if (y < STRIP * k) {
                if (x > tabRight() - 46 * k && x < tabRight() - 6 * k) return CLOSE;
                return 0;
            }
            float cy = rowY();
            if (Math.abs(y - cy) > 30 * k) return 0;
            for (int i = BACK; i <= HOME; i++) if (Math.abs(x - bx(i)) < 26 * k) return i;
            if (x > addressLeft() && x < addressRight()) return ADDRESS;
            if (Math.abs(x - shieldX()) < 28 * k) return SHIELD;
            if (Math.abs(x - menuX()) < 26 * k) return MENU;
            return 0;
        }

        void click(float x, float y) {
            switch (hit(x, y)) {
                case BACK: if (web.canGoBack()) web.goBack(); break;
                case FWD: if (web.canGoForward()) web.goForward(); break;
                case RELOAD: if (progress < 100) web.stopLoading(); else web.reload(); break;
                case HOME:
                    String h = home();
                    if (h.isEmpty()) askAddress(); else open(h);
                    break;
                case ADDRESS: askAddress(); break;
                case SHIELD:
                    toast(Ui.tr("Bloqueador AR2 ligado: ", "AR2 ad blocker on: ") + blockedOnPage
                            + Ui.tr(" bloqueados nesta página, ", " blocked on this page, ") + blockedTotal + Ui.tr(" no total", " in total"));
                    break;
                case MENU: menu(); break;
                case CLOSE: finish(); break;
                default: break;
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            int hovered = hit(px, py);

            // Tab strip, with the one tab joined to the toolbar like Chrome's
            fill.setColor(STRIP_BG);
            c.drawRect(0, 0, w, STRIP * k, fill);
            float tl = tabLeft(), tr = tabRight(), top = 8 * k, bottom = STRIP * k, rad = 12 * k;
            path.reset();
            path.moveTo(tl - rad, bottom);
            path.quadTo(tl, bottom, tl, bottom - rad);
            path.lineTo(tl, top + rad);
            path.quadTo(tl, top, tl + rad, top);
            path.lineTo(tr - rad, top);
            path.quadTo(tr, top, tr, top + rad);
            path.lineTo(tr, bottom - rad);
            path.quadTo(tr, bottom, tr + rad, bottom);
            path.close();
            fill.setColor(TOOLBAR);
            c.drawPath(path, fill);
            float iconX = tl + 30 * k, midY = (top + bottom) / 2;
            if (favicon != null) {
                r.set(iconX - 12 * k, midY - 12 * k, iconX + 12 * k, midY + 12 * k);
                c.drawBitmap(favicon, null, r, null);
            } else if (progress < 100) {
                stroke.setColor(0xFF8AB4F8);
                stroke.setStrokeWidth(2.6f * k);
                r.set(iconX - 10 * k, midY - 10 * k, iconX + 10 * k, midY + 10 * k);
                c.drawArc(r, (SystemClock.uptimeMillis() / 3) % 360, 270, false, stroke);
                postInvalidateDelayed(32);
            } else {
                globe(c, iconX, midY, 11 * k, 0xFFBDC1C6);
            }
            String t = !title.isEmpty() ? title : url.isEmpty() ? Ui.tr("Nova guia", "New tab") : host(url);
            float textMax = tr - iconX - 24 * k - 50 * k;
            c.drawText(TextUtils.ellipsize(t, tabFit, textMax, TextUtils.TruncateAt.END).toString(),
                    iconX + 22 * k, midY + Ui.halfCap(tabText), tabText);
            float cx = tr - 26 * k;
            if (hovered == CLOSE) {
                fill.setColor(0x33FFFFFF);
                c.drawCircle(cx, midY, 15 * k, fill);
            }
            cross(c, cx, midY, 6.5f * k, 0xFFBDC1C6);
            // "AR2" label to the right of the tab, so it's clear this is AR2's browser
            c.drawText(Ar2.NAME + Ui.tr(" · navegador", " · browser"), tr + 28 * k, midY + Ui.halfCap(small), small);

            // Toolbar
            fill.setColor(TOOLBAR);
            c.drawRect(0, STRIP * k, w, H * k, fill);
            float cy = rowY();
            boolean canBack = web != null && web.canGoBack(), canFwd = web != null && web.canGoForward();
            for (int i = BACK; i <= HOME; i++) {
                if (hovered == i) {
                    fill.setColor(0x33FFFFFF);
                    c.drawCircle(bx(i), cy, 24 * k, fill);
                }
            }
            arrowIcon(c, bx(BACK), cy, true, canBack ? 0xFFE8EAED : 0xFF6E7175);
            arrowIcon(c, bx(FWD), cy, false, canFwd ? 0xFFE8EAED : 0xFF6E7175);
            if (progress < 100) cross(c, bx(RELOAD), cy, 8 * k, 0xFFE8EAED); else reload(c, bx(RELOAD), cy, 0xFFE8EAED);
            homeIcon(c, bx(HOME), cy, 0xFFE8EAED);

            // Address field
            float al = addressLeft(), ar = addressRight();
            r.set(al, cy - 22 * k, ar, cy + 22 * k);
            fill.setColor(hovered == ADDRESS ? 0xFF2F3033 : FIELD);
            c.drawRoundRect(r, 22 * k, 22 * k, fill);
            if (hovered == ADDRESS) {
                stroke.setColor(0xFF8AB4F8);
                stroke.setStrokeWidth(2 * k);
                c.drawRoundRect(r, 22 * k, 22 * k, stroke);
            }
            boolean secure = url.startsWith("https://");
            if (secure) lock(c, al + 26 * k, cy, 0xFFBDC1C6); else info(c, al + 26 * k, cy, 0xFFBDC1C6);
            float textX = al + 50 * k, maxW = ar - textX - 20 * k;
            if (url.isEmpty() || url.startsWith("about:")) {
                c.drawText(Ui.tr("Digite um endereço ou pesquise", "Type an address or search"), textX, cy + Ui.halfCap(urlRest), urlRest);
            } else {
                Uri u = Uri.parse(url);
                String host = u.getHost() == null ? url : u.getHost();
                String rest = url.substring(Math.min(url.length(), url.indexOf(host) + host.length()));
                if (rest.equals("/")) rest = "";
                String shownHost = TextUtils.ellipsize(host, hostFit, maxW, TextUtils.TruncateAt.END).toString();
                c.drawText(shownHost, textX, cy + Ui.halfCap(urlHost), urlHost);
                float hw = urlHost.measureText(shownHost);
                if (hw < maxW && !rest.isEmpty()) {
                    c.drawText(TextUtils.ellipsize(rest, restFit, maxW - hw, TextUtils.TruncateAt.END).toString(),
                            textX + hw, cy + Ui.halfCap(urlRest), urlRest);
                }
            }

            // The ad blocker's extension icon, always on, with this page's count
            float sx = shieldX();
            if (hovered == SHIELD) {
                fill.setColor(0x33FFFFFF);
                c.drawCircle(sx, cy, 24 * k, fill);
            }
            shield(c, sx, cy, 0xFF34A853);
            if (blockedOnPage > 0) {
                String n = blockedOnPage > 99 ? "99+" : String.valueOf(blockedOnPage);
                float bw = Math.max(22 * k, badgeText.measureText(n) + 10 * k);
                r.set(sx + 2 * k, cy + 2 * k, sx + 2 * k + bw, cy + 22 * k);
                fill.setColor(0xFF81C995);
                c.drawRoundRect(r, 10 * k, 10 * k, fill);
                c.drawText(n, r.centerX() - badgeText.measureText(n) / 2, r.centerY() + Ui.halfCap(badgeText), badgeText);
            }

            // Menu (three dots)
            if (hovered == MENU) {
                fill.setColor(0x33FFFFFF);
                c.drawCircle(menuX(), cy, 24 * k, fill);
            }
            fill.setColor(0xFFE8EAED);
            for (int i = -1; i <= 1; i++) c.drawCircle(menuX(), cy + i * 8 * k, 2.6f * k, fill);

            // Loading line
            if (progress < 100) {
                fill.setColor(0xFF8AB4F8);
                c.drawRect(0, H * k - 3 * k, w * Math.max(0.05f, progress / 100f), H * k, fill);
            }
        }

        private void line(int color, float width) {
            stroke.setColor(color);
            stroke.setStrokeWidth(width);
        }

        private void arrowIcon(Canvas c, float x, float y, boolean leftward, int color) {
            line(color, 2.6f * k);
            float s = leftward ? -1 : 1, a = 9 * k;
            c.drawLine(x - s * a, y, x + s * a, y, stroke);
            c.drawLine(x + s * a, y, x + s * a - s * 7 * k, y - 7 * k, stroke);
            c.drawLine(x + s * a, y, x + s * a - s * 7 * k, y + 7 * k, stroke);
        }

        private void reload(Canvas c, float x, float y, int color) {
            line(color, 2.6f * k);
            r.set(x - 9 * k, y - 9 * k, x + 9 * k, y + 9 * k);
            c.drawArc(r, -60, 300, false, stroke);
            float ex = x + 9 * k * (float) Math.cos(Math.toRadians(-60)), ey = y + 9 * k * (float) Math.sin(Math.toRadians(-60));
            path.reset();
            path.moveTo(ex + 1 * k, ey - 6 * k);
            path.lineTo(ex + 1 * k, ey + 1.5f * k);
            path.lineTo(ex - 6.5f * k, ey + 1.5f * k);
            c.drawPath(path, stroke);
        }

        private void homeIcon(Canvas c, float x, float y, int color) {
            line(color, 2.4f * k);
            path.reset();
            path.moveTo(x - 10 * k, y - 1 * k);
            path.lineTo(x, y - 10 * k);
            path.lineTo(x + 10 * k, y - 1 * k);
            path.moveTo(x - 7 * k, y - 3 * k);
            path.lineTo(x - 7 * k, y + 9 * k);
            path.lineTo(x + 7 * k, y + 9 * k);
            path.lineTo(x + 7 * k, y - 3 * k);
            c.drawPath(path, stroke);
        }

        private void cross(Canvas c, float x, float y, float a, int color) {
            line(color, 2.3f * k);
            c.drawLine(x - a, y - a, x + a, y + a, stroke);
            c.drawLine(x - a, y + a, x + a, y - a, stroke);
        }

        private void lock(Canvas c, float x, float y, int color) {
            fill.setColor(color);
            r.set(x - 7 * k, y - 1 * k, x + 7 * k, y + 9 * k);
            c.drawRoundRect(r, 2 * k, 2 * k, fill);
            line(color, 2.2f * k);
            r.set(x - 4.5f * k, y - 9 * k, x + 4.5f * k, y + 1 * k);
            c.drawArc(r, 180, 180, false, stroke);
        }

        private void info(Canvas c, float x, float y, int color) {
            line(color, 2 * k);
            c.drawCircle(x, y, 9 * k, stroke);
            c.drawLine(x, y - 1 * k, x, y + 5 * k, stroke);
            fill.setColor(color);
            c.drawCircle(x, y - 4.5f * k, 1.4f * k, fill);
        }

        private void globe(Canvas c, float x, float y, float rr, int color) {
            line(color, 1.8f * k);
            c.drawCircle(x, y, rr, stroke);
            r.set(x - rr * 0.45f, y - rr, x + rr * 0.45f, y + rr);
            c.drawOval(r, stroke);
            c.drawLine(x - rr, y, x + rr, y, stroke);
        }

        private void shield(Canvas c, float x, float y, int color) {
            path.reset();
            path.moveTo(x, y - 12 * k);
            path.lineTo(x + 10 * k, y - 8 * k);
            path.quadTo(x + 10 * k, y + 6 * k, x, y + 12 * k);
            path.quadTo(x - 10 * k, y + 6 * k, x - 10 * k, y - 8 * k);
            path.close();
            fill.setColor(color);
            c.drawPath(path, fill);
            line(0xFFFFFFFF, 2.2f * k);
            path.reset();
            path.moveTo(x - 4.5f * k, y);
            path.lineTo(x - 1 * k, y + 3.5f * k);
            path.lineTo(x + 5 * k, y - 3.5f * k);
            c.drawPath(path, stroke);
        }
    }

    private static String host(String u) {
        String h = Uri.parse(u).getHost();
        return h == null ? u : h;
    }

    // MARK: - The pointer, over everything (the full-screen video too), and the first-time hint

    private final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hintText;
        private final Path arrow = new Path();
        private final RectF r = new RectF();
        private boolean visible = true, pressed;
        private long hintUntil;

        Pointer(Context c) {
            super(c);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeJoin(Paint.Join.ROUND);
            edge.setStrokeWidth(2.4f * k);
            edge.setColor(0xFF000000);
            fill.setColor(0xFFFFFFFF);
            hintText = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
            setFocusable(false);
            setClickable(false);
            // The arrow, tip at (0,0)
            float s = k * 1.15f;
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 28 * s);
            arrow.lineTo(7 * s, 21.5f * s);
            arrow.lineTo(12 * s, 32 * s);
            arrow.lineTo(16.5f * s, 30 * s);
            arrow.lineTo(11.5f * s, 19.5f * s);
            arrow.lineTo(20.5f * s, 19.5f * s);
            arrow.close();
        }

        private final Runnable hide = () -> {
            visible = false;
            invalidate();
        };

        void show() {
            visible = true;
            removeCallbacks(hide);
            if (customView != null) postDelayed(hide, 3000);
            invalidate();
        }

        void idleHide() {
            removeCallbacks(hide);
            postDelayed(hide, 3000);
        }

        void press(boolean on) {
            pressed = on;
            show();
        }

        void showHint() {
            hintUntil = SystemClock.uptimeMillis() + 7000;
            postInvalidateDelayed(7100);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return false;   // never in the way of the page
        }

        @Override protected void onDraw(Canvas c) {
            if (SystemClock.uptimeMillis() < hintUntil && customView == null) {
                String h = Ui.tr("Setas movem o ponteiro · na borda, segure para rolar · OK clica · Voltar volta · CH +/− rola a página",
                        "Arrows move the pointer · hold at an edge to scroll · OK clicks · Back goes back · CH +/− scrolls the page");
                float w = hintText.measureText(h) + 56 * k;
                float cx = getWidth() / 2f, by = getHeight() - 60 * k;
                r.set(cx - w / 2, by - 54 * k, cx + w / 2, by);
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(0xE6202124);
                c.drawRoundRect(r, 27 * k, 27 * k, bg);
                c.drawText(h, cx - hintText.measureText(h) / 2, r.centerY() + Ui.halfCap(hintText), hintText);
            }
            if (!visible) return;
            c.save();
            c.translate(px, py);
            if (pressed) c.scale(0.86f, 0.86f);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
            c.restore();
        }
    }
}
