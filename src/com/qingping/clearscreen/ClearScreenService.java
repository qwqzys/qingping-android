package com.qingping.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads the active window through the user-enabled accessibility service.
 *
 * It only clicks controls whose label is an explicit skip/close-ad control in
 * the top part of the screen. It never reads passwords, never takes
 * screenshots and never sends data anywhere.
 */
public class ClearScreenService extends AccessibilityService {

    private static final String TAG = "QingPingService";
    private static final long SESSION_MS = 15_000L;
    private static final long SCAN_INTERVAL_MS = 320L;
    private static final long CONTENT_SCAN_GAP_MS = 140L;
    private static final long MIN_ATTEMPT_GAP_MS = 100L;
    private static final long ACCESS_MIN_AGE_MS = 100L;
    private static final long ACCESS_VERIFY_MS = 380L;
    private static final long ACCESS_ACTION_VERIFY_MS = 300L;
    private static final long CLICK_JUMP_WINDOW_MS = 1_600L;
    private static final long JUMP_BACK_COOLDOWN_MS = 3_000L;
    private static final long MAX_CLICK_AGE_MS = 1_200L;
    private static final int MAX_ROOT_TAPS = 4;
    private static final long ROOT_TAP_MIN_AGE_MS = 120L;
    private static final long ROOT_VERIFY_WINDOW_MS = 800L;

    private static final Set<String> IGNORED_PACKAGES = new HashSet<>();

    /** Apps whose splash skip control is often an image without readable text. */
    private static final Set<String> TOP_RIGHT_FALLBACK_PACKAGES = new HashSet<>();

    static {
        TOP_RIGHT_FALLBACK_PACKAGES.add("cn.damai");
    }

    static {
        IGNORED_PACKAGES.add("android");
        IGNORED_PACKAGES.add("com.android.systemui");
        IGNORED_PACKAGES.add("com.android.settings");
        IGNORED_PACKAGES.add("com.android.permissioncontroller");
        IGNORED_PACKAGES.add("com.google.android.permissioncontroller");
        IGNORED_PACKAGES.add("com.android.packageinstaller");
        IGNORED_PACKAGES.add("com.google.android.packageinstaller");
        IGNORED_PACKAGES.add("com.oplus.securitypermission");
        IGNORED_PACKAGES.add("com.coloros.securitypermission");
        IGNORED_PACKAGES.add("com.android.inputmethod.latin");
        IGNORED_PACKAGES.add("com.oplus.inputmethod");
        IGNORED_PACKAGES.add("com.baidu.input");
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private StatsDb db;

    private String foregroundPkg = "";
    private String foregroundClass = "";
    private long foregroundAt;

    private String sessionPkg = "";
    private String sessionClass = "";
    private boolean foregroundHandled;
    private long sessionStart;
    private int sessionClicks;
    private boolean demoSession;
    private String pendingPkg = "";
    private String pendingClass = "";
    private Candidate pendingCandidate;
    private boolean pendingRoot;
    private int pendingStage;
    private int rootTapAttempts;
    private long rootTapAt;
    private long lastAttemptAt;
    private long lastContentScanAt;

    private String lastClickPkg = "";
    private long lastClickAt;
    private boolean lastClickLooksLikeAd;
    private long lastJumpBackAt;

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSessionActive()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - sessionStart > SESSION_MS) {
                endSession();
                return;
            }
            trySkip();
            if (isSessionActive()) {
                handler.postDelayed(this, SCAN_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        db = new StatsDb(this);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
            info.notificationTimeout = 80;
            setServiceInfo(info);
        }
        Log.i(TAG, "accessibility service connected");
        Diag.log(this, "service connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || prefs == null) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                handleWindow(event, now);
                break;

            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                if (isProtected(foregroundPkg, foregroundClass)
                        && now - lastContentScanAt >= CONTENT_SCAN_GAP_MS) {
                    lastContentScanAt = now;
                    if (!foregroundHandled
                            && !isSessionActive()
                            && now - foregroundAt <= SESSION_MS
                            && sessionPkg.isEmpty()
                            && shouldArmSplash(foregroundPkg, foregroundClass)) {
                        armSession(foregroundPkg, foregroundClass, now);
                    }
                    if (isSessionActive()) {
                        trySkip();
                    }
                }
                break;

            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                CharSequence clickedPackage = event.getPackageName();
                if (clickedPackage != null) {
                    lastClickPkg = clickedPackage.toString();
                    lastClickAt = now;
                    lastClickLooksLikeAd = containsAdWord(event);
                }
                break;

            default:
                break;
        }
    }

    private void handleWindow(AccessibilityEvent event, long now) {
        CharSequence packageSequence = event.getPackageName();
        if (packageSequence == null) {
            return;
        }
        String pkg = packageSequence.toString();
        CharSequence classSequence = event.getClassName();
        String cls = classSequence == null ? "" : classSequence.toString();

        boolean packageChanged = !pkg.equals(foregroundPkg);
        String previousPkg = foregroundPkg;
        String previousClass = foregroundClass;

        foregroundPkg = pkg;
        foregroundClass = cls;
        foregroundAt = now;

        if (packageChanged) {
            endSession();
            foregroundHandled = false;
            if (prefs.isJumpEnabled()
                    && shouldReturnFromJump(previousPkg, previousClass, pkg, cls, now)) {
                return;
            }
            if (isProtected(pkg, cls)) {
                if (shouldArmSplash(pkg, cls)) {
                    armSession(pkg, cls, now);
                } else if (isMainLikeClass(cls)) {
                    // Entering the app's normal UI is not a splash session.
                    foregroundHandled = true;
                }
            }
        } else if (!isProtected(pkg, cls)) {
            endSession();
            foregroundHandled = false;
        } else if (!shouldArmSplash(pkg, cls)) {
            endSession();
            // If the class is clearly main UI, lock the session. If it is a
            // generic dialog/FrameLayout, allow a later content event to arm
            // once the ad tree has actually appeared.
            foregroundHandled = isMainLikeClass(cls);
        } else if (sessionPkg.isEmpty() && !foregroundHandled) {
            armSession(pkg, cls, now);
        }
    }

    /**
     * A session may only start on a splash-like Activity, or on a small
     * ad-dialog window tree. Normal app UI can have clickable controls in the
     * top-right too (menus, avatars, search); those must never be tapped.
     */
    private boolean shouldArmSplash(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (isSplashClass(cls)) {
            return true;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root != null
                    && pkg.equals(String.valueOf(root.getPackageName()))
                    && isLikelySplashRoot(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isMainLikeClass(String cls) {
        if (cls == null) {
            return false;
        }
        String lower = cls.toLowerCase(Locale.ROOT);
        return lower.contains("mainactivity") || lower.contains("homepage")
                || lower.contains(".home") || lower.contains("channelpage")
                || lower.contains("searchactivity") || lower.contains("detailactivity");
    }

    private boolean isSplashClass(String cls) {
        if (cls == null) {
            return false;
        }
        String lower = cls.toLowerCase(Locale.ROOT);
        return lower.contains("splash") || lower.contains("adactivity")
                || lower.contains("advert") || lower.contains("welcome")
                || lower.contains("launch") || lower.contains("loading");
    }

    /**
     * Splash windows are tiny node trees. A normal feed/main page is hundreds
     * of nodes, so this prevents the image-button fallback from firing there.
     */
    private boolean isLikelySplashRoot(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        String rootClass = String.valueOf(root.getClassName());
        if (isSplashClass(rootClass)) {
            return true;
        }
        int nodes = treeSize(root, 45);
        if (nodes <= 30) {
            return true;
        }
        // Small ad windows often carry advert/splash view ids.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < 45) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) {
                continue;
            }
            String id = node.getViewIdResourceName();
            if (id != null) {
                String lower = id.toLowerCase(Locale.ROOT);
                if (lower.contains("advert") || lower.contains("splash")
                        || lower.contains("/skip") || lower.endsWith(":id/skip")) {
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return false;
    }

    private int treeSize(AccessibilityNodeInfo root, int cap) {
        if (root == null) {
            return 0;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int count = 0;
        while (!queue.isEmpty() && count < cap) {
            AccessibilityNodeInfo node = queue.removeFirst();
            count++;
            if (node == null) {
                continue;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.addLast(child);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        return count;
    }

    private boolean shouldReturnFromJump(String previousPkg, String previousClass,
                                         String targetPkg, String targetClass, long now) {
        if (previousPkg == null || previousPkg.isEmpty()
                || previousPkg.equals(targetPkg)) {
            return false;
        }
        if (!isProtected(previousPkg, previousClass)) {
            return false;
        }
        if (!previousPkg.equals(lastClickPkg)) {
            return false;
        }
        long clickAge = now - lastClickAt;
        if (clickAge < 0 || clickAge > CLICK_JUMP_WINDOW_MS) {
            return false;
        }
        if (now - lastJumpBackAt < JUMP_BACK_COOLDOWN_MS) {
            return false;
        }
        if (!isJumpTarget(targetPkg, targetClass, lastClickLooksLikeAd)) {
            return false;
        }

        lastJumpBackAt = now;
        lastClickPkg = "";
        final String sourcePackage = previousPkg;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean wentBack = performGlobalAction(GLOBAL_ACTION_BACK);
                if (wentBack) {
                    record(sourcePackage, StatsDb.KIND_JUMP);
                    Log.i(TAG, "misclick jump returned: " + sourcePackage);
                }
            }
        }, 260L);
        return true;
    }

    private boolean isJumpTarget(String pkg, String cls, boolean adLikeClick) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        String c = cls == null ? "" : cls.toLowerCase(Locale.ROOT);

        if (p.equals(getPackageName())) {
            return false;
        }

        // Strong signal: a browser or an app marketplace opened right after a
        // tap inside another app. This is the classic ad-click destination.
        if (p.contains("browser") || p.contains("chrome") || p.contains("firefox")
                || p.contains("opera") || p.contains("mtt") || p.contains("ucmobile")
                || p.contains("quark") || p.contains("searchbox")
                || p.contains("market") || p.contains("appstore")
                || p.contains("qqdownloader")) {
            return true;
        }

        // Weaker signal: shopping apps are often the landing page of a
        // mis-touch ad, but users also open them intentionally. Require the
        // tapped control to look like an ad call-to-action.
        boolean shopping = p.contains("taobao") || p.contains("tmall")
                || p.contains("jingdong") || p.contains("pinduoduo")
                || p.contains("suning") || p.contains("vipshop");
        if (shopping && adLikeClick) {
            return true;
        }

        return c.contains("browser") && !c.contains("inappbrowser");
    }

    private boolean containsAdWord(AccessibilityEvent event) {
        List<CharSequence> texts = event.getText();
        if (texts == null) {
            return false;
        }
        for (CharSequence value : texts) {
            if (value == null) {
                continue;
            }
            String text = value.toString();
            if (text.contains("广告") || text.contains("下载") || text.contains("安装")
                    || text.contains("了解详情") || text.contains("查看详情")
                    || text.contains("立即领取") || text.contains("立即购买")
                    || text.contains("打开看看") || text.contains("免费")
                    || text.toLowerCase(Locale.ROOT).contains("download")) {
                return true;
            }
        }
        return false;
    }

    private void armSession(String pkg, String cls, long now) {
        if (!prefs.isSplashEnabled()) {
            return;
        }
        sessionPkg = pkg;
        sessionClass = cls == null ? "" : cls;
        foregroundHandled = true;
        sessionStart = now;
        sessionClicks = 0;
        demoSession = pkg.equals(getPackageName()) && sessionClass.contains("TestAdActivity");
        pendingPkg = "";
        pendingClass = "";
        pendingCandidate = null;
        pendingRoot = false;
        pendingStage = 0;
        rootTapAttempts = 0;
        rootTapAt = 0L;
        lastAttemptAt = 0L;
        Diag.log(this, "arm pkg=" + pkg + " cls=" + sessionClass + " demo=" + demoSession);
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, 160L);
    }

    private boolean isSessionActive() {
        return !sessionPkg.isEmpty()
                && sessionClicks == 0
                && SystemClock.uptimeMillis() - sessionStart <= SESSION_MS;
    }

    private void endSession() {
        handler.removeCallbacks(scanRunnable);
        sessionPkg = "";
        sessionClass = "";
        sessionClicks = 0;
        demoSession = false;
    }

    private void trySkip() {
        if (!isSessionActive() || !prefs.isSplashEnabled()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastAttemptAt < MIN_ATTEMPT_GAP_MS) {
            return;
        }
        lastAttemptAt = now;

        Candidate candidate = findCandidateAnyWindow();
        if (candidate == null) {
            return;
        }

        // Two independent user-selectable modes.
        // Root mode: inject the auto-detected control immediately.
        // Accessibility mode: real touch first, then ACTION_CLICK, then a
        // second fresh touch; never waits for the slow root path.
        if (!demoSession && prefs.isRootTapEnabled()) {
            if (now - sessionStart >= ROOT_TAP_MIN_AGE_MS) {
                beginRootTap(candidate);
            }
            return;
        }
        if (now - sessionStart < ACCESS_MIN_AGE_MS) {
            return;
        }
        beginAccessibilityAttempt(candidate, 1);
    }

    /**
     * Search every interactive window that belongs to the foreground app.
     * Splash ads are sometimes rendered in an application-attached dialog
     * whose root is a FrameLayout rather than the Activity's decor view.
     */
    private Candidate findCandidateAnyWindow() {
        Candidate best = null;
        int windowCount = 0;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null) {
                        continue;
                    }
                    AccessibilityNodeInfo root;
                    try {
                        root = window.getRoot();
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    if (root == null) {
                        continue;
                    }
                    CharSequence pkg = root.getPackageName();
                    if (pkg == null || !sessionPkg.equals(pkg.toString())) {
                        continue;
                    }
                    windowCount++;
                    Candidate candidate = findCandidate(root);
                    if (candidate != null
                            && (best == null || candidate.score > best.score)) {
                        best = candidate;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        if (best == null) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && sessionPkg.equals(pkg.toString())) {
                        best = findCandidate(root);
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        Diag.log(this, "search windows=" + windowCount + " candidate=" + (best != null));
        return best;
    }

    private void beginRootTap(Candidate candidate) {
        if (candidate == null || rootTapAttempts >= MAX_ROOT_TAPS) {
            clearPending();
            endSession();
            return;
        }
        rootTapAttempts++;
        pendingPkg = sessionPkg;
        pendingClass = sessionClass;
        pendingCandidate = candidate;
        pendingRoot = true;
        pendingStage = 0;
        rootTapAt = SystemClock.uptimeMillis();
        sessionClicks = 1;
        handler.removeCallbacks(scanRunnable);
        Diag.log(this, "root attempt=" + rootTapAttempts + "/" + MAX_ROOT_TAPS
                + " bounds=" + candidate.bounds);
        RootInput.tap(this, candidate.bounds.exactCenterX(),
                candidate.bounds.exactCenterY());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verifyTextClick();
            }
        }, 300L);
    }

    /**
     * Accessibility-only attempt sequence:
     * stage 1 = touch gesture, stage 2 = ACTION_CLICK, stage 3 = fresh touch.
     * Each stage gets one short verification window; failure ends the
     * session instead of drifting into random main-screen clicks.
     */
    private void beginAccessibilityAttempt(Candidate candidate, int stage) {
        if (candidate == null) {
            clearPending();
            endSession();
            return;
        }
        pendingPkg = sessionPkg;
        pendingClass = sessionClass;
        pendingCandidate = candidate;
        pendingRoot = false;
        pendingStage = stage;
        sessionClicks = 1;
        handler.removeCallbacks(scanRunnable);

        boolean issued;
        if (stage == 2) {
            issued = performActionClick(candidate);
        } else {
            // For our own test page, ACTION_CLICK is the most reliable path.
            // For real apps, try a real touch first.
            issued = demoSession && performActionClick(candidate);
            if (!issued) {
                issued = tapCenter(candidate.bounds);
            }
            if (!issued) {
                issued = performActionClick(candidate);
            }
        }
        Diag.log(this, "accessibility attempt stage=" + stage + " issued=" + issued
                + " bounds=" + candidate.bounds);
        if (!issued) {
            clearPending();
            endSession();
            return;
        }
        long delay = stage == 2 ? ACCESS_ACTION_VERIFY_MS : ACCESS_VERIFY_MS;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                verifyTextClick();
            }
        }, delay);
    }

    private boolean performActionClick(Candidate candidate) {
        if (candidate == null || candidate.clickTarget == null) {
            return false;
        }
        try {
            return candidate.clickTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void verifyTextClick() {
        String pkg = pendingPkg;
        String cls = pendingClass;
        if (pkg.isEmpty()) {
            return;
        }
        Diag.log(this, "verify pkg=" + pkg + " fg=" + foregroundPkg
                + " fgCls=" + foregroundClass + " root=" + pendingRoot
                + " stage=" + pendingStage);
        if (!pkg.equals(foregroundPkg)) {
            clearPending();
            return;
        }

        // The built-in test page is not a real ad. It must only report
        // success after TestAdActivity has actually left the foreground.
        // Never show a success toast just because a tap was issued.
        if (demoSession) {
            if (!foregroundClass.contains("TestAdActivity")) {
                clearPending();
                onDemoClicked();
                return;
            }
        } else if (isSuccessDestination(pkg)) {
            finishVerified(pkg);
            return;
        }

        boolean wasSplashClass = cls.toLowerCase(Locale.ROOT).contains("splash");
        boolean nowSplashClass = foregroundClass.toLowerCase(Locale.ROOT).contains("splash");
        if (wasSplashClass && !nowSplashClass) {
            clearPending();
            endSession();
            return;
        }

        Candidate fresh = findCandidateAnyWindow();
        if (fresh == null) {
            clearPending();
            endSession();
            return;
        }

        if (pendingRoot) {
            long now = SystemClock.uptimeMillis();
            if (rootTapAttempts < MAX_ROOT_TAPS
                    && now - rootTapAt >= ROOT_VERIFY_WINDOW_MS) {
                beginRootTap(fresh);
                return;
            }
            if (rootTapAttempts < MAX_ROOT_TAPS) {
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        verifyTextClick();
                    }
                }, 250L);
                return;
            }
            clearPending();
            endSession();
            return;
        }

        if (pendingStage == 1) {
            beginAccessibilityAttempt(fresh, 2);
            return;
        }
        if (pendingStage == 2) {
            beginAccessibilityAttempt(fresh, 3);
            return;
        }
        clearPending();
        endSession();
    }

    private void clearPending() {
        pendingPkg = "";
        pendingClass = "";
        pendingCandidate = null;
        pendingRoot = false;
        pendingStage = 0;
    }

    private void finishVerified(String pkg) {
        Diag.log(this, "verified skip pkg=" + pkg + " cls=" + foregroundClass);
        clearPending();
        record(pkg, StatsDb.KIND_SPLASH);
        Log.i(TAG, "splash skipped: " + pkg);
        sessionPkg = "";
        sessionClass = "";
        sessionClicks = 1;
        handler.removeCallbacks(scanRunnable);
    }

    private void onDemoClicked() {
        clearPending();
        toast("拦截成功：已自动点击“跳过”");
        sessionPkg = "";
        sessionClass = "";
        demoSession = false;
        sessionClicks = 1;
        handler.removeCallbacks(scanRunnable);
    }

    private Candidate findCandidate(AccessibilityNodeInfo root) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        if (screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }

        String[] queries = {"跳过", "skip", "关闭广告", "关闭", "close ad", "close"};
        Set<String> seen = new HashSet<>();
        Candidate best = null;

        for (String query : queries) {
            List<AccessibilityNodeInfo> nodes;
            try {
                nodes = root.findAccessibilityNodeInfosByText(query);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (nodes == null) {
                continue;
            }
            for (AccessibilityNodeInfo node : new ArrayList<>(nodes)) {
                if (node == null || !node.isVisibleToUser()) {
                    continue;
                }
                CharSequence nodePackage = node.getPackageName();
                if (nodePackage != null && !sessionPkg.equals(nodePackage.toString())) {
                    continue;
                }

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.width() <= 0 || bounds.height() <= 0
                        || bounds.right <= 0 || bounds.bottom <= 0
                        || bounds.left >= screenWidth || bounds.top >= screenHeight) {
                    continue;
                }
                // A "跳过" text drawn on a full-screen container is not a
                // button. Never let a giant container turn into an ad click.
                if (bounds.width() > screenWidth * 0.75f
                        || bounds.height() > screenHeight * 0.30f) {
                    continue;
                }

                String text = nodeText(node);
                if (text.isEmpty()) {
                    continue;
                }
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.contains("登录") || lower.contains("注册")
                        || lower.contains("教程") || lower.contains("引导")
                        || lower.contains("新手") || lower.contains("密码")) {
                    continue;
                }

                boolean saysSkip = lower.contains("跳过") || lower.contains("skip");
                boolean saysCloseAd = lower.contains("关闭广告")
                        || lower.contains("close ad") || lower.contains("closead");
                boolean exactClose = lower.equals("关闭") || lower.equals("close");
                if (!saysSkip && !saysCloseAd && !exactClose) {
                    continue;
                }

                boolean topArea = bounds.top < screenHeight * 0.34f;
                boolean rightArea = bounds.left > screenWidth * 0.48f;
                if (exactClose && !(topArea && rightArea
                        && bounds.width() < screenWidth * 0.35f
                        && bounds.height() < screenHeight * 0.12f)) {
                    continue;
                }

                String key = text + "|" + bounds.left + "," + bounds.top
                        + "," + bounds.right + "," + bounds.bottom;
                if (!seen.add(key)) {
                    continue;
                }

                int score = 0;
                if (saysSkip) {
                    score += 100;
                } else if (saysCloseAd) {
                    score += 86;
                } else {
                    score += 24;
                }
                if (topArea) {
                    score += 25;
                }
                if (rightArea) {
                    score += 20;
                }
                if (bounds.width() < screenWidth * 0.5f) {
                    score += 8;
                }
                if (bounds.height() < screenHeight * 0.14f) {
                    score += 8;
                }
                if (node.isClickable()) {
                    score += 16;
                }

                AccessibilityNodeInfo clickTarget = clickableAncestor(node);
                if (clickTarget != null) {
                    Rect targetBounds = new Rect();
                    clickTarget.getBoundsInScreen(targetBounds);
                    if (targetBounds.width() > screenWidth * 0.75f
                            || targetBounds.height() > screenHeight * 0.30f) {
                        clickTarget = null;
                    }
                }
                if (clickTarget != null && !clickTarget.equals(node)) {
                    score += 10;
                }
                if (clickTarget == null) {
                    // No clickable ancestor: only a high-confidence, small
                    // skip control in the upper part may use a gesture tap.
                    if (!(saysSkip && topArea && bounds.width() < screenWidth * 0.45f)) {
                        continue;
                    }
                }

                int threshold = exactClose ? 96 : 110;
                if (score < threshold) {
                    continue;
                }
                if (best == null || score > best.score) {
                    best = new Candidate(node, clickTarget, bounds, score);
                }
            }
        }

        // Some splash SDKs render the skip control as a small clickable
        // ImageView with no text/contentDescription. For known apps, accept a
        // small top-right clickable control as a last resort.
        if (best == null && TOP_RIGHT_FALLBACK_PACKAGES.contains(sessionPkg)
                && isLikelySplashRoot(root)) {
            best = findTopRightClickable(root, screenWidth, screenHeight);
        }
        return best;
    }

    /**
     * A conservative fallback for image-only skip buttons. It only considers
     * small, enabled, clickable controls in the top-right corner and rejects
     * common menu/share/search labels.
     */
    private Candidate findTopRightClickable(AccessibilityNodeInfo root,
                                            int screenWidth, int screenHeight) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(root);
        int visited = 0;
        Candidate best = null;

        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node == null) {
                continue;
            }
            try {
                if (node.isClickable() && node.isEnabled() && node.isVisibleToUser()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int width = bounds.width();
                    int height = bounds.height();
                    String text = nodeText(node);
                    boolean menuLike = text.contains("更多") || text.contains("菜单")
                            || text.contains("分享") || text.contains("搜索")
                            || text.contains("设置") || text.contains("登录")
                            || text.contains("客服") || text.contains("反馈")
                            || text.toLowerCase(Locale.ROOT).contains("menu")
                            || text.toLowerCase(Locale.ROOT).contains("more");


                    boolean topRight = bounds.left > screenWidth * 0.66f
                            && bounds.top < screenHeight * 0.20f
                            && bounds.right <= screenWidth + 2
                            && bounds.bottom < screenHeight * 0.24f;
                    boolean small = width >= dp(24) && width <= dp(120)
                            && height >= dp(16) && height <= dp(90);
                    if (topRight && small && !menuLike) {
                        String key = text + "|" + bounds.left + "," + bounds.top
                                + "," + bounds.right + "," + bounds.bottom;
                        if (seen.add(key)) {
                            int score = 80;
                            if (bounds.left > screenWidth * 0.84f) {
                                score += 10;
                            }
                            if (bounds.top < screenHeight * 0.12f) {
                                score += 10;
                            }
                            if (best == null || score > best.score) {
                                best = new Candidate(node, node, bounds, score);
                            }
                        }
                    }
                }

                int childCount = node.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    try {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) {
                            queue.addLast(child);
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current.isClickable() && current.isEnabled()) {
                return current;
            }
            AccessibilityNodeInfo parent;
            try {
                parent = current.getParent();
            } catch (RuntimeException ignored) {
                return null;
            }
            current = parent;
        }
        return null;
    }

    private String nodeText(AccessibilityNodeInfo node) {
        StringBuilder builder = new StringBuilder();
        CharSequence text = node.getText();
        if (text != null) {
            builder.append(text);
        }
        CharSequence description = node.getContentDescription();
        if (description != null) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(description);
        }
        return builder.toString().trim();
    }

    /** Only count a transition that looks like the app's real home screen. */
    private boolean isSuccessDestination(String pkg) {
        if (!pkg.equals(foregroundPkg)) {
            return false;
        }
        String lower = foregroundClass.toLowerCase(Locale.ROOT);
        if (lower.contains("splash")) {
            return false;
        }
        if ("cn.damai".equals(pkg)) {
            return lower.contains("homepage") || lower.contains("mainactivity")
                    || lower.contains("home");
        }
        if (lower.contains("ad") || lower.contains("detail") || lower.contains("web")
                || lower.contains("browser") || lower.contains("landing")
                || lower.contains("promotion")) {
            return false;
        }
        return true;
    }

    private boolean tapCenter(Rect bounds) {
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY());
    }

    private boolean tapAt(float x, float y) {
        if (x <= 0 || y <= 0) {
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
                    .build();
            return dispatchGesture(gesture,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription description) {
                            Diag.log(ClearScreenService.this,
                                    "gesture completed at " + x + "," + y);
                        }

                        @Override
                        public void onCancelled(GestureDescription description) {
                            Diag.log(ClearScreenService.this,
                                    "gesture CANCELLED at " + x + "," + y);
                        }
                    }, null);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isProtected(String pkg, String cls) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (pkg.equals(getPackageName())) {
            return cls != null && cls.contains("TestAdActivity");
        }
        if (IGNORED_PACKAGES.contains(pkg)) {
            return false;
        }
        String lower = pkg.toLowerCase(Locale.ROOT);
        if (lower.contains("launcher") || lower.contains("systemui")
                || lower.contains("permissioncontroller") || lower.contains("packageinstaller")
                || lower.contains("inputmethod")) {
            return false;
        }
        return true;
    }

    private void record(String pkg, int kind) {
        if (pkg == null || pkg.isEmpty() || db == null) {
            return;
        }
        final String label = resolveLabel(pkg);
        try {
            writer.execute(new Runnable() {
                @Override
                public void run() {
                    db.record(pkg, label, kind);
                }
            });
        } catch (RuntimeException ignored) {
            // The service may be shutting down; data is best-effort here.
        }
    }

    private String resolveLabel(String pkg) {
        try {
            PackageManager manager = getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(pkg, 0);
            CharSequence label = manager.getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private void toast(String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ClearScreenService.this, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onInterrupt() {
        endSession();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        writer.shutdownNow();
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }

    private static final class Candidate {
        final AccessibilityNodeInfo node;
        final AccessibilityNodeInfo clickTarget;
        final Rect bounds;
        final int score;

        Candidate(AccessibilityNodeInfo node, AccessibilityNodeInfo clickTarget,
                  Rect bounds, int score) {
            this.node = node;
            this.clickTarget = clickTarget;
            this.bounds = bounds;
            this.score = score;
        }
    }
}
