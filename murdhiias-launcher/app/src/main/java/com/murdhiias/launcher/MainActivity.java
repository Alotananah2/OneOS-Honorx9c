package com.murdhiias.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MURDHIIAS launcher: dark monochrome glass home screen with a big split clock. */
public class MainActivity extends Activity {
    private static final int REQ_PHOTO = 7;
    private static final int COLS = 4;

    private SharedPreferences prefs;
    private LauncherApps launcherApps;
    private float dp;

    private final List<LauncherActivityInfo> apps = new ArrayList<>();
    private final Map<String, LauncherActivityInfo> byKey = new HashMap<>();
    private final Map<String, Bitmap> iconCache = new HashMap<>();

    private FrameLayout root;
    private LinearLayout home;
    private SplitClock clock;
    private ImageView photo;
    private LinearLayout widgets;
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private static final int REQ_PICK_WIDGET = 8;
    private static final int REQ_BIND_WIDGET = 9;
    private static final int REQ_CONFIG_WIDGET = 10;
    private int pendingWidget = -1;
    private SensorManager sensors;
    private float tiltX = 0.5f, tiltY = 0.5f;
    private final SensorEventListener tilt = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            // 3D tilt: the wallpaper (and the home screen, a little less) follows the phone's tilt.
            float gx = e.values[0], gy = e.values[1];
            float tx = 0.5f - Math.max(-1f, Math.min(1f, gx / 6f)) * 0.5f;
            float ty = 0.5f + Math.max(-1f, Math.min(1f, (gy - 6f) / 6f)) * 0.5f;
            tiltX += (tx - tiltX) * 0.12f; tiltY += (ty - tiltY) * 0.12f;
            try {
                WallpaperManager wm = WallpaperManager.getInstance(MainActivity.this);
                wm.setWallpaperOffsetSteps(0.5f, 0.5f);
                wm.setWallpaperOffsets(root.getWindowToken(), tiltX, tiltY);
            } catch (Throwable ignored) { }
            if (home != null) {
                home.setTranslationX((tiltX - 0.5f) * -px(14));
                home.setTranslationY((tiltY - 0.5f) * -px(10));
            }
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };
    private GridLayout homeGrid;
    private LinearLayout dock;
    private FrameLayout drawer;
    private GridLayout drawerGrid;
    private GestureDetector gestures;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (clock != null) clock.invalidate(); }
    };
    private final BroadcastReceiver pkgs = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reload(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("murdhiias", MODE_PRIVATE);
        launcherApps = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        widgetManager = AppWidgetManager.getInstance(this);
        widgetHost = new AppWidgetHost(this, 1024);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        buildUi();
        setContentView(root);
        IntentFilter t = new IntentFilter();
        t.addAction(Intent.ACTION_TIME_TICK);
        t.addAction(Intent.ACTION_TIME_CHANGED);
        t.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, t);
        IntentFilter p = new IntentFilter();
        p.addAction(Intent.ACTION_PACKAGE_ADDED);
        p.addAction(Intent.ACTION_PACKAGE_REMOVED);
        p.addAction(Intent.ACTION_PACKAGE_CHANGED);
        p.addDataScheme("package");
        registerReceiver(pkgs, p);
        reload();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(tick);
        unregisterReceiver(pkgs);
        super.onDestroy();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try { widgetHost.startListening(); } catch (Throwable ignored) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { widgetHost.stopListening(); } catch (Throwable ignored) { }
    }

    private void stopTilt() { if (sensors != null) sensors.unregisterListener(tilt); }

    private void startTilt() {
        if (sensors != null && prefs.getBoolean("tilt", true)) {
            Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (g == null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g != null) sensors.registerListener(tilt, g, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTilt();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startTilt();
        if (clock != null) clock.invalidate();
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (drawer != null && drawer.getVisibility() == View.VISIBLE) closeDrawer();
    }

    // ---------------------------------------------------------------- UI

    private int px(float v) { return Math.round(v * dp); }

    private GradientDrawable glass(float radiusDp, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(radiusDp));
        g.setColor(fill);
        g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (vy < -1200 && Math.abs(vy) > Math.abs(vx) && drawer.getVisibility() != View.VISIBLE) { openDrawer(); return true; }
                if (vy > 1200 && Math.abs(vy) > Math.abs(vx) && drawer.getVisibility() != View.VISIBLE) { expandNotifications(); return true; }
                return false;
            }
        });

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(px(18), px(12), px(18), px(12));
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        clock = new SplitClock(this);
        home.addView(clock, new LinearLayout.LayoutParams(-1, px(190)));

        photo = new ImageView(this);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackground(glass(26, 0x22FFFFFF, 0x33FFFFFF));
        photo.setClipToOutline(true);
        photo.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(26)); }
        });
        photo.setOnLongClickListener(v -> { homeMenu(); return true; });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, px(170));
        plp.topMargin = px(14);
        home.addView(photo, plp);

        widgets = new LinearLayout(this);
        widgets.setOrientation(LinearLayout.VERTICAL);
        home.addView(widgets, new LinearLayout.LayoutParams(-1, -2));

        homeGrid = new GridLayout(this);
        homeGrid.setColumnCount(COLS);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = px(18);
        home.addView(homeGrid, glp);

        View spacer = new View(this);
        home.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView search = new TextView(this);
        search.setText("⌕  Search");
        search.setTextColor(0xDDFFFFFF);
        search.setTextSize(13);
        search.setGravity(Gravity.CENTER);
        search.setBackground(glass(18, 0x33FFFFFF, 0x44FFFFFF));
        search.setOnClickListener(v -> openSearch());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(px(120), px(34));
        slp.gravity = Gravity.CENTER_HORIZONTAL;
        slp.bottomMargin = px(14);
        home.addView(search, slp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(px(8), px(10), px(8), px(10));
        dock.setBackground(glass(30, 0x26FFFFFF, 0x40FFFFFF));
        home.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xE6080808);
        drawer.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        drawerGrid = new GridLayout(this);
        drawerGrid.setColumnCount(COLS);
        drawerGrid.setPadding(px(18), px(70), px(18), px(40));
        sv.addView(drawerGrid, new FrameLayout.LayoutParams(-1, -2));
        drawer.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));

        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top = in.getSystemWindowInsetTop();
            int bottom = in.getSystemWindowInsetBottom();
            home.setPadding(px(18), top + px(12), px(18), bottom + px(10));
            return in;
        });
        home.setOnLongClickListener(v -> { homeMenu(); return true; });
        drawer.setOnTouchListener((v, e) -> true);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (drawer.getVisibility() != View.VISIBLE) gestures.onTouchEvent(e);
        return super.dispatchTouchEvent(e);
    }

    private void openDrawer() {
        drawer.setAlpha(0f);
        drawer.setTranslationY(px(60));
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().alpha(1f).translationY(0).setDuration(220).start();
    }

    private void closeDrawer() {
        drawer.animate().alpha(0f).translationY(px(60)).setDuration(180)
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    private void expandNotifications() {
        try {
            Object sb = getSystemService("statusbar");
            sb.getClass().getMethod("expandNotificationsPanel").invoke(sb);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- apps

    private static String key(LauncherActivityInfo a) { return a.getComponentName().flattenToShortString(); }

    private void reload() {
        apps.clear();
        byKey.clear();
        UserHandle me = Process.myUserHandle();
        for (LauncherActivityInfo a : launcherApps.getActivityList(null, me)) {
            if (a.getComponentName().getPackageName().equals(getPackageName())) continue;
            apps.add(a);
            byKey.put(key(a), a);
        }
        Collections.sort(apps, (x, y) -> x.getLabel().toString().compareToIgnoreCase(y.getLabel().toString()));
        if (!prefs.contains("dock")) prefs.edit().putString("dock", defaultDock()).apply();
        if (!prefs.contains("pins")) prefs.edit().putString("pins", defaultPins()).apply();
        String ph = prefs.getString("photo", null);
        if (ph != null) {
            try { photo.setImageURI(Uri.parse(ph)); photo.setVisibility(View.VISIBLE); }
            catch (Throwable t) { photo.setVisibility(View.GONE); }
        } else {
            photo.setImageDrawable(null);
            photo.setVisibility(View.GONE);
        }
        fillHome();
        fillDrawer();
    }

    private String resolveKey(Intent i) {
        ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (r == null || r.activityInfo == null) return null;
        String pkg = r.activityInfo.packageName;
        for (LauncherActivityInfo a : apps) if (a.getComponentName().getPackageName().equals(pkg)) return key(a);
        return null;
    }

    private String defaultDock() {
        List<String> d = new ArrayList<>();
        Intent[] wants = {
                new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
                new Intent(Intent.ACTION_DIAL),
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
                new Intent(Settings.ACTION_SETTINGS),
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE)};
        for (Intent i : wants) { String k = resolveKey(i); if (k != null && !d.contains(k)) d.add(k); }
        return String.join(",", d);
    }

    private String defaultPins() {
        List<String> dockKeys = list("dock");
        List<String> p = new ArrayList<>();
        for (LauncherActivityInfo a : apps) {
            if (p.size() >= 12) break;
            if (!dockKeys.contains(key(a))) p.add(key(a));
        }
        return String.join(",", p);
    }

    private List<String> list(String name) {
        List<String> out = new ArrayList<>();
        String s = prefs.getString(name, "");
        if (s == null || s.isEmpty()) return out;
        for (String k : s.split(",")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void save(String name, List<String> l) { prefs.edit().putString(name, String.join(",", l)).apply(); }

    private void fillWidgets() {
        widgets.removeAllViews();
        int w = getResources().getDisplayMetrics().widthPixels - px(36);
        for (String sid : list("widgets")) {
            int id;
            try { id = Integer.parseInt(sid); } catch (NumberFormatException e) { continue; }
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null) continue;
            AppWidgetHostView v = widgetHost.createView(this, id, info);
            v.setAppWidget(id, info);
            v.setPadding(0, 0, 0, 0);
            int h = Math.max(px(info.minHeight > 0 ? 0 : 72), info.minHeight);
            if (h < px(48)) h = px(72);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.topMargin = px(12);
            final int fid = id;
            v.setOnLongClickListener(x -> { widgetMenu(fid, info); return true; });
            widgets.addView(v, lp);
        }
    }

    private void widgetMenu(int id, AppWidgetProviderInfo info) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(info.loadLabel(getPackageManager()))
                .setItems(new String[]{"Remove widget"}, (d, w) -> {
                    List<String> l = list("widgets"); l.remove(String.valueOf(id)); save("widgets", l);
                    try { widgetHost.deleteAppWidgetId(id); } catch (Throwable ignored) { }
                    fillWidgets();
                }).show();
    }

    private void pickWidget() {
        pendingWidget = widgetHost.allocateAppWidgetId();
        Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget);
        try { startActivityForResult(pick, REQ_PICK_WIDGET); }
        catch (Throwable t) { widgetHost.deleteAppWidgetId(pendingWidget); pendingWidget = -1; }
    }

    private void widgetPicked(int id) {
        AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
        if (info == null) { widgetHost.deleteAppWidgetId(id); return; }
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG_WIDGET, null);
                pendingWidget = id;
                return;
            } catch (Throwable ignored) { }
        }
        addWidget(id);
    }

    private void addWidget(int id) {
        List<String> l = list("widgets");
        if (!l.contains(String.valueOf(id))) l.add(String.valueOf(id));
        save("widgets", l);
        pendingWidget = -1;
        fillWidgets();
    }

    private void fillHome() {
        fillWidgets();
        homeGrid.removeAllViews();
        int cell = (getResources().getDisplayMetrics().widthPixels - px(36)) / COLS;
        for (String k : list("pins")) {
            LauncherActivityInfo a = byKey.get(k);
            if (a == null) continue;
            homeGrid.addView(appCell(a, cell, true, "pins"));
        }
        dock.removeAllViews();
        int dcell = (getResources().getDisplayMetrics().widthPixels - px(52)) / 5;
        for (String k : list("dock")) {
            LauncherActivityInfo a = byKey.get(k);
            if (a == null) continue;
            dock.addView(appCell(a, dcell, false, "dock"));
        }
    }

    private void fillDrawer() {
        drawerGrid.removeAllViews();
        int cell = (getResources().getDisplayMetrics().widthPixels - px(36)) / COLS;
        for (LauncherActivityInfo a : apps) drawerGrid.addView(appCell(a, cell, true, null));
    }

    private View appCell(LauncherActivityInfo a, int cell, boolean label, String from) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, px(6), 0, px(8));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(glassIcon(a));
        int s = px(56);
        c.addView(iv, new LinearLayout.LayoutParams(s, s));
        if (label) {
            TextView t = new TextView(this);
            t.setText(a.getLabel());
            t.setTextColor(0xE6FFFFFF);
            t.setTextSize(11);
            t.setSingleLine(true);
            t.setGravity(Gravity.CENTER);
            t.setShadowLayer(6, 0, 1, 0x88000000);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(cell - px(6), -2);
            tl.topMargin = px(5);
            c.addView(t, tl);
        }
        ViewGroup.LayoutParams lp = from != null && from.equals("dock")
                ? new LinearLayout.LayoutParams(cell, -2) : new GridLayout.LayoutParams();
        lp.width = cell;
        c.setLayoutParams(lp);
        c.setOnClickListener(v -> {
            try { launcherApps.startMainActivity(a.getComponentName(), a.getUser(), null, null); }
            catch (Throwable ignored) { }
            if (drawer.getVisibility() == View.VISIBLE) drawer.setVisibility(View.GONE);
        });
        c.setOnLongClickListener(v -> { appMenu(a, from); return true; });
        return c;
    }

    /** The app's icon in grey, on a rounded frosted-glass tile. */
    private Bitmap glassIcon(LauncherActivityInfo a) {
        String k = key(a);
        Bitmap cached = iconCache.get(k);
        if (cached != null) return cached;
        int s = px(56);
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x38FFFFFF);
        RectF r = new RectF(0, 0, s, s);
        cv.drawRoundRect(r, s * 0.26f, s * 0.26f, bg);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1, px(1)));
        edge.setColor(0x66FFFFFF);
        RectF r2 = new RectF(px(0.5f), px(0.5f), s - px(0.5f), s - px(0.5f));
        cv.drawRoundRect(r2, s * 0.26f, s * 0.26f, edge);
        Drawable d = a.getIcon(0);
        if (d != null) {
            Drawable m = d.mutate();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f);
            ColorMatrix bright = new ColorMatrix(new float[]{
                    1.15f, 0, 0, 0, 18,
                    0, 1.15f, 0, 0, 18,
                    0, 0, 1.15f, 0, 18,
                    0, 0, 0, 1, 0});
            cm.postConcat(bright);
            m.setColorFilter(new ColorMatrixColorFilter(cm));
            int in = Math.round(s * 0.14f);
            m.setBounds(in, in, s - in, s - in);
            m.draw(cv);
        }
        iconCache.put(k, bm);
        return bm;
    }

    // ---------------------------------------------------------------- menus

    private void appMenu(LauncherActivityInfo a, String from) {
        List<String> items = new ArrayList<>();
        if ("pins".equals(from)) items.add("Remove from home");
        else if ("dock".equals(from)) items.add("Remove from dock");
        else { items.add("Add to home"); items.add("Add to dock"); }
        items.add("App info");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.getLabel())
                .setItems(items.toArray(new String[0]), (dl, which) -> {
                    String it = items.get(which);
                    String k = key(a);
                    if (it.equals("Remove from home")) { List<String> l = list("pins"); l.remove(k); save("pins", l); }
                    else if (it.equals("Remove from dock")) { List<String> l = list("dock"); l.remove(k); save("dock", l); }
                    else if (it.equals("Add to home")) { List<String> l = list("pins"); if (!l.contains(k)) l.add(k); save("pins", l); }
                    else if (it.equals("Add to dock")) {
                        List<String> l = list("dock");
                        if (!l.contains(k)) { if (l.size() >= 5) l.remove(l.size() - 1); l.add(k); }
                        save("dock", l);
                    } else if (it.equals("App info")) {
                        try { launcherApps.startAppDetailsActivity(a.getComponentName(), a.getUser(), null, null); }
                        catch (Throwable ignored) { }
                    }
                    fillHome();
                }).show();
    }

    private void homeMenu() {
        if (drawer.getVisibility() == View.VISIBLE) return;
        String[] items = {"Add widget (Claude, Google search, ...)", "Change wallpaper", "Choose photo widget", "Remove photo widget",
                prefs.getBoolean("tilt", true) ? "Turn 3D tilt off" : "Turn 3D tilt on", "All apps"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("MURDHIIAS")
                .setItems(items, (dl, which) -> {
                    if (which == 0) pickWidget();
                    else if (which == 1) {
                        try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")); }
                        catch (Throwable ignored) { }
                    } else if (which == 2) {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("image/*");
                        startActivityForResult(i, REQ_PHOTO);
                    } else if (which == 3) {
                        prefs.edit().remove("photo").apply();
                        reload();
                    } else if (which == 4) {
                        boolean on = !prefs.getBoolean("tilt", true);
                        prefs.edit().putBoolean("tilt", on).apply();
                        stopTilt(); startTilt();
                        if (!on) { home.setTranslationX(0); home.setTranslationY(0); }
                    } else openDrawer();
                }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK_WIDGET) {
            int id = data != null ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidget) : pendingWidget;
            if (res != RESULT_OK) { if (id >= 0) widgetHost.deleteAppWidgetId(id); pendingWidget = -1; return; }
            AppWidgetProviderInfo info = widgetManager.getAppWidgetInfo(id);
            if (info == null && data != null) {
                ComponentName cn = data.getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER);
                if (cn != null && !widgetManager.bindAppWidgetIdIfAllowed(id, cn)) {
                    Intent b = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    b.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
                    b.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cn);
                    pendingWidget = id;
                    startActivityForResult(b, REQ_BIND_WIDGET);
                    return;
                }
            }
            widgetPicked(id);
            return;
        }
        if (req == REQ_BIND_WIDGET) {
            if (res == RESULT_OK && pendingWidget >= 0) widgetPicked(pendingWidget);
            else if (pendingWidget >= 0) { widgetHost.deleteAppWidgetId(pendingWidget); pendingWidget = -1; }
            return;
        }
        if (req == REQ_CONFIG_WIDGET) {
            if (res == RESULT_OK && pendingWidget >= 0) addWidget(pendingWidget);
            else if (pendingWidget >= 0) { widgetHost.deleteAppWidgetId(pendingWidget); pendingWidget = -1; }
            return;
        }
        if (req == REQ_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Throwable ignored) { }
            prefs.edit().putString("photo", u.toString()).apply();
            reload();
        }
    }

    private void openSearch() {
        try {
            Intent g = new Intent("android.search.action.GLOBAL_SEARCH");
            g.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(g);
        } catch (Throwable t) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))); }
            catch (Throwable ignored) { }
        }
    }
}
