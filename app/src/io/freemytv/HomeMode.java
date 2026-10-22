package io.freemytv;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Original ⇄ Free My TV. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "io.freemytv";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String SETTINGS_ACTIVITY = PKG + "/" + PKG + ".SettingsActivity";
    /** Our screensaver: on Android 11 the copy that runs in the main process (ScreensaverAlone, #10). */
    static final String DREAM = PKG + "/" + PKG + (Build.VERSION.SDK_INT == 30 ? ".ScreensaverAlone" : ".Screensaver");
    static final String FREE = "free", ORIGINAL = "original";

    /** Classic Android TV's home screen; its recommendations service crashes on the screensaver while it's off (#11). */
    static final String TV_LAUNCHER = "com.google.android.tvlauncher";
    static final String TV_RECOMMENDATIONS = "com.google.android.tvrecommendations";
    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    /**
     * Google TV has a RecoveryActivity that would take over the home screen without its launcher. Older Android TV
     * (Android 9) has the same setup app without a home screen: there it is left alone.
     */
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";

    /**
     * Turned off in Free My TV mode, when the TV has them. Google extras first, then TV-maker extras (TCL here; add
     * your brand's, see the README). The TV's own home screen and screensaver are added at switch time.
     */
    static final String[] DEBLOAT = {
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
        // TCL
        "com.tcl.magiconnectfree", "com.tcl.miracast",                                // screen mirroring apps
        "com.tcl.waterfall.overseas", "com.tcl.channelplus",                          // TCL Channel and its Live TV
        "com.tcl.messagebox", "com.tcl.usercenter",                                   // TCL messages and account
        "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",                   // store demo, hotel menu
        "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",        // repair guide, manual, logs
        // Multilaser (Expressluck / CVTE board)
        "tv.anoki.acr.anokiacroptin",                                                 // Anoki ACR: watches what's on screen
        "fusion.android.tv.demo", "com.expressluck.tvhelpmanual",                     // store demo, manual
        // Philco (Homwee/Changhong)
        "com.homwee.aipont",                                                          // AI PONT: promo channels, app downloads
        "com.homwee.help",                                                            // user manual
        // Xiaomi
        "com.miui.tv.analytics", "com.xiaomi.statistic",                              // telemetry
        "com.mitv.tvhome.mitvplus", "com.xm.webcontent",                              // Mi TV+ promo channels, promo web content
    };

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /** Never turned off, not even by mistake (picture, sound, inputs, remote, store). Checked before every disable. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
        "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
        "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config",
        "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service", "com.tcl.initsetup",
        "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.inputmethod.latin", "com.google.android.webview",
        "com.android.tv.settings", "com.android.systemui", "android", PKG,
        // Philips (TP Vision) and MediaTek: system, Ambilight, live TV and inputs, setup, factory menu (#11)
        "org.droidtv", "org.droidtv.ambisvc", "org.droidtv.ui.strings", "org.droidtv.tv.packageinfo", "org.droidtv.icp",
        "org.droidtv.gel", "org.droidtv.eum", "com.mediatek.tv.service", "com.mediatek.tvinput", "com.mediatek.network",
        "com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.setupwizard", "com.mediatek.tv.factory",
        "mediatek.factorymenu.ui", "com.tpv.xmic.ots.cms.client",
        // Multilaser (Expressluck / CVTE): remote pairing and keys, live TV, media player, factory menus
        "com.express.bt.autopair", "com.mediatek.autopair", "com.mediatek.hotkey.dispatcher",
        "com.mediatek.android.tv.globalkeyoverlay", "com.cltv.fast", "com.mediatek.wwtv.mediaplayer",
        "com.mediatek.androidbox", "com.expressluck.facmenu", "com.express.at.factory.factorymanager",
        "com.at.factory.androidtvaidlservice", "com.cvte.tv.at", "com.cvte.tv.fac.aging", "com.express.fac.test",
        // Philco (Homwee/Changhong, MediaTek): picture tests, overlays, updates, factory menu (pairing is above)
        "com.mediatek.tv.factorykey", "com.mediatek.testpq", "com.mediatek.overlay.tvcenter",
        "com.mediatek.overlay.tvsettings", "com.homwee.overlay.tvservice", "com.homwee.synckey", "com.homwee.upgrade",
        "com.homwee.dmt.upgrade", "com.homwee.factory", "com.homwee.chinstallapk", "com.homwee.chtvmanager",
        "com.mstar.android.tv.disclaimercustomization",
        // Xiaomi (MediaTek): system service, remote pairing, live TV, quick settings, child lock, updates, casting
        "mitv.service", "com.mi.bluetooth", "com.mitv.livetv.ci_mediatek", "com.xiaomi.tvqs.overseas",
        "com.mitv.tvlock", "com.xiaomi.mitv.updateservice", "com.xiaomi.android.tvsetup.partnercustomizer",
        "com.mitv.overseaservice", "com.xiaomi.mitv.tvmanager", "com.xiaomi.wfdsinkhelperservice",
        "com.mitv.milinkservice", "com.xiaomi.mitv.smartshare", "android.autoinstalls.config.xiaomi.amelie"));

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /** Asks the main process to switch (the settings screen calls this). */
    static void request(Context c, String mode) {
        ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check Free My TV mode. */
    static void bootCheck(Context c) {
        ensureRemoteService(c, 30_000);
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000);
    }

    /** After an update of this app: complete Free My TV mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    /**
     * The remote's app buttons (RemoteKeys) need an accessibility service that only filters those keys. It is on only
     * on TCL and Vestel TVs in Free My TV mode, and goes off with Original. Android turns it off when this app is updated, so the
     * boot check and an update turn it back on.
     */
    static void ensureRemoteService(Context c, long delayMs) {
        if (RemoteKeys.supported()) ModeJob.schedule(c, ModeJob.REMOTE, null, delayMs);
    }

    static void ensureRemoteServiceNow(Context app) {
        if (!RemoteKeys.supported() || !FREE.equals(state(app).optString("mode"))) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            remoteService(app, adb, true);
        } catch (IOException e) {
            AppLog.w("remote buttons service: " + e.getMessage());
        }
    }

    private static final String REMOTE_SERVICE = PKG + "/" + PKG + ".RemoteKeyService";
    private static final Pattern SERVICE = Pattern.compile("[\\w.]+/[\\w.$]+");

    /** Adds ours to the enabled accessibility services (keeping the others, like TalkBack), or takes it out. */
    private static void remoteService(Context c, Adb adb, boolean on) throws IOException {
        String current = adb.shell("settings get secure enabled_accessibility_services").trim();
        if (current.equals("null")) current = "";
        List<String> others = new ArrayList<>();
        for (String one : current.split(":")) {
            if (one.isEmpty() || one.equals(REMOTE_SERVICE)) continue;
            // Only plain component names go back into the shell command; anything else is left alone.
            if (!SERVICE.matcher(one).matches()) {
                AppLog.w("accessibility services left as they are: unexpected entry");
                return;
            }
            others.add(one);
        }
        if (on == current.contains(REMOTE_SERVICE)) return;
        List<String> list = new ArrayList<>(others);
        if (on) {
            JSONObject s = state(c);
            try { s.put("accessibilityWasOff", !"1".equals(adb.shell("settings get secure accessibility_enabled").trim())); }
            catch (JSONException ignored) {}
            save(c, s);
            list.add(REMOTE_SERVICE);
        }
        adb.shell(list.isEmpty() ? "settings delete secure enabled_accessibility_services"
                : "settings put secure enabled_accessibility_services '" + String.join(":", list) + "'");
        if (on) adb.shell("settings put secure accessibility_enabled 1");
        else if (list.isEmpty() && state(c).optBoolean("accessibilityWasOff")) adb.shell("settings put secure accessibility_enabled 0");
        AppLog.w("remote buttons service " + (on ? "on" : "off"));
    }

    // MARK: - Ad blocker (AdGuard DNS, for the whole TV)

    /**
     * AdGuard's free public DNS. Set as Android's Private DNS (Android 9+), it answers "nowhere" for ad and tracker
     * servers, so ads stop loading in every app and website on the TV. Videos whose ads come from the same servers as
     * the video itself (YouTube) are not affected: SmartTube covers YouTube.
     */
    static final String ADGUARD_DNS = "dns.adguard-dns.com";
    private static final Set<String> DNS_MODES = new HashSet<>(Arrays.asList("off", "opportunistic", "hostname"));
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,252}");

    static void requestAdBlock(Context c, boolean on) {
        ModeJob.schedule(c, ModeJob.ADBLOCK, on ? "on" : "off", 0);
    }

    static void adBlockNow(Context c, boolean on) {
        synchronized (RUN) {
            try (Adb adb = connect(c, state(c))) {
                setAdBlock(c, adb, on, true);
                done(c, null);
            } catch (Exception e) {
                done(c, Ui.tr("Não deu para mudar o bloqueador de anúncios: ", "Couldn't change the ad blocker: ") + e.getMessage());
            }
        }
    }

    /** Turns AdGuard DNS on (checking the TV can still look up names; if not, it puts everything back) or off. */
    private static void setAdBlock(Context c, Adb adb, boolean on, boolean chosen) throws IOException, JSONException {
        JSONObject s = state(c);
        if (chosen) { s.put("adblockChoice", on ? "on" : "off"); save(c, s); }
        if (!on) {
            busy(c, Ui.tr("Desligando o bloqueador de anúncios…", "Turning off the ad blocker…"));
            restoreDns(adb, s);
            s = state(c);
            s.put("adblock", false);
            save(c, s);
            notice(c, Ui.tr("Bloqueador de anúncios desligado: o DNS da TV voltou ao que era.",
                    "Ad blocker off: the TV's DNS is back to what it was."));
            return;
        }
        busy(c, Ui.tr("Ligando o bloqueador de anúncios…", "Turning on the ad blocker…"));
        String mode = adb.shell("settings get global private_dns_mode").trim();
        String host = adb.shell("settings get global private_dns_specifier").trim();
        if (!s.has("dnsMode")) {   // what the TV had before we ever touched it, kept for "off"
            s.put("dnsMode", mode).put("dnsHost", host);
            save(c, s);
        }
        boolean blocking;
        try {
            adb.shell("settings put global private_dns_specifier " + ADGUARD_DNS);
            adb.shell("settings put global private_dns_mode hostname");
            if (!"hostname".equals(adb.shell("settings get global private_dns_mode").trim())) {
                throw new IOException(Ui.tr("esta TV não aceita DNS privado.", "this TV doesn't take a Private DNS."));
            }
            busy(c, Ui.tr("Testando a internet com o bloqueador…", "Testing the internet with the ad blocker…"));
            // Names the TV rarely looks up, so the answer comes from AdGuard and not from the TV's cache.
            if (!resolves(adb, "example.org") && !resolves(adb, "www.wikipedia.org")) {
                // The network can't reach AdGuard's DNS (some providers block its port, 853): no site would open.
                throw new IOException(Ui.tr("esta rede não alcança o DNS da AdGuard, então a internet foi deixada como estava.",
                        "this network can't reach AdGuard DNS, so the internet was left as it was."));
            }
            blocking = blocked(adb, "doubleclick.net");
        } catch (IOException | RuntimeException e) {
            // Whatever went wrong, never leave the TV with a DNS it can't reach.
            try {
                restoreDns(adb, state(c));
                JSONObject back = state(c);
                back.put("adblock", false);
                save(c, back);
            } catch (Exception ignored) {
            }
            throw e;
        }
        s = state(c);
        s.put("adblock", true);
        save(c, s);
        notice(c, blocking
                ? Ui.tr("Bloqueador de anúncios ligado (AdGuard DNS): anúncios bloqueados em todos os apps e sites.",
                        "Ad blocker on (AdGuard DNS): ads are blocked in every app and website.")
                : Ui.tr("Bloqueador de anúncios ligado (AdGuard DNS). Pode levar um minuto para valer em todos os apps.",
                        "Ad blocker on (AdGuard DNS). It may take a minute to work in every app."));
    }

    /**
     * After the TV starts: Private DNS has no fallback, so a network that can't reach AdGuard (a new Wi-Fi, a blocked
     * port, a board without a clock that boots with the wrong date) would leave every app offline. Then put the TV's
     * own DNS back and say so.
     */
    private static void checkAdBlock(Context c, JSONObject s) {
        if (!s.optBoolean("adblock")) return;
        try (Adb adb = Adb.connect(c, 10_000, null)) {
            if (resolves(adb, "example.org") || resolves(adb, "www.wikipedia.org")) return;
            restoreDns(adb, state(c));
            JSONObject off = state(c);
            off.put("adblock", false);
            save(c, off);
            notice(c, Ui.tr("Bloqueador de anúncios desligado: esta rede não alcança o DNS da AdGuard. Ligue de novo em Ajustes do Início.",
                    "Ad blocker turned off: this network can't reach AdGuard DNS. Turn it on again in Home Settings."));
        } catch (Exception e) {
            AppLog.w("ad blocker check: " + e.getMessage());
        }
    }

    /** True when the TV can look the name up (retries while Android checks the new DNS server). */
    private static boolean resolves(Adb adb, String name) throws IOException {
        for (int i = 0; i < 8; i++) {
            String out = adb.shell("ping -c 1 -W 2 " + name + " 2>&1");
            if (out.contains("PING ")) return true;
            SystemClock.sleep(2_000);
        }
        return false;
    }

    /** True when an ad server's name comes back as 0.0.0.0, AdGuard's answer for blocked names. */
    private static boolean blocked(Adb adb, String name) throws IOException {
        for (int i = 0; i < 3; i++) {
            String out = adb.shell("ping -c 1 -W 1 " + name + " 2>&1");
            // AdGuard answers 0.0.0.0, or refuses the name: both mean the ad server can't be reached.
            if (out.contains("(0.0.0.0)") || out.contains("(::)")) return true;
            if (out.contains("PING ")) return false;
            if (out.contains("unknown host") || out.contains("Name or service not known") || out.contains("No address")) return true;
            SystemClock.sleep(1_500);
        }
        return false;
    }

    /** Puts back the Private DNS the TV had before (only known values go into the shell command). */
    private static void restoreDns(Adb adb, JSONObject s) throws IOException {
        String mode = s.optString("dnsMode", "null"), host = s.optString("dnsHost", "null");
        if (DNS_MODES.contains(mode)) adb.shell("settings put global private_dns_mode " + mode);
        else adb.shell("settings delete global private_dns_mode");
        if (!host.equals("null") && !host.isEmpty() && !host.equals(ADGUARD_DNS) && HOST.matcher(host).matches()) {
            adb.shell("settings put global private_dns_specifier " + host);
        } else {
            adb.shell("settings delete global private_dns_specifier");
        }
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static void applyFree(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                home = found != null ? realHome(found) : "";
            }
            String dream = originalDream(s);
            if (dream.isEmpty()) dream = defaultDream(adb);
            JSONObject latest = state(c);
            latest.put("originalHome", home).put("originalDream", dream);
            String saver = adb.shell("settings get secure screensaver_components").trim();
            if (!saver.contains(PKG + "/")) latest.put("originalScreensaver", safeComponents(saver));
            save(c, latest);
            // Turning off an app makes Android delete the Wi-Fi networks it saved (#14), and Original can't bring them
            // back. The TV's first setup usually saved the network: then stop here, before anything changes.
            Set<String> owners = wifiOwners(adb);
            boolean setupHome = setupIsHome(adb);
            Set<String> blockers = new HashSet<>();
            if (setupHome) blockers.add(GOOGLE_SETUP);
            if (!home.isEmpty()) blockers.add(packageOf(home));
            blockers.retainAll(owners);
            if (!blockers.isEmpty()) {
                // Old networks from the first setup that are out of range now (Settings can't show them, so the
                // person can't forget them there): forget those here. Never the one the TV is on, never one in range.
                busy(c, Ui.tr("Removendo redes Wi-Fi antigas…", "Removing old Wi-Fi networks…"));
                List<String> forgotten = forgetOldNetworks(adb, blockers);
                if (!forgotten.isEmpty()) {
                    AppLog.w("forgot old Wi-Fi networks from " + blockers + ": " + forgotten);
                    owners = wifiOwners(adb);
                }
            }
            if ((setupHome && owners.contains(GOOGLE_SETUP)) || owners.contains(packageOf(home))) {
                throw new IOException(Ui.tr("a configuração inicial da TV salvou o Wi-Fi, e desligá-la apagaria essas redes. "
                        + "Em Configurações → Rede e Internet, esqueça a rede e conecte de novo (as fora de alcance não aparecem "
                        + "lá: o Claude Code apaga pelo computador) e tente outra vez.",
                        "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                        + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                        + "show there: Claude Code can remove them from your computer), then try once more.",
                        "la configuración inicial del TV guardó el Wi-Fi, y desactivarla borraría esas redes. "
                        + "En Ajustes → Red e Internet, olvida la red y vuelve a conectarte (las fuera de alcance no aparecen "
                        + "ahí: Claude Code las borra desde la computadora) y vuelve a intentarlo."));
            }
            if (metrics(latest, ORIGINAL) == null && !home.isEmpty()) {
                busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
                measureHomeTime(c, adb, ORIGINAL);
                measureMemory(c, adb, ORIGINAL);
            }
            busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"));
            Watchdog.clear(c);
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen"));
            adb.shell("settings put secure screensaver_components " + DREAM);
            JSONObject started = state(c);
            started.put("mode", FREE).put("homeReverted", false).put("setupHome", setupHome);
            save(c, started);
            List<String> targets = targets(home, dream, setupHome);
            Set<String> disabled = new HashSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            int done = 0;
            for (String pkg : targets) {
                done++;
                busy(c, Ui.tr("Desligando apps (", "Turning off apps (") + done + "/" + targets.size() + ")…");
                if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) {
                    disabled.add(pkg);
                    JSONObject now = state(c);
                    now.put("disabled", new JSONArray(sorted(disabled)));
                    save(c, now);
                } else {
                    AppLog.w("could not turn off " + pkg + ": " + out.trim());
                }
            }
            s = state(c);
            s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(sorted(disabled))).put("setupHome", setupHome);
            save(c, s);
            if (RemoteKeys.supported()) {
                try {
                    remoteService(c, adb, true);
                } catch (IOException e) {
                    AppLog.w("remote buttons service: " + e.getMessage());
                }
            }
            notice(c, Ui.tr("Free My TV ligado: Início e tela de descanso novos, " + disabled.size() + " apps desligados.",
                    "Free My TV is on: new home screen and screensaver, " + disabled.size() + " apps turned off."));
            // No ads anywhere on the TV, unless the person turned the ad blocker off in Home Settings.
            if (!"off".equals(state(c).optString("adblockChoice")) && !state(c).optBoolean("adblock")) {
                try {
                    setAdBlock(c, adb, true, false);
                } catch (Exception e) {
                    notice(c, Ui.tr("Bloqueador de anúncios não ligado: ", "Ad blocker not turned on: ") + e.getMessage());
                }
            }
            busy(c, Ui.tr("Medindo o Free My TV…", "Measuring Free My TV…"));
            measureHomeTime(c, adb, FREE);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, FREE, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para ligar o Free My TV: ", "Couldn't turn on Free My TV: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    private static void applyOriginal(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"));
            String home = originalHome(s);
            Set<String> toEnable = new HashSet<>(stringList(s, "disabled"));
            for (String pkg : targets(home, originalDream(s), true)) {
                if (!Arrays.asList(DEBLOAT).contains(pkg) && !Arrays.asList(ASSISTANT).contains(pkg)) toEnable.add(pkg);
            }
            Set<String> present = packages(adb, "");
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (String pkg : sorted(toEnable)) {
                done++;
                busy(c, Ui.tr("Religando apps (", "Turning apps back on (") + done + "/" + toEnable.size() + ")…");
                if (!present.contains(pkg)) continue;
                // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears silently.
                String out = adb.shell("pm enable " + pkg);
                if (!out.contains("new state: enabled")) failed.add(pkg);
            }
            busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"));
            if (state(c).optBoolean("adblock")) {
                // Original is the TV as it came: its own DNS too. The choice is kept, so Free My TV turns it back on.
                restoreDns(adb, state(c));
                JSONObject off = state(c);
                off.put("adblock", false);
                save(c, off);
            }
            boolean homeOk = restoreHome(adb, home);
            restoreScreensaver(adb, s);
            try {
                remoteService(c, adb, false);
            } catch (IOException e) {
                AppLog.w("remote buttons service: " + e.getMessage());
            }
            s = state(c);
            s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(failed));
            save(c, s);
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                        "Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> problems = new ArrayList<>(failed);
                if (!homeOk) problems.add(Ui.tr("o Início original", "the original home screen"));
                notice(c, Ui.tr("Modo original, mas não consegui religar: ", "Original mode, but I couldn't turn back on: ")
                        + String.join(", ", problems) + ".");
            }
            busy(c, Ui.tr("Medindo o Início original…", "Measuring the original home screen…"));
            measureHomeTime(c, adb, ORIGINAL);
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            ModeJob.schedule(c, ModeJob.MEMORY, ORIGINAL, 120_000);
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
        }
    }

    /** The recorded home screen; without one, Google TV's; without that, the system picks (we leave the role). */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity '" + home + "'").contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    /** Safety net: our home screen crashed 3 times in a row. The home screen and the screensaver go back; the rest stays. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                boolean ours = homeHolder(adb).contains(PKG);
                if (!ours && (!FREE.equals(s.optString("mode")) || s.optBoolean("homeReverted"))) return;
                String home = originalHome(s);
                adb.shell("pm enable " + GOOGLE_SETUP);
                if (!restoreHome(adb, home)) {
                    AppLog.w("could not give the home screen back");
                    return;
                }
                String homePkg = packageOf(home), dreamPkg = packageOf(originalDream(s));
                if (dreamPkg != null) adb.shell("pm enable " + dreamPkg);
                JSONArray kept = new JSONArray();
                for (String pkg : stringList(s, "disabled")) {
                    if (!pkg.equals(GOOGLE_SETUP) && !pkg.equals(homePkg) && !pkg.equals(dreamPkg)) kept.put(pkg);
                }
                // The screensaver first: if that fails, the revert isn't marked done and the next try does it again.
                restoreScreensaver(adb, s);
                s.put("homeReverted", true).put("disabled", kept);
                save(app, s);
                notice(app, Ui.tr("O Início do Free My TV caiu 3 vezes seguidas; voltei para o Início original.",
                        "Free My TV's home screen crashed 3 times in a row; I switched back to the original one."));
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            } catch (Exception e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    /**
     * In Free My TV mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            String fingerprint = Build.FINGERPRINT;
            boolean updated = !fingerprint.equals(s.optString("fingerprint", fingerprint));
            try {
                s.put("fingerprint", fingerprint);
                s.remove("busy");
                save(app, s);
            } catch (JSONException ignored) {}
            checkAdBlock(app, s);
            if (!FREE.equals(s.optString("mode"))) return;
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                Set<String> owners = wifiOwners(adb);
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                String dream = originalDream(s);
                for (String pkg : targets(home, dream, s.optBoolean("setupHome", true))) {
                    if (!reverted || !(pkg.equals(packageOf(home)) || pkg.equals(GOOGLE_SETUP) || pkg.equals(packageOf(dream)))) {
                        wanted.add(pkg);
                    }
                }
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                for (String pkg : sorted(wanted)) {
                    if (PROTECTED.contains(pkg) || !present.contains(pkg) || owners.contains(pkg)) continue;
                    if (disabledNow.contains(pkg)) {
                        disabled.add(pkg);
                        continue;
                    }
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("o Início", "the home screen"));
                }
                // Whole component names: ".ScreensaverAlone" (Android 11) contains ".Screensaver" (12 and newer).
                if (!reverted && !Arrays.asList(adb.shell("settings get secure screensaver_components").trim().split(",")).contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    fixed.add(Ui.tr("a tela de descanso", "the screensaver"));
                }
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                save(app, latest);
            } catch (Exception e) {
                notice(app, Ui.tr("Não consegui conferir o Free My TV: ", "Couldn't check Free My TV: ") + (Ui.ru ? Russian.text(e.getMessage() == null ? "" : e.getMessage()) : e.getMessage()));
                return;
            }
            if (!fixed.isEmpty()) {
                notice(app, (updated ? Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                        : Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) + String.join(", ", fixed) + ".");
            } else if (updated) {
                notice(app, Ui.tr("A TV foi atualizada; o Free My TV continua igual.", "The TV was updated; Free My TV is still on."));
            }
        }
    }

    /**
     * What to turn off: the TV's own home screen and screensaver, Google TV's recovery home (when setupHome: the setup
     * app is one of the TV's home screens), and the lists above.
     */
    private static List<String> targets(String home, String dream, boolean setupHome) {
        List<String> out = new ArrayList<>();
        String homePkg = packageOf(home);
        if (homePkg != null && !homePkg.equals(PKG)) out.add(homePkg);
        if (TV_LAUNCHER.equals(homePkg)) out.add(TV_RECOMMENDATIONS);
        if (setupHome) out.add(GOOGLE_SETUP);
        String dreamPkg = packageOf(dream);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
        out.addAll(Arrays.asList(DEBLOAT));
        if (Config.DISABLE_ASSISTANT) out.addAll(Arrays.asList(ASSISTANT));
        return out;
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return null;
        String list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return null;
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (holder.matches("[\\w.]+(;[\\w.]+)*")) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        String component = lines[lines.length - 1].trim();
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return component.contains("/") && !component.startsWith("android/") ? packageOf(component) : "";
    }

    /** Whether Google TV's setup app is one of the home screens (its RecoveryActivity), so it must go off too. */
    private static boolean setupIsHome(Adb adb) throws IOException {
        return adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").contains(GOOGLE_SETUP + "/");
    }

    /** Apps that saved a Wi-Fi network ("cname" in the Wi-Fi service's list of saved networks). */
    private static Set<String> wifiOwners(Adb adb) throws IOException {
        Set<String> owners = new HashSet<>();
        Matcher m = Pattern.compile("cname=([\\w.]+)").matcher(adb.shell("dumpsys wifi | grep -o 'cname=[^ ]*'"));
        while (m.find()) owners.add(m.group(1));
        return owners;
    }

    private static final Pattern SAVED_BLOCK = Pattern.compile("(?:^|\\s)ID: (\\d+) SSID: (\"(?:[^\"\\\\]|\\\\.)*\"|\\S+) ");
    private static final Pattern CURRENT_WIFI = Pattern.compile("SSID: (\"(?:[^\"\\\\]|\\\\.)*\"|[^,\\n]*), BSSID: [^\\n]*?Net ID: (-?\\d+)");
    private static final Pattern LISTED = Pattern.compile("(?m)^\\s*(\\d+)\\s+(.*?)\\s+\\S+\\s*$");

    /**
     * Forgets the saved Wi-Fi networks that one of `owners` saved and that are out of range now: Settings doesn't list
     * those, so the person can't forget them there. Careful on purpose: nothing is forgotten unless the TV is on Wi-Fi
     * right now and a fresh scan sees that network; never the network the TV is on; never one the scan sees (Settings
     * shows those, and the person forgets and reconnects them there). Returns the names it forgot.
     */
    private static List<String> forgetOldNetworks(Adb adb, Set<String> owners) {
        List<String> forgotten = new ArrayList<>();
        try {
            String dump = adb.shell("dumpsys wifi");
            Set<String> currentIds = new HashSet<>();
            Set<String> currentNames = new HashSet<>();
            Matcher cur = CURRENT_WIFI.matcher(dump);
            while (cur.find()) {
                int id = Integer.parseInt(cur.group(2));
                String name = unquote(cur.group(1));
                if (id >= 0 && !name.isEmpty() && !name.equals("<unknown ssid>")) {
                    currentIds.add(String.valueOf(id));
                    currentNames.add(name);
                }
            }
            if (currentIds.isEmpty()) return forgotten;   // not on Wi-Fi (or can't tell): touch nothing

            // Saved networks: id -> name, from Android's own list.
            java.util.Map<String, String> saved = new java.util.HashMap<>();
            Matcher l = LISTED.matcher(adb.shell("cmd wifi list-networks"));
            while (l.find()) saved.put(l.group(1), unquote(l.group(2)));
            if (saved.isEmpty()) return forgotten;

            // What a fresh scan sees. If it doesn't see the network the TV is on, the scan can't be trusted: stop.
            String scan = adb.shell("cmd wifi start-scan; sleep 6; cmd wifi list-scan-results");
            boolean sane = false;
            for (String name : currentNames) if (scan.contains(name)) sane = true;
            if (!sane) return forgotten;

            Matcher b = SAVED_BLOCK.matcher(dump);
            List<int[]> spans = new ArrayList<>();
            List<String[]> heads = new ArrayList<>();
            while (b.find()) {
                spans.add(new int[] {b.start(), b.end()});
                heads.add(new String[] {b.group(1), unquote(b.group(2))});
            }
            Set<String> done = new HashSet<>();
            for (int i = 0; i < spans.size(); i++) {
                String id = heads.get(i)[0], name = heads.get(i)[1];
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(dump.length(), spans.get(i)[1] + 6000);
                Matcher owner = Pattern.compile("cname=([\\w.]+)").matcher(dump.substring(spans.get(i)[1], end));
                if (!owner.find() || !owners.contains(owner.group(1))) continue;
                if (done.contains(id) || currentIds.contains(id) || currentNames.contains(name)) continue;
                if (!name.equals(saved.get(id))) continue;   // not the same network in Android's list: leave it
                if (name.isEmpty() || scan.contains(name)) continue;   // in range: Settings shows it
                String out = adb.shell("cmd wifi forget-network " + id).trim();
                AppLog.w("forget Wi-Fi " + id + " (" + name + "): " + out);
                done.add(id);
                forgotten.add(name);
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("could not check old Wi-Fi networks: " + e);
        }
        return forgotten;
    }

    private static String unquote(String s) {
        s = s.trim();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** The recorded home screen of the TV, as {@link #realHome}. */
    private static String originalHome(JSONObject s) {
        return realHome(s.optString("originalHome", ""));
    }

    /**
     * Some TVs (TCL, Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private static String realHome(String home) {
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : safeComponent(home);
    }

    private static String originalDream(JSONObject s) {
        return safeComponent(s.optString("originalDream", ""));
    }

    // Nested classes ("Outer$Inner") are valid; every shell command that takes one quotes it, so $ stays literal.
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][\\w.]*/[\\w.$]+");

    private static String safeComponent(String component) {
        return COMPONENT.matcher(component).matches() ? component : "";
    }

    private static String safeComponents(String components) {
        for (String one : components.split(",")) {
            if (safeComponent(one).isEmpty()) return "";
        }
        return components;
    }

    private static void restoreScreensaver(Adb adb, JSONObject s) throws IOException {
        String saver = safeComponents(s.optString("originalScreensaver", ""));
        if (saver.isEmpty()) adb.shell("settings delete secure screensaver_components");
        else adb.shell("settings put secure screensaver_components '" + saver + "'");
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? safeComponent(d) : "";
    }

    private static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static Adb connect(Context c, JSONObject s) throws IOException {
        busy(c, Ui.tr("Conectando…", "Connecting…"));
        Adb adb = Adb.connect(c, 120_000, () -> busy(c, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!s.optBoolean("adbAuthorized")) {
            JSONObject latest = state(c);
            try { latest.put("adbAuthorized", true); } catch (JSONException ignored) {}
            save(c, latest);
        }
        return adb;
    }

    private static void check(String output, String expected, String error) throws IOException {
        if (!output.contains(expected)) throw new IOException(error + " (" + output.trim() + ")");
    }

    // MARK: - Measurements (without opening apps: memory and the home button)

    private static void measureHomeTime(Context c, Adb adb, String mode) throws IOException, JSONException {
        List<Integer> samples = new ArrayList<>();
        Pattern total = Pattern.compile("TotalTime: (\\d+)");
        for (int i = 0; i < 3; i++) {
            adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
            SystemClock.sleep(500);
            Matcher m = total.matcher(adb.shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME"));
            if (m.find()) samples.add(Integer.parseInt(m.group(1)));
            SystemClock.sleep(600);
        }
        adb.shell("am start -W -n " + SETTINGS_ACTIVITY);
        if (samples.isEmpty()) return;
        Collections.sort(samples);
        putMetric(c, mode, "homeMs", samples.get(samples.size() / 2));
    }

    /** Two minutes after a switch (the home screen settled, caches trimmed). */
    static void measureMemoryNow(Context c, String mode) {
        synchronized (RUN) {
            if (!mode.equals(state(c).optString("mode"))) return;
            try (Adb adb = Adb.connect(c, 10_000, null)) {
                measureMemory(c, adb, mode);
            } catch (Exception e) {
                AppLog.w("memory measurement failed: " + e.getMessage());
            }
        }
    }

    private static void measureMemory(Context c, Adb adb, String mode) throws IOException, JSONException {
        Matcher avail = Pattern.compile("MemAvailable:\\s+(\\d+) kB").matcher(adb.shell("cat /proc/meminfo"));
        if (avail.find()) putMetric(c, mode, "memAvailableMB", Long.parseLong(avail.group(1)) / 1024);
        long pss = 0;
        String home = packageOf(originalHome(state(c)));
        String[] processes = FREE.equals(mode) ? new String[] {PKG + ":home"}
                : home == null ? new String[0] : new String[] {home, home + ":coreservices"};
        Pattern total = Pattern.compile("TOTAL PSS:\\s+(\\d+)");
        for (String p : processes) {
            Matcher m = total.matcher(adb.shell("dumpsys meminfo " + p));
            if (m.find()) pss += Long.parseLong(m.group(1));
        }
        if (pss > 0) putMetric(c, mode, "homePssMB", pss / 1024);
    }

    private static void putMetric(Context c, String mode, String key, long value) throws JSONException {
        JSONObject s = state(c);
        JSONObject metrics = s.optJSONObject("metrics");
        if (metrics == null) metrics = new JSONObject();
        JSONObject m = metrics.optJSONObject(mode);
        if (m == null) m = new JSONObject();
        m.put(key, value).put("at", System.currentTimeMillis());
        metrics.put(mode, m);
        s.put("metrics", metrics);
        save(c, s);
    }

    private static JSONObject metrics(JSONObject s, String mode) {
        JSONObject metrics = s.optJSONObject("metrics");
        return metrics == null ? null : metrics.optJSONObject(mode);
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void busy(Context c, String text) {
        JSONObject s = state(c);
        try { s.put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).put("error", ""); } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }

    // MARK: - Helpers

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}
