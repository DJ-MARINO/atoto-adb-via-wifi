package com.djmarino.atoto.x10adb;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X10 ADB Doctor. The ATOTO X10 is a Qualcomm (QCM6125) unit, not FYT, so the
 * S8 trick (com.syu.ms command 161) does not exist there. A normal app cannot
 * turn ADB on by itself on Android 13, so this app looks for a route that the
 * firmware already offers, launches it, and writes a report of everything it
 * found so the next version can target this firmware.
 *
 * Routes, in order: ADB already listening; Settings' Developer options page
 * opened directly by intent; vendor factory or engineering screens; the FYT
 * service (only shown when com.syu.ms exists).
 */
public final class MainActivity extends Activity {
    static final int REPORT_PORT = 8765;

    private static final Pattern KEYWORDS = Pattern.compile(
            "(?i)develop|debug|adb|wireless|factory|engineer|hidden|secret|usb|advanced|"
                    + "diag|qfil|test|root|shell|setting");
    private static final String[] KEY_PROPS = {
            "ro.product.manufacturer", "ro.product.brand", "ro.product.model",
            "ro.product.device", "ro.product.board", "ro.board.platform", "ro.hardware",
            "ro.soc.manufacturer", "ro.soc.model", "ro.build.fingerprint",
            "ro.build.display.id", "ro.build.version.release", "ro.build.version.sdk",
            "ro.build.version.security_patch", "ro.build.type", "ro.build.tags",
            "ro.debuggable", "ro.secure", "ro.adb.secure", "ro.boot.verifiedbootstate",
            "sys.usb.config", "sys.usb.state", "persist.sys.usb.config",
            "service.adb.tcp.port", "persist.adb.tcp.port", "service.adb.tls.port",
            "persist.adb.tls_server.enable", "init.svc.adbd", "ro.oem_unlock_supported"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder actionLog = new StringBuilder();
    private final List<Launch> launches = new ArrayList<>();

    private TextView verdictView;
    private TextView reportView;
    private TextView serverView;
    private LinearLayout launchRow;
    private Button fytButton;
    private FytAdbController fyt;
    private volatile String reportText = "Scanning...";
    private volatile ServerSocket server;

    private static final class Launch {
        final String label;
        final Intent intent;
        final String note;

        Launch(String label, Intent intent, String note) {
            this.label = label;
            this.intent = intent;
            this.note = note;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fyt = new FytAdbController(this);
        setContentView(buildUi());
        scan();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startReportServer();
    }

    @Override
    protected void onStop() {
        stopReportServer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        fyt.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(12));
        root.setBackgroundColor(Color.parseColor("#0F1216"));

        TextView title = text("X10 ADB Doctor", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        verdictView = text("Scanning...", 20, Color.parseColor("#FFD27A"));
        verdictView.setPadding(0, dp(8), 0, dp(8));
        root.addView(verdictView);

        LinearLayout tools = row();
        tools.addView(button("Rescan", v -> scan()));
        tools.addView(button("Open Developer options", v -> openDeveloperOptions()));
        tools.addView(button("About (Build number)", v -> launch(new Launch("About",
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), null))));
        tools.addView(button("Share report", v -> shareReport()));
        tools.addView(button("Copy report", v -> copyReport()));
        fytButton = button("FYT: ADB on until reboot", v -> enableFyt());
        fytButton.setVisibility(View.GONE);
        tools.addView(fytButton);
        root.addView(scrollRow(tools));

        serverView = text("", 15, Color.parseColor("#8FA3B8"));
        root.addView(serverView);

        TextView launchTitle = text("Screens found on this unit (tap to try):", 15,
                Color.parseColor("#8FA3B8"));
        launchTitle.setPadding(0, dp(8), 0, 0);
        root.addView(launchTitle);
        launchRow = row();
        root.addView(scrollRow(launchRow));

        reportView = text("", 13, Color.parseColor("#D6DEE6"));
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(reportView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(scroll, lp);
        return root;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private HorizontalScrollView scrollRow(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(row);
        return scroll;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- actions

    private void openDeveloperOptions() {
        // Try the documented action first, then the Settings activities by name.
        for (Launch launch : developerOptionCandidates()) {
            if (resolves(launch.intent)) {
                launch(launch);
                return;
            }
        }
        log("Open Developer options: no Settings screen for it resolves on this unit");
        refreshLater();
    }

    private void launch(Launch launch) {
        Intent intent = new Intent(launch.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            log("Launched " + launch.label + " (" + describe(intent) + "): OK");
        } catch (ActivityNotFoundException error) {
            log("Launched " + launch.label + ": not found");
            toast("Not found on this unit");
        } catch (SecurityException error) {
            log("Launched " + launch.label + ": blocked (" + error.getMessage() + ")");
            toast("Blocked by the system");
        } catch (Exception error) {
            log("Launched " + launch.label + ": " + error);
            toast("Failed: " + error.getClass().getSimpleName());
        }
        refreshLater();
    }

    private void enableFyt() {
        fyt.enableTemporary(new FytAdbController.Listener() {
            @Override
            public void onRequestSent() {
                log("FYT command 161 sent (service.adb.tcp.port=5555, restart adbd)");
                mainHandler.postDelayed(MainActivity.this::scan, 2000L);
            }

            @Override
            public void onError(String message) {
                log("FYT failed: " + message);
                scan();
            }
        });
    }

    private void shareReport() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "X10 ADB Doctor report");
        send.putExtra(Intent.EXTRA_TEXT, reportText);
        try {
            startActivity(Intent.createChooser(send, "Send report"));
        } catch (Exception error) {
            toast("No app can share text here. Use Copy or the web address.");
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("X10 ADB Doctor report", reportText));
            toast("Report copied");
        }
    }

    private void log(String line) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (actionLog) {
            actionLog.append(time).append("  ").append(line).append('\n');
        }
    }

    private void refreshLater() {
        mainHandler.postDelayed(this::scan, 1500L);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- scan

    private void scan() {
        verdictView.setText("Scanning...");
        worker.execute(() -> {
            List<Launch> found = new ArrayList<>();
            String[] verdict = new String[1];
            String text;
            try {
                text = buildReport(found, verdict);
            } catch (Throwable error) {
                text = "Scan failed: " + error;
                verdict[0] = "Scan failed. Send this screen to Claude.";
            }
            final String finalText = text;
            mainHandler.post(() -> render(finalText, verdict[0], found));
        });
    }

    private void render(String text, String verdict, List<Launch> found) {
        reportText = text;
        reportView.setText(text);
        verdictView.setText(verdict);
        fytButton.setVisibility(fyt.isToolkitServicePresent() ? View.VISIBLE : View.GONE);
        launches.clear();
        launches.addAll(found);
        launchRow.removeAllViews();
        for (Launch launch : found) {
            launchRow.addView(button(launch.label, v -> launch(launch)));
        }
        if (found.isEmpty()) {
            launchRow.addView(text("none", 15, Color.parseColor("#8FA3B8")));
        }
        updateServerLine();
    }

    private String buildReport(List<Launch> found, String[] verdict) {
        StringBuilder out = new StringBuilder();
        PackageManager pm = getPackageManager();
        ContentResolver cr = getContentResolver();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        out.append("X10 ADB Doctor ").append(versionName()).append("  ").append(stamp).append("\n\n");

        // Device
        section(out, "Device");
        kv(out, "Build", Build.MANUFACTURER + " / " + Build.BRAND + " / " + Build.MODEL
                + " / " + Build.DEVICE + " / " + Build.PRODUCT);
        kv(out, "Hardware", Build.HARDWARE + " board " + Build.BOARD);
        if (Build.VERSION.SDK_INT >= 31) {
            kv(out, "SoC", Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL);
        }
        kv(out, "Android", Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        kv(out, "Display", Build.DISPLAY);
        kv(out, "Fingerprint", Build.FINGERPRINT);

        // ADB state
        Map<String, String> props = readAllProps();
        section(out, "ADB state");
        String devEnabled = global(cr, "development_settings_enabled");
        String adbEnabled = global(cr, "adb_enabled");
        String adbWifi = global(cr, "adb_wifi_enabled");
        kv(out, "development_settings_enabled", devEnabled);
        kv(out, "adb_enabled (USB debugging)", adbEnabled);
        kv(out, "adb_wifi_enabled (Wireless debugging)", adbWifi);
        UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
        boolean debugBlocked = um != null
                && um.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES);
        kv(out, "DISALLOW_DEBUGGING_FEATURES", debugBlocked ? "YES (debugging blocked by policy)" : "no");
        for (String key : KEY_PROPS) {
            kv(out, key, props.containsKey(key) ? props.get(key) : "(unreadable or unset)");
        }
        List<String> addresses = ipv4Addresses();
        kv(out, "IPv4", addresses.isEmpty() ? "none" : join(addresses));
        boolean legacyOpen = portOpen(5555);
        kv(out, "127.0.0.1:5555 (legacy Wi-Fi ADB)", legacyOpen ? "LISTENING" : "closed");
        int tlsPort = parseInt(props.get("service.adb.tls.port"));
        boolean tlsOpen = tlsPort > 0 && portOpen(tlsPort);
        if (tlsPort > 0) {
            kv(out, "127.0.0.1:" + tlsPort + " (Wireless debugging)", tlsOpen ? "LISTENING" : "closed");
        }

        // Routes
        section(out, "Developer options routes");
        Launch devRoute = null;
        for (Launch launch : developerOptionCandidates()) {
            boolean ok = resolves(launch.intent);
            kv(out, launch.label, (ok ? "resolves" : "no") + "  [" + describe(launch.intent) + "]");
            if (ok && devRoute == null) {
                devRoute = launch;
            }
        }
        boolean fytPresent = fyt.isToolkitServicePresent();
        kv(out, "FYT com.syu.ms/app.ToolkitService", fytPresent ? "FOUND" : "not present");

        // Packages
        List<PackageInfo> all = pm.getInstalledPackages(0);
        List<String> vendor = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (PackageInfo info : all) {
            names.add(info.packageName);
            if (isVendor(info)) {
                vendor.add(info.packageName);
            }
        }
        Collections.sort(names);
        Collections.sort(vendor);
        if (!vendor.contains("com.android.settings")) {
            vendor.add(0, "com.android.settings");
        }
        section(out, "Vendor and Settings components (" + vendor.size() + " packages)");
        for (String pkg : vendor) {
            describePackage(pm, pkg, out, found);
        }

        section(out, "Bound system services (service list)");
        out.append(run(5, "/system/bin/service", "list")).append('\n');

        section(out, "Action log");
        synchronized (actionLog) {
            out.append(actionLog.length() == 0 ? "(nothing yet)\n" : actionLog.toString());
        }

        section(out, "All installed packages (" + names.size() + ")");
        for (String name : names) {
            out.append(name).append('\n');
        }

        section(out, "All readable system properties (" + props.size() + ")");
        for (Map.Entry<String, String> entry : props.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        String ip = addresses.isEmpty() ? "HEAD_UNIT_IP" : firstWifiAddress(addresses);
        if (legacyOpen) {
            verdict[0] = "ADB over Wi-Fi is ON. On the laptop: adb connect " + ip + ":5555";
        } else if (tlsOpen || "1".equals(adbWifi)) {
            verdict[0] = "Wireless debugging is ON" + (tlsPort > 0 ? " (port " + tlsPort + ")" : "")
                    + ". Pair from Developer options > Wireless debugging > Pair with code.";
        } else if (debugBlocked) {
            verdict[0] = "Debugging is blocked by a device policy on this unit. Send the report.";
        } else if (devRoute != null) {
            verdict[0] = "Try: Open Developer options, turn on \"Use developer options\", then "
                    + "Wireless debugging. If that page is blank or closes, send the report.";
        } else if (fytPresent) {
            verdict[0] = "FYT service found: try \"FYT: ADB on until reboot\".";
        } else {
            verdict[0] = "No supported route found. Send the report (Share, Copy, or the web address).";
        }
        return out.toString();
    }

    private List<Launch> developerOptionCandidates() {
        List<Launch> list = new ArrayList<>();
        list.add(new Launch("Developer options (action)",
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null));
        list.add(new Launch("Developer options (dashboard)", component("com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"), null));
        list.add(new Launch("Developer options (legacy)", component("com.android.settings",
                "com.android.settings.Settings$DevelopmentSettingsActivity"), null));
        list.add(new Launch("Developer options (classic)", component("com.android.settings",
                "com.android.settings.DevelopmentSettings"), null));
        list.add(new Launch("Wireless debugging (action)",
                new Intent("android.settings.ADB_WIRELESS_SETTINGS"), null));
        list.add(new Launch("Wireless debugging (activity)", component("com.android.settings",
                "com.android.settings.Settings$WirelessDebuggingActivity"), null));
        return list;
    }

    private void describePackage(PackageManager pm, String pkg, StringBuilder out, List<Launch> found) {
        PackageInfo info;
        try {
            info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable error) {
            out.append(pkg).append(": ").append(error.getClass().getSimpleName()).append('\n');
            return;
        }
        String label = String.valueOf(info.applicationInfo.loadLabel(pm));
        out.append('\n').append(pkg).append("  \"").append(label).append("\"  v")
                .append(info.versionName).append('\n');
        boolean packageMatches = KEYWORDS.matcher(pkg + " " + label).find();
        if (info.activities != null) {
            for (ActivityInfo activity : info.activities) {
                if (!activity.exported) {
                    continue;
                }
                String flags = (activity.permission != null ? " perm=" + activity.permission : "")
                        + (activity.enabled ? "" : " DISABLED");
                out.append("  A ").append(shortName(pkg, activity.name)).append(flags).append('\n');
                boolean matches = KEYWORDS.matcher(activity.name).find()
                        || (packageMatches && !"com.android.settings".equals(pkg));
                if (matches && activity.enabled && activity.permission == null && found.size() < 60) {
                    found.add(new Launch(shortLabel(activity.name),
                            component(pkg, activity.name), null));
                }
            }
        }
        if (info.services != null) {
            for (ServiceInfo service : info.services) {
                if (service.exported) {
                    out.append("  S ").append(shortName(pkg, service.name))
                            .append(service.permission != null ? " perm=" + service.permission : "")
                            .append('\n');
                }
            }
        }
    }

    private static boolean isVendor(PackageInfo info) {
        ApplicationInfo app = info.applicationInfo;
        if (app == null || (app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            return false;
        }
        String pkg = info.packageName;
        return !(pkg.startsWith("com.android.") || pkg.startsWith("com.google.")
                || pkg.startsWith("android.") || pkg.equals("android"));
    }

    // ---------------------------------------------------------------- helpers

    private boolean resolves(Intent intent) {
        ResolveInfo info = getPackageManager().resolveActivity(intent, 0);
        return info != null && info.activityInfo != null && info.activityInfo.exported;
    }

    private static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    private static String describe(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().flattenToShortString();
        }
        return String.valueOf(intent.getAction());
    }

    private static String shortName(String pkg, String cls) {
        return cls.startsWith(pkg + ".") ? cls.substring(pkg.length()) : cls;
    }

    private static String shortLabel(String cls) {
        String name = cls.substring(cls.lastIndexOf('.') + 1).replace('$', ' ');
        return name.length() > 34 ? name.substring(0, 34) : name;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "?";
        }
    }

    private static String global(ContentResolver cr, String key) {
        try {
            String value = Settings.Global.getString(cr, key);
            return value == null ? "(unset)" : value;
        } catch (Exception error) {
            return "(unreadable: " + error.getClass().getSimpleName() + ")";
        }
    }

    private static void section(StringBuilder out, String title) {
        out.append("\n== ").append(title).append(" ==\n");
    }

    private static void kv(StringBuilder out, String key, String value) {
        out.append(key).append(": ").append(value).append('\n');
    }

    private static int parseInt(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String join(List<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(item);
        }
        return out.toString();
    }

    private static Map<String, String> readAllProps() {
        Map<String, String> props = new TreeMap<>();
        Matcher matcher = Pattern.compile("^\\[(.+?)\\]: \\[(.*)\\]$", Pattern.MULTILINE)
                .matcher(run(5, "/system/bin/getprop"));
        while (matcher.find()) {
            props.put(matcher.group(1), matcher.group(2));
        }
        return props;
    }

    /** Runs a command with a timeout and returns its combined output. */
    private static String run(int seconds, String... command) {
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process running = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // The process was stopped or closed its output.
                }
            });
            reader.start();
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                synchronized (output) {
                    output.append("(timed out after ").append(seconds).append(" s)\n");
                }
            } else {
                synchronized (output) {
                    output.append("(exit ").append(process.exitValue()).append(")\n");
                }
            }
            process.destroy();
            reader.join(1000L);
        } catch (Exception error) {
            synchronized (output) {
                output.append("(could not run: ").append(error.getMessage()).append(")\n");
            }
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        synchronized (output) {
            return output.toString();
        }
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static List<String> ipv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        result.add(nif.getName() + " " + address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // No interfaces readable; the report says "none".
        }
        return result;
    }

    private static String firstWifiAddress(List<String> addresses) {
        for (String entry : addresses) {
            if (entry.startsWith("wlan")) {
                return entry.substring(entry.indexOf(' ') + 1);
            }
        }
        String first = addresses.get(0);
        return first.substring(first.indexOf(' ') + 1);
    }

    // ---------------------------------------------------------------- report server

    /**
     * While the app is on screen it serves the report as plain text on port
     * 8765, so the laptop or the Pi can fetch it: curl http://IP:8765
     * It stops as soon as the app leaves the screen.
     */
    private void startReportServer() {
        if (server != null) {
            return;
        }
        final ServerSocket socket;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(REPORT_PORT));
        } catch (Exception error) {
            log("Report web address unavailable: " + error.getMessage());
            updateServerLine();
            return;
        }
        server = socket;
        updateServerLine();
        Thread thread = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        client.setSoTimeout(3000);
                        BufferedReader in = new BufferedReader(new InputStreamReader(
                                client.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            // Skip the request headers.
                        }
                        byte[] body = reportText.getBytes(StandardCharsets.UTF_8);
                        OutputStream os = client.getOutputStream();
                        os.write(("HTTP/1.0 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        os.write(body);
                        os.flush();
                    } catch (Exception ignored) {
                        // One bad request should not stop the server.
                    }
                }
            } finally {
                try {
                    socket.close();
                } catch (Exception ignored) {
                    // Already closed.
                }
                mainHandler.post(() -> {
                    if (server == socket) {
                        server = null;
                    }
                    updateServerLine();
                });
            }
        }, "report-server");
        thread.setDaemon(true);
        thread.start();
    }

    private void stopReportServer() {
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // Already closed.
            }
        }
    }

    private void updateServerLine() {
        if (serverView == null) {
            return;
        }
        if (server == null) {
            serverView.setText("Report web address: off");
            return;
        }
        List<String> addresses = ipv4Addresses();
        String ip = addresses.isEmpty() ? "HEAD_UNIT_IP" : firstWifiAddress(addresses);
        serverView.setText("Report on the network while this screen is open: http://" + ip + ":"
                + REPORT_PORT);
    }
}
