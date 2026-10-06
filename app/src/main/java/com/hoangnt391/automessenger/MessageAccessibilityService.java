package com.hoangnt391.automessenger;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class MessageAccessibilityService extends AccessibilityService {
    private static final String DEBUG_CHANNEL = "automessenger_debug";
    private static MessageAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService debounceScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Object pendingLock = new Object();
    private final List<String> pendingMessages = new ArrayList<>();
    private ScheduledFuture<?> pendingFlush;
    private String lastQueuedText = "";
    private long lastQueuedAt = 0L;
    private AccessibilityNodeInfo lastInput;
    private volatile boolean replying = false;
    private String lastIncoming = "";
    private String lastSent = "";
    private long lastReplyAt = 0L;
    private volatile boolean ashnaWebBusy = false;
    private String ashnaWebTargetPackage = "";
    private String ashnaWebQuestion = "";
    private String ashnaSubmittedInstruction = "";
    private android.webkit.WebView ashnaHiddenWebView;
    private android.view.WindowManager ashnaWebWindowManager;
    private android.view.WindowManager.LayoutParams ashnaWebWindowParams;
    private ReplyCallback ashnaWebCallback;
    private int ashnaLoginReadyChecks = 0;
    private android.widget.Button ashnaLoginCloseButton;
    private String ashnaLastCandidate = "";
    private int ashnaStableCandidateChecks = 0;
    private String ashnaBaselineBody = "";
    private String ashnaConversationContext = "";
    private final List<String> deferredIncomingBatches = new ArrayList<>();

    private void ensureDebugChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.app.NotificationManager nm =
                    getSystemService(android.app.NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(DEBUG_CHANNEL) == null) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        DEBUG_CHANNEL, "AutoMessenger - Nhật ký",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT));
            }
        }
    }

    private void postDebug(String message) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                ensureDebugChannel();
                android.app.Notification.Builder b =
                        android.os.Build.VERSION.SDK_INT >= 26
                                ? new android.app.Notification.Builder(this, DEBUG_CHANNEL)
                                : new android.app.Notification.Builder(this);
                b.setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AutoMessenger • AshnaAI")
                        .setContentText(message)
                        .setAutoCancel(true);
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.notify(4102, b.build());
            } catch (Exception ignored) {}
        });
    }

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        ensureDebugChannel();
        postDebug("Trợ năng đã kết nối. Chờ tin nhắn mới.");
    }

    public static boolean isRunning() { return instance != null; }

    public static MessageAccessibilityService getInstance() { return instance; }

    public static boolean isChatAppActive() {
        MessageAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        return isSupportedChatPackage(root.getPackageName().toString());
    }

    public static boolean isMessengerActive() { return isChatAppActive(); }

    private static boolean isSupportedChatPackage(String pkg) {
        return "com.facebook.orca".equals(pkg)
                || "com.zing.zalo".equals(pkg)
                || "com.whatsapp".equals(pkg)
                || "org.telegram.messenger".equals(pkg)
                || "com.openai.chatgpt".equals(pkg);
    }

    public static void handleScreenMessage(String text) {
        MessageAccessibilityService s = instance;
        if (s != null && text != null) s.handleDetectedIncoming(text.trim());
    }

    private void handleDetectedIncoming(String incoming) {
        if (incoming == null || incoming.isEmpty()) return;
        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;
        if (incoming.equals(lastSent)) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);
        queueIncomingMessage(incoming);
    }

    /**
     * Gom tin nhắn theo "quiet period" 3 giây:
     * - Mỗi tin mới sẽ reset đồng hồ 3 giây.
     * - Nếu người kia nhắn liên tục, tất cả tin được gom thành 1 batch.
     * - Chỉ khi im lặng đủ 3 giây mới chuyển batch cho chatbot.
     */
    private void queueIncomingMessage(String incoming) {
        final String text = incoming == null ? "" : incoming.trim();
        if (text.isEmpty()) return;

        synchronized (pendingLock) {
            long now = System.currentTimeMillis();

            // Accessibility có thể phát lại cùng một tin nhiều lần.
            // Chỉ bỏ qua bản sao trong 1.2 giây, không làm mất tin giống nhau
            // nếu người kia thực sự gửi lại sau đó.
            if (text.equals(lastQueuedText) && now - lastQueuedAt < 1200L) return;
            lastQueuedText = text;
            lastQueuedAt = now;

            pendingMessages.add(text);

            // Có tin mới là bắt đầu/reset lại 3 giây im lặng.
            if (pendingFlush != null) pendingFlush.cancel(false);

            postDebug("Có tin mới. Gom tin và chờ 3s im lặng trước khi chatbot trả lời...");

            pendingFlush = debounceScheduler.schedule(
                    this::flushPendingMessages, 3L, TimeUnit.SECONDS);
        }
    }

    private void flushPendingMessages() {
        final String batch;
        synchronized (pendingLock) {
            if (pendingMessages.isEmpty()) {
                pendingFlush = null;
                return;
            }
            if (ashnaWebBusy) {
                postDebug("AI đang trả lời. Tin mới được giữ lại để nối tiếp cuộc trò chuyện.");
                if (pendingFlush != null) pendingFlush.cancel(false);
                pendingFlush = debounceScheduler.schedule(this::flushPendingMessages, 1000L, TimeUnit.MILLISECONDS);
                return;
            }
            batch = joinPendingMessages(pendingMessages);
            pendingMessages.clear();
            pendingFlush = null;
        }

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) return;

        lastIncoming = batch;
        postDebug("Đã gom " + countMessages(batch) + " tin. Đang xử lý 1 lượt hội thoại...");
        generateAndSend(batch);
    }

    private String joinPendingMessages(List<String> messages) {
        StringBuilder b = new StringBuilder();
        for (String message : messages) {
            if (message == null || message.trim().isEmpty()) continue;
            if (b.length() > 0) b.append("\n");
            b.append(message.trim());
        }
        String result = b.toString();
        return result.length() > 8000 ? result.substring(result.length() - 8000) : result;
    }

    private int countMessages(String batch) {
        if (batch == null || batch.isEmpty()) return 0;
        return batch.split("\\n").length;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return;

        // Never inspect Settings, launcher, permission screens, etc.
        // This is the main guard against the old "nhảy loạn tap" behaviour.
        String pkg = root.getPackageName().toString();
        if (!isSupportedChatPackage(pkg)) {
            safeRecycle(root);
            return;
        }

        AccessibilityNodeInfo input = findEditable(root);
        if (input != null && input.isVisibleToUser()) replaceLastInput(input);

        if (!getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) {
            safeRecycle(root);
            return;
        }
        String incoming = extractLatestMessage(root, event);
        if (incoming != null && !incoming.trim().isEmpty()) {
            ashnaConversationContext = buildRecentConversationContext(root, incoming.trim());
        }
        safeRecycle(root);
        if (incoming == null || incoming.trim().isEmpty()) return;
        incoming = incoming.trim();

        if (incoming.equals(lastSent)) return;
        if (incoming.length() > 4000) incoming = incoming.substring(0, 4000);

        queueIncomingMessage(incoming);
    }

    private void generateAndSend(final String incoming) {
        if (ashnaWebBusy) {
            synchronized (pendingLock) {
                if (incoming != null && !incoming.trim().isEmpty()) deferredIncomingBatches.add(incoming.trim());
            }
            postDebug("AI đang xử lý. Lượt mới đã được xếp hàng.");
            return;
        }
        replying = true;
        AccessibilityNodeInfo current = getRootInActiveWindow();
        ashnaWebTargetPackage = value(current == null ? null : current.getPackageName());
        safeRecycle(current);
        ashnaWebQuestion = incoming;
        AccessibilityNodeInfo contextRoot = getRootInActiveWindow();
        String liveContext = buildRecentConversationContext(contextRoot, incoming);
        if (!liveContext.isEmpty()) ashnaConversationContext = liveContext;
        safeRecycle(contextRoot);
        postDebug("Ashna Web Free: xử lý ngầm...");
        openAshnaWebAndAsk(incoming);
    }

    /** Ashna Web Free runs inside an invisible accessibility WebView. */
    private void openAshnaWebAndAsk(final String question) {
        requestAshnaWebReply(question, (q, answer, error) -> {
            if (error != null || answer == null || answer.trim().isEmpty()) {
                reportError(error == null ? "Ashna Web không trả về câu trả lời." : error);
                return;
            }
            final String clean = sanitizeOutgoingAnswer(answer);
            if (clean.isEmpty()) {
                reportError("Ashna Web chỉ trả về phần giao diện/suy nghĩ, không có câu trả lời cuối.");
                return;
            }
            appendConversationTurn(q, clean);
            AutoMessengerService.setLastConversation(q, clean);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (sendMessage(clean)) {
                    lastSent = clean;
                    lastReplyAt = System.currentTimeMillis();
                    postDebug("Đã gửi câu trả lời qua Ashna Web Free chạy ngầm.");
                }
            });
        });
    }

    public void requestAshnaWebReply(final String question, final ReplyCallback callback) {
        if (question == null || question.trim().isEmpty() || callback == null) return;
        final String originalQuestion = question.trim();
        final String aiInstruction = buildAshnaInstruction(originalQuestion, ashnaConversationContext);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (ashnaWebBusy) {
                callback.onReply(originalQuestion, null, "AI đang xử lý lượt trước; lượt mới đã được giữ lại.");
                return;
            }
            ashnaWebBusy = true;
            ashnaWebQuestion = originalQuestion;
            ashnaSubmittedInstruction = aiInstruction;
            ashnaWebCallback = callback;
            ashnaLastCandidate = "";
            ashnaStableCandidateChecks = 0;
            ashnaBaselineBody = "";
            ensureAshnaHiddenWebView();
            if (ashnaHiddenWebView == null) {
                finishAshnaWeb(null, "Không tạo được WebView Ashna chạy ngầm.");
                return;
            }
            postDebug("Ashna Web Free: xử lý ngầm, không chuyển khỏi ứng dụng chat.");
            ashnaHiddenWebView.loadUrl("https://app.ashna.ai/chat?agent=gpt-6.1-sol");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> driveHiddenAshnaWeb(aiInstruction, 0), 1800L);
        });
    }

    /** Chỉ dẫn nội bộ: biến tin nhắn đầu vào thành câu trả lời tự nhiên trong đúng vai trò người đang trò chuyện. */
    private String buildAshnaInstruction(String question, String conversationContext) {
        String userStyle = getSharedPreferences("AutoMessenger", 0)
                .getString("active_prompt_text", "")
                .trim();

        StringBuilder b = new StringBuilder();
        b.append("Đây là cuộc trao đổi NGƯỜI VỚI NGƯỜI. Hãy đóng vai đúng người đang nhắn, không phải trợ lý tư vấn. ");
        b.append("Nhiệm vụ là đọc đúng các tin nhắn thật trong cuộc trò chuyện, hiểu người kia đang nói gì, cảm xúc gì, đang trả lời câu nào và mối quan hệ hiện tại, rồi viết đúng MỘT câu trả lời tiếp theo để gửi cho người kia. ");
        b.append("Chỉ dùng ngữ cảnh hội thoại đã lấy từ màn hình chat và tin nhắn mới nhất. Không tự bịa thêm sự kiện, tên, lịch sử hay ý định không có trong ngữ cảnh. Nếu có nhiều tin liên tiếp của người kia, coi chúng là một lượt nói và trả lời đủ ý, không trả lời từng câu riêng lẻ. ");
        b.append("PROMPT/PHONG CÁCH người dùng cung cấp chỉ là kim chỉ nam về tính cách, mục tiêu và cách nói chuyện; tuyệt đối không chép prompt thành câu trả lời và không để prompt lấn át nội dung người kia vừa nói. ");
        b.append("Ưu tiên câu trả lời giống người thật: tự nhiên, đúng giọng cuộc trò chuyện, ngắn vừa đủ, bắt đúng chi tiết vừa được nói, có cảm xúc phù hợp. Nếu đang làm quen/tán tỉnh thì chủ động, có duyên, trêu hoặc flirt nhẹ khi hợp cảnh; không sến, không vồ vập, không dùng văn mẫu. Nếu người kia chỉ nói 'ừ/haha/ok' thì đáp tự nhiên và ngắn. Nếu họ hỏi trực tiếp thì trả lời đúng câu hỏi trước rồi mới mở tiếp câu chuyện nếu cần. ");
        b.append("Không lặp lại câu người kia, không hỏi lại điều vừa được nói rõ, không hỏi dồn dập, không biến thành phỏng vấn, không tư vấn khách hàng, không giải thích suy nghĩ. ");
        b.append("Chỉ xuất đúng nội dung sẽ gửi cho người kia, không tiêu đề, không lời dẫn, không ngoặc kép, không phân tích, không Thought for a few seconds, không Thinking. Giữ đúng ngôn ngữ và độ dài tự nhiên.\n");
        if (!userStyle.isEmpty()) {
            b.append("Phong cách người dùng mong muốn: ").append(userStyle).append("\n");
        }
        if (conversationContext != null && !conversationContext.trim().isEmpty()) {
            b.append("\nĐoạn hội thoại gần đây (chỉ dùng để hiểu ngữ cảnh):\n");
            b.append(conversationContext.trim()).append("\n");
        }
        b.append("\nTin nhắn mới nhất cần trả lời (ưu tiên trả lời tin này):\n");
        b.append(question);
        return b.toString();
    }

    private String buildRecentConversationContext(AccessibilityNodeInfo root, String latestIncoming) {
        if (root == null) return "";
        List<ConversationLine> lines = new ArrayList<>();
        collectConversationLines(root, lines);
        if (lines.isEmpty()) return "";

        // Giữ đúng thứ tự dọc của các bong bóng chat; không sắp theo vị trí ngang.
        lines.sort((a, b) -> Integer.compare(a.top, b.top));

        List<String> compact = new ArrayList<>();
        String previous = "";
        for (ConversationLine line : lines) {
            String text = normalize(line.text);
            if (text.isEmpty() || text.equals(previous)) continue;
            previous = text;
            String role = line.outgoing ? "Mình" : "Người kia";
            String item = role + ": " + text;
            if (compact.isEmpty() || !compact.get(compact.size() - 1).equals(item)) compact.add(item);
        }

        int from = Math.max(0, compact.size() - 12);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < compact.size(); i++) {
            if (out.length() > 0) out.append("\n");
            out.append(compact.get(i));
        }
        String result = out.toString();
        return result.length() > 6000 ? result.substring(result.length() - 6000) : result;
    }

    private void collectConversationLines(AccessibilityNodeInfo node, List<ConversationLine> out) {
        if (node == null || out == null) return;
        String text = value(node.getText()).trim();
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
        if (node.isVisibleToUser() && !node.isEditable() && !text.isEmpty()
                && text.length() <= 1200 && cls.contains("textview")
                && !isUiText(text) && !isChatHeaderOrComposerText(text)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0 && r.top > 80
                    && r.bottom < getResources().getDisplayMetrics().heightPixels - dp(80)) {
                out.add(new ConversationLine(text, (r.left + r.right) / 2f,
                        r.top, isLikelyOutgoing(node)));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectConversationLines(node.getChild(i), out);
        }
    }

    private boolean isChatHeaderOrComposerText(String text) {
        String x = normalize(text).toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return true;
        return x.equals("tin nhắn") || x.equals("nhắn tin") || x.equals("message")
                || x.equals("type a message") || x.equals("send message")
                || x.equals("tìm kiếm") || x.equals("search") || x.equals("back")
                || x.equals("quay lại") || x.equals("online") || x.equals("đang hoạt động")
                || x.equals("đã xem") || x.equals("seen") || x.equals("đang nhập...")
                || x.equals("typing...");
    }

    private static final class ConversationLine {
        final String text;
        final float centerX;
        final int top;
        final boolean outgoing;
        ConversationLine(String text, float centerX, int top, boolean outgoing) {
            this.text = text;
            this.centerX = centerX;
            this.top = top;
            this.outgoing = outgoing;
        }
    }

    /**
     * Creates Ashna WebView from the accessibility service itself.
     *
     * Important: TYPE_ACCESSIBILITY_OVERLAY is an accessibility-service window
     * type. Do NOT create a TYPE_ACCESSIBILITY_OVERLAY window context on Android
     * 11+; createWindowContext() is intended for supported application window
     * types and can fail before WebView is even constructed. The service already
     * owns the accessibility-overlay permission, so its own Context/WindowManager
     * is the correct host.
     *
     * The WebView stays attached to this service while Messenger is foreground,
     * but is made 1% transparent and non-touchable during normal operation.
     * Login temporarily restores visibility/touch.
     */
    private void ensureAshnaHiddenWebView() {
        if (ashnaHiddenWebView != null) return;

        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(this::ensureAshnaHiddenWebView);
            return;
        }

        try {
            // WebView data is not available during Android Direct Boot.
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                android.os.UserManager um =
                        (android.os.UserManager) getSystemService(android.content.Context.USER_SERVICE);
                if (um != null && !um.isUserUnlocked()) {
                    postDebug("Ashna WebView tạm hoãn: thiết bị chưa mở khóa.");
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(this::ensureAshnaHiddenWebView, 3000L);
                    return;
                }
            }

            if (android.webkit.WebView.getCurrentWebViewPackage() == null) {
                postDebug("LỖI Ashna WebView: Android System WebView/Chrome chưa sẵn sàng.");
                return;
            }

            ashnaWebWindowManager = (android.view.WindowManager)
                    getSystemService(WINDOW_SERVICE);
            if (ashnaWebWindowManager == null) {
                postDebug("LỖI Ashna WebView: không lấy được WindowManager của Trợ năng.");
                return;
            }

            android.webkit.WebView.setWebContentsDebuggingEnabled(false);
            android.content.Context hostContext = getApplicationContext();
            ashnaHiddenWebView = new android.webkit.WebView(hostContext);

            android.webkit.WebSettings s = ashnaHiddenWebView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadsImagesAutomatically(true);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setSupportMultipleWindows(false);
            s.setMediaPlaybackRequiresUserGesture(true);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }

            android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(ashnaHiddenWebView, true);

            ashnaHiddenWebView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            ashnaHiddenWebView.setAlpha(0.01f);
            ashnaHiddenWebView.setVisibility(android.view.View.VISIBLE);
            ashnaHiddenWebView.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
            ashnaHiddenWebView.setFocusable(true);
            ashnaHiddenWebView.setFocusableInTouchMode(true);

            ashnaHiddenWebView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                    try { android.webkit.CookieManager.getInstance().flush(); }
                    catch (Exception ignored) {}
                    postDebug("Ashna Web: trang đã tải.");
                }

                @Override public void onReceivedError(android.webkit.WebView view,
                        int errorCode, String description, String failingUrl) {
                    postDebug("LỖI Ashna Web: " + shortError(description));
                }
            });

            int d = Math.max(1, (int) getResources().getDisplayMetrics().density);
            // Keep a real bottom margin so Android's navigation / Recent Apps / Home
            // gestures and buttons are never covered by the accessibility overlay.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int bottomGap = Math.max(72 * d, screenH / 12);
            int webW = Math.max(1, screenW - 24 * d);
            int webH = Math.max(1, screenH - bottomGap - 24 * d);

            ashnaWebWindowParams = new android.view.WindowManager.LayoutParams(
                    webW,
                    webH,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            ashnaWebWindowParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            ashnaWebWindowParams.y = 12 * d;

            ashnaWebWindowManager.addView(ashnaHiddenWebView, ashnaWebWindowParams);
            postDebug("Ashna WebView: đã tạo engine chạy ngầm.");
        } catch (android.view.WindowManager.BadTokenException e) {
            destroyAshnaWebView();
            postDebug("LỖI Ashna WebView: Accessibility Overlay bị từ chối. Hãy tắt/bật lại Trợ năng AutoMessenger.");
        } catch (android.view.WindowManager.InvalidDisplayException e) {
            destroyAshnaWebView();
            postDebug("LỖI Ashna WebView: màn hình hiện tại chưa sẵn sàng cho Accessibility Overlay.");
        } catch (Throwable e) {
            destroyAshnaWebView();
            postDebug("LỖI tạo Ashna WebView: "
                    + e.getClass().getSimpleName() + ": " + shortError(e.getMessage()));
        }
    }

    private void destroyAshnaWebView() {
        if (ashnaHiddenWebView != null) {
            try {
                if (ashnaWebWindowManager != null) {
                    ashnaWebWindowManager.removeViewImmediate(ashnaHiddenWebView);
                }
            } catch (Exception ignored) {}
            try { ashnaHiddenWebView.stopLoading(); } catch (Exception ignored) {}
            try { ashnaHiddenWebView.destroy(); } catch (Exception ignored) {}
        }
        ashnaHiddenWebView = null;
        ashnaWebWindowParams = null;
        ashnaWebWindowManager = null;
    }

    public void openAshnaLogin() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                // Login is a normal Activity now. It is not a full-screen accessibility
                // overlay, so Android can freely switch between chat apps, Home and
                // Recents while the user is logging in/confirming the session.
                android.content.Intent intent =
                        new android.content.Intent(this, AshnaLoginActivity.class);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                postDebug("Ashna Web: mở màn đăng nhập riêng, không khóa chuyển ứng dụng.");
            } catch (Throwable e) {
                reportError("Không mở được màn đăng nhập Ashna: " + shortError(e.getMessage()));
            }
        });
    }

    private void showAshnaLoginCloseButton() {
        if (ashnaWebWindowManager == null || ashnaLoginCloseButton != null) return;
        try {
            final int d = Math.max(1, (int) getResources().getDisplayMetrics().density);
            ashnaLoginCloseButton = new android.widget.Button(getApplicationContext());
            ashnaLoginCloseButton.setText("✕");
            ashnaLoginCloseButton.setTextSize(19f);
            ashnaLoginCloseButton.setAllCaps(false);
            ashnaLoginCloseButton.setMinWidth(0);
            ashnaLoginCloseButton.setMinHeight(0);
            ashnaLoginCloseButton.setPadding(0, 0, 0, 0);
            ashnaLoginCloseButton.setGravity(android.view.Gravity.CENTER);
            ashnaLoginCloseButton.setBackgroundColor(0xEE222222);
            ashnaLoginCloseButton.setTextColor(android.graphics.Color.WHITE);
            ashnaLoginCloseButton.setContentDescription("Đóng đăng nhập Ashna");
            ashnaLoginCloseButton.setOnClickListener(v -> hideAshnaWeb());

            android.view.WindowManager.LayoutParams p = new android.view.WindowManager.LayoutParams(
                    56 * d, 56 * d,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    android.graphics.PixelFormat.TRANSLUCENT);
            p.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            p.x = 10 * d;
            p.y = 10 * d;
            ashnaWebWindowManager.addView(ashnaLoginCloseButton, p);
        } catch (Throwable e) {
            ashnaLoginCloseButton = null;
            postDebug("LỖI tạo nút Đóng Ashna: " + shortError(e.getMessage()));
        }
    }

    private void hideAshnaWeb() {
        if (ashnaLoginCloseButton != null && ashnaWebWindowManager != null) {
            try { ashnaWebWindowManager.removeViewImmediate(ashnaLoginCloseButton); }
            catch (Exception ignored) {}
            ashnaLoginCloseButton = null;
        }
        if (ashnaHiddenWebView == null || ashnaWebWindowManager == null) return;
        ashnaHiddenWebView.setAlpha(0.01f);
        ashnaWebWindowParams.flags =
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        try { ashnaWebWindowManager.updateViewLayout(ashnaHiddenWebView, ashnaWebWindowParams); }
        catch (Exception ignored) {}
    }

    private void monitorAshnaLogin(final int attempt) {
        if (ashnaHiddenWebView == null) return;
        if (attempt > 600) {
            postDebug("Ashna Web: vẫn đang chờ đăng nhập. WebView được giữ nguyên.");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> monitorAshnaLogin(601), 5000L);
            return;
        }
        String js = "javascript:(function(){"
                + "var t=document.body?document.body.innerText:'';"
                + "var u=location.href||'';"
                + "var els=[].slice.call(document.querySelectorAll('textarea,input,[contenteditable=\"true\"]'));"
                + "var visible=function(e){if(!e)return false;var r=e.getBoundingClientRect();"
                + "var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!='none'&&s.visibility!='hidden'};"
                + "var composer=els.some(function(e){return visible(e)&&!e.disabled&&e.type!='hidden'});"
                + "var login=/sign in|log in|continue with google|continue with email|đăng nhập|login/i.test(t);"
                + "var auth=/log out|sign out|đăng xuất|my account|profile/i.test(t);"
                + "var chat=/\\/chat/i.test(u)||/new chat/i.test(t);"
                + "return JSON.stringify({login:login,auth:auth,composer:composer,chat:chat,url:u});})();";
        ashnaHiddenWebView.evaluateJavascript(js, result -> {
            String state = decodeJsString(result);
            boolean confirmed = false;
            try {
                org.json.JSONObject o = new org.json.JSONObject(state);
                boolean login = o.optBoolean("login", true);
                boolean composer = o.optBoolean("composer", false);
                boolean chat = o.optBoolean("chat", false);
                String url = o.optString("url", "");
                confirmed = composer && chat && !login && url.contains("/chat");
            } catch (Exception ignored) {}

            if (confirmed) ashnaLoginReadyChecks++;
            else ashnaLoginReadyChecks = 0;

            if (ashnaLoginReadyChecks >= 3) {
                try { android.webkit.CookieManager.getInstance().flush(); }
                catch (Exception ignored) {}
                getSharedPreferences("AutoMessenger", 0)
                        .edit().putBoolean("ashna_login_confirmed", true).apply();
                hideAshnaWeb();
                ashnaHiddenWebView.setFocusable(false);
                ashnaHiddenWebView.setFocusableInTouchMode(false);
                postDebug("Ashna Web: ĐÃ xác nhận đăng nhập. Đã lưu phiên và chuyển chạy ngầm.");
                return;
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> monitorAshnaLogin(attempt + 1), 1000L);
        });
    }

    private void driveHiddenAshnaWeb(final String question, final int attempt) {
        if (!ashnaWebBusy || ashnaHiddenWebView == null) return;
        if (attempt > 20) {
            finishAshnaWeb(null,
                    "Ashna Web chưa sẵn sàng sau khoảng 10 giây. Hãy kiểm tra phiên đăng nhập hoặc thử lại.");
            return;
        }
        String js = "javascript:(function(){"
                + "var vis=function(e){if(!e)return false;var r=e.getBoundingClientRect();"
                + "var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'};"
                + "var els=[].slice.call(document.querySelectorAll('textarea,input,[contenteditable=\"true\"]'));"
                + "els=els.filter(function(e){return vis(e)&&!e.disabled&&e.type!=='hidden'});"
                + "var body=document.body?document.body.innerText:'';"
                + "var login=/sign in|log in|đăng nhập|login/i.test(body);"
                + "var modelWanted=/GPT\\s*6\\.1\\s*Sol/i;"
                + "var modelOld=/GPT\\s*6\\s*Sol/i;"
                + "var buttons=[].slice.call(document.querySelectorAll('button,[role=button]')).filter(vis);"
                + "var oldBtn=buttons.find(function(x){var z=(x.innerText||x.getAttribute('aria-label')||'');return modelOld.test(z)&&!modelWanted.test(z);});"
                + "if(oldBtn&&!modelWanted.test(body)){try{oldBtn.click();}catch(e){}}"
                + "var choices=[].slice.call(document.querySelectorAll('button,[role=button],[role=menuitem],li')).filter(vis);"
                + "var wanted=choices.find(function(x){var z=(x.innerText||x.getAttribute('aria-label')||'');return modelWanted.test(z);});"
                + "if(wanted){try{wanted.click();}catch(e){}}"
                + "login=/sign in|log in|đăng nhập|login/i.test(document.body?document.body.innerText:'');"
                + "var input=els.sort(function(a,b){return b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom})[0];"
                + "if(login&&!input)return 'LOGIN';if(!input)return 'NO_INPUT';"
                + "var q=" + jsQuote(question) + ";"
                + "var baseline=document.body?document.body.innerText:'';"
                + "try{input.focus();if(input.isContentEditable){input.innerText=q;}else{"
                + "var proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(input,q);else input.value=q;}"
                + "input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));"
                + "var bs=[].slice.call(document.querySelectorAll('button,[role=\"button\"],input[type=\"submit\"]'));"
                + "var b=bs.filter(function(x){var z=((x.innerText||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(x.getAttribute('data-testid')||'')).toLowerCase();"
                + "return vis(x)&&/send|gửi|submit|arrow.?up|paper.?plane/.test(z)})[0];"
                + "if(b){b.click();return JSON.stringify({state:'SENT',baseline:baseline});}"
                + "input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));"
                + "input.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));"
                + "return JSON.stringify({state:'ENTER',baseline:baseline});}catch(e){return 'ERR:'+e.message;}})();";

        ashnaHiddenWebView.evaluateJavascript(js, result -> {
            String state = decodeJsString(result);
            if ("LOGIN".equals(state)) {
                finishAshnaWeb(null, "Ashna Web chưa đăng nhập trong AutoMessenger. Bấm 'Đăng nhập Ashna' trong bong bóng AI và đăng nhập 1 lần.");
                return;
            }
            if (state.startsWith("{")) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(state);
                    String status = o.optString("state", "");
                    if ("SENT".equals(status) || "ENTER".equals(status)) {
                        ashnaBaselineBody = o.optString("baseline", "");
                        postDebug("Ashna Web: đã gửi ngầm, chờ câu trả lời...");
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                () -> pollHiddenAshnaAnswer(question, 0), 1800L);
                        return;
                    }
                } catch (Exception ignored) {}
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> driveHiddenAshnaWeb(question, attempt + 1), 500L);
        });
    }

    private void pollHiddenAshnaAnswer(final String question, final int attempt) {
        if (!ashnaWebBusy || ashnaHiddenWebView == null) return;
        if (attempt > 28) {
            finishAshnaWeb(null, "Ashna Web không lấy được câu trả lời cuối sau khoảng 22 giây.");
            return;
        }
        String js = "javascript:(function(){"
                + "var q=" + jsQuote(question) + ";"
                + "var body=document.body?document.body.innerText:'';"
                + "var submitted=" + jsQuote(ashnaSubmittedInstruction) + ";"
                + "var baseline=" + jsQuote(ashnaBaselineBody) + ";"
                + "var oldLines={};baseline.split(/\\n+/).forEach(function(x){x=x.trim();if(x)oldLines[x]=1;});"
                + "var bad=/^(send|gửi|new chat|chat|settings|sign in|log in|copy|regenerate|stop|retry|model|agent|input|thinking|thought|generating|show more|show less|gpt\\s*6(?:\\.1)?\\s*sol|ashnaai(?:\\s+can\\s+make\\s+mistakes)?|how can i help you today\\?)$/i;"
                + "var cand=[];"
                + "var nodes=[].slice.call(document.querySelectorAll('[data-message-author-role=\"assistant\"],[data-role=\"assistant\"],[role=\"article\"],[data-message-id],[data-message],[data-testid*=\"message\"],[class*=\"message\"],[class*=\"Message\"]'));"
                + "nodes.forEach(function(n){var t=(n.innerText||n.textContent||'').trim();var low=t.toLowerCase();"
                + "if(!t||t===q||oldLines[t]||bad.test(t)||t.length<2||t.length>5000)return;"
                + "if(low.indexOf('bạn đang đóng vai người nhận tin nhắn')>=0||low.indexOf('tin nhắn của người kia')>=0||low.indexOf('không phân tích, không giải thích')>=0||low.indexOf('chỉ xuất đúng nội dung tin nhắn')>=0)return;"
                + "cand.push(t);});"
                + "if(!cand.length){var lines=body.split(/\\n+/).map(function(x){return x.trim()}).filter(Boolean);"
                + "var qi=-1;"
                + "for(var i=lines.length-1;i>=0;i--){if(submitted&&lines[i]===submitted){qi=i;break;}}"
                + "if(qi<0){for(var k=lines.length-1;k>=0;k--){if(lines[k]===q){qi=k;break;}}}"
                + "if(qi>=0){for(var j=qi+1;j<lines.length;j++){var t=lines[j],low=t.toLowerCase();"
                + "if(t===q||oldLines[t]||bad.test(t)||t.length<2||t.length>5000)continue;"
                + "if(low.indexOf('bạn đang đóng vai người nhận tin nhắn')>=0||low.indexOf('tin nhắn của người kia')>=0||low.indexOf('chỉ xuất đúng nội dung tin nhắn')>=0)continue;"
                + "cand.push(t);}}}"
                + "return JSON.stringify({candidates:cand.slice(-6)});})();";

        ashnaHiddenWebView.evaluateJavascript(js, result -> {
            String answer = extractHiddenAnswer(decodeJsString(result), question);
            if (answer != null && answer.trim().length() >= 2) {
                String stable = answer.trim();
                if (isAshnaTransientText(stable)) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            () -> pollHiddenAshnaAnswer(question, attempt + 1), 700L);
                    return;
                }
                if (stable.equals(ashnaLastCandidate)) {
                    ashnaStableCandidateChecks++;
                } else {
                    ashnaLastCandidate = stable;
                    ashnaStableCandidateChecks = 1;
                }
                if (ashnaStableCandidateChecks >= 2) {
                    finishAshnaWeb(stable, null);
                    return;
                }
            }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> pollHiddenAshnaAnswer(question, attempt + 1), 700L);
        });
    }

    private boolean isAshnaTransientText(String text) {
        if (text == null) return true;
        String x = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return x.equals("thinking")
                || x.equals("thought")
                || x.equals("thinking...")
                || x.equals("thinking…")
                || x.equals("generating")
                || x.equals("generating...");
    }

    private String extractHiddenAnswer(String json, String question) {
        // Loại disclaimer của Ashna khỏi mọi dạng câu trả lời, kể cả khi nó dính cùng một dòng.
        if (json == null || json.trim().isEmpty()) return null;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONArray a = o.optJSONArray("candidates");
            if (a != null) {
                for (int i = a.length() - 1; i >= 0; i--) {
                    String x = a.optString(i, "").trim();
                    x = cleanAshnaAnswer(x, question);
                    if (x != null && x.length() >= 2) return x;
                }
            }
            String body = o.optString("body", "");
            int p = body.lastIndexOf(question);
            if (p >= 0) {
                String tail = body.substring(p + question.length()).trim();
                String[] lines = tail.split("\\n+");                StringBuilder b = new StringBuilder();
                for (String line : lines) {
                    String x = cleanAshnaAnswer(line.trim(), question);
                    if (x == null || x.isEmpty()) continue;
                    if (b.length() > 0) b.append("\\n");
                    b.append(x);
                    if (b.length() > 3500) break;
                }
                if (b.length() >= 2) return b.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String removeAshnaDisclaimer(String text) {
        if (text == null) return "";
        String x = text.trim();
        x = x.replaceAll("(?i)\\s*AshnaAI\\s+can\\s+make\\s+mistakes\\.?\\s*$", "");
        x = x.replaceAll("(?i)\\s*AshnaAI\\s+can\\s+make\\s+mistakes\\.?", "");
        return x.trim();
    }

    private boolean isAshnaModelText(String text) {
        if (text == null) return true;
        String x = text.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\u00a0\\t\\r\\n]+", " ")
                .replaceAll("\\s+", " ");
        return x.equals("gpt 6 sol")
                || x.equals("gpt-6-sol")
                || x.equals("gpt6 sol")
                || x.equals("model: gpt 6 sol")
                || x.equals("model: gpt-6-sol")
                || x.equals("agent: gpt 6 sol")
                || x.equals("agent: gpt-6-sol")
                || x.matches("^gpt\\s*[-]?\\s*6\\s*[-]?\\s*sol$");
    }

    private String cleanAshnaAnswer(String text, String question) {
        if (text == null) return null;
        String[] lines = removeAshnaDisclaimer(text).split("\\n+");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String x = removeAshnaDisclaimer(line).trim();
            if (x.isEmpty() || x.equals(question) || isWebUiText(x) || isAshnaModelText(x) || isReasoningLeak(x)) continue;
            if (out.length() > 0) out.append("\\n");
            out.append(x);
            if (out.length() > 3500) break;
        }
        return out.length() == 0 ? null : out.toString().trim();
    }

    private boolean isReasoningLeak(String text) {
        if (text == null) return true;
        String x = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return x.equals("thought for a few seconds") || x.startsWith("thought for ")
                || x.equals("thought") || x.equals("thinking") || x.startsWith("thinking ")
                || x.startsWith("thinking...") || x.startsWith("thinking…")
                || x.equals("reasoning") || x.startsWith("reasoning:")
                || x.equals("analysis") || x.startsWith("analysis:")
                || x.startsWith("chain of thought") || x.contains("here is my reasoning")
                || x.startsWith("i'll think") || x.startsWith("let me think");
    }

    private String sanitizeOutgoingAnswer(String answer) {
        if (answer == null) return "";
        String cleaned = cleanAshnaAnswer(answer.trim(), ashnaWebQuestion);
        return cleaned == null ? "" : cleaned.trim();
    }

    private void appendConversationTurn(String incoming, String answer) {
        String in = normalize(incoming), out = normalize(answer);
        if (in.isEmpty() || out.isEmpty()) return;
        StringBuilder sb = new StringBuilder(ashnaConversationContext == null ? "" : ashnaConversationContext.trim());
        if (sb.length() > 0) sb.append("\\n");
        sb.append("Người kia: ").append(in).append("\\nMình: ").append(out);
        String result = sb.toString();
        ashnaConversationContext = result.length() > 8000 ? result.substring(result.length() - 8000) : result;
    }

    private String jsQuote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\\\/") + "\"";
    }

    private String decodeJsString(String value) {
        if (value == null) return "";
        try {
            Object parsed = new org.json.JSONTokener(value).nextValue();
            return parsed == null ? "" : parsed.toString();
        } catch (Exception ignored) {}
        return value;
    }

    private void finishAshnaWeb(final String answer, final String error) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            ReplyCallback cb = ashnaWebCallback;
            ashnaWebCallback = null;
            ashnaWebBusy = false;
            replying = false;
            if (cb != null) cb.onReply(ashnaWebQuestion, answer, error);
            final String deferred;
            synchronized (pendingLock) {
                deferred = deferredIncomingBatches.isEmpty() ? "" : joinPendingMessages(deferredIncomingBatches);
                deferredIncomingBatches.clear();
            }
            if (!deferred.isEmpty() && getSharedPreferences("AutoMessenger", 0).getBoolean("auto", false)) {
                synchronized (pendingLock) {
                    pendingMessages.add(deferred);
                    if (pendingFlush != null) pendingFlush.cancel(false);
                    pendingFlush = debounceScheduler.schedule(this::flushPendingMessages, 3000L, TimeUnit.MILLISECONDS);
                }
                postDebug("Đã giữ tin mới trong lúc AI trả lời; tiếp tục sau 3s im lặng.");
            }
        });
    }

    private boolean isWebUiText(String text) {
        String x = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("new chat") || x.equals("chat")
                || x.equals("settings") || x.equals("sign in") || x.equals("log in")
                || x.equals("try again") || x.equals("copy") || x.equals("regenerate")
                || x.equals("ashnaai can make mistakes") || x.equals("input") || x.equals("how can i help you today?")
                || x.equals("stop") || x.equals("show more") || x.equals("show less")
                || x.startsWith("model:") || x.startsWith("agent:") || isReasoningLeak(x);
    }

    private boolean sendMessage(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            safeRecycle(root);
            reportError("Không lấy được màn hình chat hiện tại.");
            return false;
        }
        String pkg = root.getPackageName().toString();
        if (!isSupportedChatPackage(pkg)) {
            safeRecycle(root);
            reportError("Ứng dụng hiện tại không phải ứng dụng chat được hỗ trợ: " + pkg);
            return false;
        }

        // The target app is captured when the incoming message arrives. If the
        // user switches to another chat app while Ashna is thinking, never send
        // the reply into the wrong conversation. The next accessibility event
        // will capture the new app normally.
        if (ashnaWebTargetPackage != null && !ashnaWebTargetPackage.isEmpty()
                && !ashnaWebTargetPackage.equals(pkg)) {
            safeRecycle(root);
            postDebug("Đã phát hiện bạn chuyển ứng dụng. Không gửi nhầm câu trả lời sang " + pkg + ".");
            reportError("Bạn vừa chuyển ứng dụng. Hãy quay lại cuộc trò chuyện ban đầu để gửi câu trả lời.");
            return false;
        }

        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) {
            safeRecycle(root);
            reportError("Không tìm thấy ô nhập tin nhắn.");
            return false;
        }
        replaceLastInput(input);
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);

        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!set) {
            safeRecycle(root);
            reportError("Không thể điền câu trả lời vào ô nhập.");
            return false;
        }

        boolean verified = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            AccessibilityNodeInfo verifyRoot = getRootInActiveWindow();
            AccessibilityNodeInfo verifyInput = findEditable(verifyRoot);
            if (verifyInput != null
                    && normalize(value(verifyInput.getText())).equals(normalize(text))) {
                verified = true;
                safeRecycle(verifyInput);
                safeRecycle(verifyRoot);
                break;
            }
            safeRecycle(verifyInput);
            safeRecycle(verifyRoot);
            try { Thread.sleep(80L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!verified) {
            safeRecycle(root);
            reportError("Đã điền nhưng không xác nhận được nội dung trong ô nhập.");
            return false;
        }

        AccessibilityNodeInfo freshRoot = getRootInActiveWindow();
        AccessibilityNodeInfo send = findSendButton(freshRoot);
        boolean clicked = send != null && clickNodeOrParent(send);
        safeRecycle(send);
        safeRecycle(freshRoot);
        safeRecycle(root);

        if (!clicked) {
            AccessibilityNodeInfo currentInput = getRootInActiveWindow();
            AccessibilityNodeInfo edit = findEditable(currentInput);
            if (edit != null) {
                edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                clicked = edit.performAction(0x00400000);
            }
            safeRecycle(edit);
            safeRecycle(currentInput);
        }
        if (!clicked) reportError("Không tìm thấy hoặc không bấm được nút Gửi.");
        return clicked;
    }

    private void reportError(String message) {
        String msg = shortError(message == null ? "Lỗi không xác định." : message);
        postDebug("LỖI: " + msg);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(this, "AutoMessenger: " + msg,
                        android.widget.Toast.LENGTH_LONG).show());
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isVisibleToUser()) {
            String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
            String all = (value(node.getText()) + " " + value(node.getContentDescription())
                    + " " + value(node.getHintText())).toLowerCase(Locale.ROOT);
            if (node.isEditable() || cls.contains("edittext")
                    || all.contains("type a message") || all.contains("write a message")
                    || all.contains("nhập tin nhắn") || all.contains("tin nhắn")
                    || all.equals("aa") || all.endsWith(" aa")) {
                if (node.isEditable() || cls.contains("edittext")) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findEditable(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String all = (value(node.getText()) + " " + value(node.getContentDescription()) + " "
                + value(node.getHintText()) + " " + value(node.getViewIdResourceName()))
                .toLowerCase(Locale.ROOT);
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);

        if (node.isVisibleToUser()
                && (all.contains("send") || all.contains("gửi") || all.contains("gui")
                || id.contains("message_send") || id.endsWith("_send")
                || (cls.contains("imagebutton") && id.contains("send")))) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findSendButton(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int i = 0; i < 6 && current != null; i++) {
            if (current.isVisibleToUser() && current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            current = current.getParent();
        }
        return false;
    }

    private String extractLatestMessage(AccessibilityNodeInfo root, AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            String candidate = messageTextFromNode(source);
            safeRecycle(source);
            if (candidate != null) return candidate;
        }

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        collectMessageNodes(root, candidates);
        String best = "";
        for (AccessibilityNodeInfo n : candidates) {
            String candidate = messageTextFromNode(n);
            if (candidate != null && !candidate.equals(lastSent)
                    && !candidate.equals(lastIncoming)) best = candidate;
            safeRecycle(n);
        }
        return best;
    }

    private void collectMessageNodes(AccessibilityNodeInfo node,
                                      List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        String text = value(node.getText()).trim();
        if (node.isVisibleToUser() && !text.isEmpty() && !node.isEditable()
                && isMessageLikeNode(node) && !isLikelyOutgoing(node)) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectMessageNodes(node.getChild(i), out);
    }

    private String messageTextFromNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || node.isEditable()) return null;
        String text = value(node.getText()).trim();
        if (text.isEmpty() || text.length() > 4000 || isUiText(text)) return null;
        if (!isMessageLikeNode(node) || isLikelyOutgoing(node)) return null;
        return text;
    }

    private boolean isMessageLikeNode(AccessibilityNodeInfo node) {
        String id = value(node.getViewIdResourceName()).toLowerCase(Locale.ROOT);
        String cls = value(node.getClassName()).toLowerCase(Locale.ROOT);
        String desc = value(node.getContentDescription()).toLowerCase(Locale.ROOT);
        if (id.contains("message") || id.contains("messenger") || desc.contains("message")) return true;
        if (cls.contains("textview")) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            return r.top > 120 && r.bottom > r.top;
        }
        return false;
    }

    private boolean isLikelyOutgoing(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.right <= r.left) return false;
        int center = (r.left + r.right) / 2;
        return center > getResources().getDisplayMetrics().widthPixels * 0.58f;
    }

    private boolean isUiText(String s) {
        String x = s.toLowerCase(Locale.ROOT).trim();
        return x.equals("send") || x.equals("gửi") || x.equals("gui")                || x.equals("message") || x.equals("messenger") || x.equals("aa")
                || x.equals("more") || x.equals("thêm")
                || x.contains("type a message") || x.contains("write a message")
                || x.contains("nhập tin nhắn") || x.contains("tin nhắn");
    }

    private void replaceLastInput(AccessibilityNodeInfo input) {
        if (lastInput != null && lastInput != input) safeRecycle(lastInput);
        lastInput = input;
    }

    private String value(CharSequence s) { return s == null ? "" : s.toString(); }

    private String normalize(String s) {
        if (s == null) return "";
        return s.replace("\u00a0", " ").trim().replaceAll("\\s+", " ");
    }

    private String shortError(String message) {
        String x = message == null ? "Lỗi không xác định" : message.trim();
        return x.length() > 180 ? x.substring(0, 180) : x;
    }

    private void safeRecycle(AccessibilityNodeInfo node) {
        if (node != null) try { node.recycle(); } catch (Exception ignored) {}
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        instance = null;
        try { hideAshnaWeb(); } catch (Exception ignored) {}
        try {
            if (ashnaWebWindowManager != null) {
                if (ashnaLoginCloseButton != null) {
                    try { ashnaWebWindowManager.removeViewImmediate(ashnaLoginCloseButton); } catch (Exception ignored) {}
                }
                if (ashnaHiddenWebView != null) {
                    try { ashnaWebWindowManager.removeViewImmediate(ashnaHiddenWebView); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        worker.shutdownNow();
        debounceScheduler.shutdownNow();
        safeRecycle(lastInput);
        lastInput = null;
        if (ashnaLoginCloseButton != null && ashnaWebWindowManager != null) {
            try { ashnaWebWindowManager.removeViewImmediate(ashnaLoginCloseButton); }
            catch (Exception ignored) {}
            ashnaLoginCloseButton = null;
        }
        if (ashnaHiddenWebView != null) {
            try {
                if (ashnaWebWindowManager != null) ashnaWebWindowManager.removeView(ashnaHiddenWebView);
            } catch (Exception ignored) {}
            try { ashnaHiddenWebView.stopLoading(); } catch (Exception ignored) {}
            try { ashnaHiddenWebView.destroy(); } catch (Exception ignored) {}
            ashnaHiddenWebView = null;
        }
        ashnaWebCallback = null;
        super.onDestroy();
    }



    public interface ReplyCallback {
        void onReply(String question, String answer, String error);
    }

    /** Manual bubble request: same hidden Ashna Web Free engine as auto-replies. */
    public void generateManualReply(final String question, final ReplyCallback callback) {
        if (callback == null || question == null || question.trim().isEmpty()) return;
        requestAshnaWebReply(question.trim(), callback);
    }

    public boolean putTextInMessenger(String text) { return sendMessage(text); }

    public boolean putTextInComposerOnly(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isSupportedChatPackage(value(root.getPackageName()))) {
            safeRecycle(root);
            return false;
        }
        AccessibilityNodeInfo input = findEditable(root);
        if (input == null) {
            safeRecycle(root);
            return false;
        }
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (ok) replaceLastInput(input); else safeRecycle(input);
        safeRecycle(root);
        return ok;
    }
}