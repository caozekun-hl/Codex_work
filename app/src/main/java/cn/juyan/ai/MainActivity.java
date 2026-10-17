package cn.juyan.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import cn.juyan.ai.AppState.Conversation;
import cn.juyan.ai.AppState.Message;
import cn.juyan.ai.AppState.Provider;

public final class MainActivity extends Activity {
    private static final int BG = 0xFFF6F8F6, WHITE = 0xFFFFFFFF, INK = 0xFF1C302A,
            MUTED = 0xFF6B7B73, TEAL = 0xFF167565, PALE = 0xFFE5F0E9,
            LINE = 0xFFDDE6DF, DANGER = 0xFFAC473D;
    private static final int IMPORT_CONFIG = 10, EXPORT_CONFIG = 11, EXPORT_CHAT = 12;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private AppState state;
    private SecureStore store;
    private LinearLayout root, page, header, bottom;
    private FrameLayout content;
    private ScrollView messagesScroll;
    private LinearLayout messagesBox;
    private EditText composer;
    private TextView modelLabel, streamingText, streamingReasoning, streamingStatus;
    private View sendButton;
    private int tab = 0;
    private String draft = "", selectedProvider = "", selectedModel = "";
    private AiClient.Call activeCall, testCall;
    private Message activeMessage;
    private Conversation activeConversation;
    private boolean destroyed, storageBlocked;
    private String exportPayload = "";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SecureStore(this);
        try { state = store.load(); }
        catch (Exception e) { state = new AppState(); storageBlocked = true; }
        if (savedInstanceState != null) draft = savedInstanceState.getString("draft", "");
        applyPrivacy();
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = col(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        header = col(); root.addView(header);
        content = new FrameLayout(this); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = row(); root.addView(bottom);
        render();
        if (storageBlocked) new AlertDialog.Builder(this).setTitle("本地数据无法解密")
                .setMessage("可能是系统密钥发生变化或数据损坏。为避免覆盖，应用已停止保存。原数据仍保留，请先保留设备数据再排查；也可以在设置中明确清空本地数据后重新配置。")
                .setPositiveButton("知道了", null).show();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draft", composer == null ? draft : composer.getText().toString());
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() { super.onStop(); persist(); }
    @Override protected void onDestroy() {
        destroyed = true;
        if (activeMessage != null && activeMessage.status.equals("streaming")) {
            activeMessage.status = "interrupted"; activeMessage.error = "生成已中断，可重新生成。";
        }
        if (activeCall != null) activeCall.cancel();
        if (testCall != null) testCall.cancel();
        persist(); executor.shutdown(); super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (tab != 0) { navigate(0); return; }
        super.onBackPressed();
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout col() { LinearLayout v = new LinearLayout(this); v.setOrientation(1); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(0); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(dp(4), 1); return t;
    }
    private GradientDrawable bg(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke); return d;
    }
    private void gap(LinearLayout parent, int height) { View v = new View(this); parent.addView(v, new LinearLayout.LayoutParams(1, dp(height))); }
    private void weight(LinearLayout parent) { parent.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1)); }
    private TextView button(String title, boolean primary, Runnable click) {
        TextView b = text(title, 15, primary ? WHITE : TEAL, true); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(17), dp(14), dp(17), dp(14));
        b.setBackground(bg(primary ? TEAL : PALE, 14, 0)); b.setOnClickListener(v -> click.run()); return b;
    }
    private View iconButton(String icon, String label, Runnable click) {
        Icon v = new Icon(icon, INK); v.setContentDescription(label); v.setBackground(bg(WHITE, 14, LINE));
        v.setPadding(dp(13), dp(13), dp(13), dp(13));
        v.setOnClickListener(w -> click.run()); v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44))); return v;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = col(); c.setPadding(dp(18), dp(18), dp(18), dp(18)); c.setBackground(bg(WHITE, 20, LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12); parent.addView(c, lp); return c;
    }
    private ScrollView scrolling(LinearLayout child) {
        ScrollView s = new ScrollView(this); s.setFillViewport(true); s.setClipToPadding(false); s.addView(child);
        s.setVerticalScrollBarEnabled(false); return s;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private void alert(String title, String msg) { new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("知道了", null).show(); }

    private boolean persist() {
        if (storageBlocked) return false;
        try { store.save(state); return true; }
        catch (Exception e) { if (!destroyed) toast("本地保存失败，请检查手机剩余空间。当前内容仅保留在内存。"); return false; }
    }
    private void applyPrivacy() {
        if (state.screenPrivacy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void captureDraft() { if (composer != null) draft = composer.getText().toString(); }
    private void navigate(int next) {
        captureDraft(); tab = next;
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        render();
    }
    private void render() {
        header.removeAllViews(); content.removeAllViews(); bottom.removeAllViews(); composer = null;
        streamingText = null; streamingReasoning = null; streamingStatus = null;
        header.setPadding(dp(22), dp(18), dp(22), dp(15));
        LinearLayout top = row(); header.addView(top);
        LinearLayout brand = col();
        brand.addView(text("JUYAN  /  PRIVATE AI", 10, MUTED, true)); gap(brand, 6);
        brand.addView(text(tab == 0 ? "聚言 AI" : tab == 1 ? "我的接口" : "偏好设置", 27, INK, true));
        top.addView(brand); weight(top);
        if (tab == 0) {
            top.addView(iconButton("history", "查看历史对话", this::showHistory));
            View space = new View(this); top.addView(space, new LinearLayout.LayoutParams(dp(8), 1));
            top.addView(iconButton("plus", "新建对话", () -> newChat(true)));
        } else top.addView(iconButton(tab == 1 ? "plus" : "info", tab == 1 ? "添加 API" : "关于聚言 AI",
                tab == 1 ? this::showTemplates : this::showAbout));
        page = col(); content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        if (tab == 0) renderChat(); else if (tab == 1) renderProviders(); else renderSettings();
        renderNav();
    }

    private void renderNav() {
        bottom.setPadding(dp(16), dp(9), dp(16), dp(9)); bottom.setBackground(bg(BG, 0, 0));
        String[] labels = {"对话", "接口", "设置"}, icons = {"chat", "plug", "settings"};
        for (int i = 0; i < 3; i++) {
            final int n = i; LinearLayout item = row(); item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6), dp(11), dp(6), dp(11));
            if (tab == i) item.setBackground(bg(PALE, 16, 0));
            Icon icon = new Icon(icons[i], tab == i ? TEAL : MUTED);
            item.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView label = text("  " + labels[i], 14, tab == i ? TEAL : MUTED, tab == i);
            item.addView(label); item.setOnClickListener(v -> navigate(n));
            bottom.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    private Provider currentProvider() {
        Conversation c = state.current();
        Provider p = state.provider(c == null ? selectedProvider : c.providerId);
        if (p == null && c == null && !state.providers.isEmpty()) p = state.providers.get(0);
        return p;
    }
    private String currentModel() {
        Conversation c = state.current(); if (c != null) return c.model;
        Provider p = currentProvider();
        return p == null ? "" : p.id.equals(selectedProvider) && p.models.contains(selectedModel) ? selectedModel : p.models.get(0);
    }
    private void renderChat() {
        LinearLayout modelRow = row(); modelRow.setPadding(dp(22), 0, dp(22), dp(12));
        Provider p = currentProvider();
        modelLabel = text(p == null ? "＋ 连接一个 AI 接口" : "●  " + currentModel() + "  ⌄", 13, TEAL, true);
        modelLabel.setMaxLines(1); modelLabel.setEllipsize(TextUtils.TruncateAt.END);
        modelLabel.setPadding(dp(13), dp(10), dp(13), dp(10)); modelLabel.setBackground(bg(PALE, 12, 0));
        modelLabel.setOnClickListener(v -> showModelPicker());
        modelRow.addView(modelLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("  ···", 24, MUTED, true); more.setContentDescription("对话操作");
        more.setPadding(dp(12), dp(5), 0, dp(5)); more.setOnClickListener(v -> chatActions()); modelRow.addView(more);
        page.addView(modelRow);
        messagesBox = col(); messagesBox.setPadding(dp(22), dp(6), dp(22), dp(16));
        messagesScroll = scrolling(messagesBox); page.addView(messagesScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderMessages();
        LinearLayout inputArea = col(); inputArea.setPadding(dp(16), dp(8), dp(16), dp(3));
        LinearLayout box = row(); box.setGravity(Gravity.BOTTOM); box.setPadding(dp(13), dp(9), dp(8), dp(9));
        box.setBackground(bg(WHITE, 22, LINE));
        composer = new EditText(this); composer.setTextSize(16); composer.setTextColor(INK); composer.setHintTextColor(MUTED);
        composer.setBackgroundColor(android.graphics.Color.TRANSPARENT); composer.setPadding(dp(4), dp(9), dp(8), dp(9));
        composer.setHint("写下问题，开始对话…"); composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMaxLines(4); composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12000)}); composer.setText(draft);
        box.addView(composer, new LinearLayout.LayoutParams(0, -2, 1));
        Icon send = new Icon(activeCall == null ? "send" : "stop", WHITE);
        send.setPadding(dp(12), dp(12), dp(12), dp(12)); send.setBackground(bg(TEAL, 15, 0));
        send.setContentDescription(activeCall == null ? "发送消息" : "停止生成");
        send.setOnClickListener(v -> { if (activeCall != null) { activeCall.cancel(); toast("正在停止生成…"); } else sendMessage(false); });
        box.addView(send, new LinearLayout.LayoutParams(dp(46), dp(46))); sendButton = send;
        inputArea.addView(box);
        TextView hint = text("直连接口 · 本机加密保存 · AI 内容请核实", 10, MUTED, false);
        hint.setGravity(Gravity.CENTER); hint.setPadding(0, dp(8), 0, dp(2)); inputArea.addView(hint);
        page.addView(inputArea);
    }

    private void renderMessages() {
        messagesBox.removeAllViews(); streamingText = null; streamingReasoning = null; streamingStatus = null;
        Conversation c = state.current();
        if (c == null || c.messages.isEmpty()) { welcome(); return; }
        for (int i = 0; i < c.messages.size(); i++) addMessage(c.messages.get(i), i == c.messages.size() - 1);
        scrollEnd();
    }
    private void welcome() {
        gap(messagesBox, 30);
        Icon mark = new Icon("spark", TEAL); mark.setPadding(dp(18), dp(18), dp(18), dp(18)); mark.setBackground(bg(PALE, 23, 0));
        messagesBox.addView(mark, new LinearLayout.LayoutParams(dp(72), dp(72))); gap(messagesBox, 24);
        messagesBox.addView(text("让想法，\n即刻发生。", 31, INK, true)); gap(messagesBox, 14);
        messagesBox.addView(text(state.providers.isEmpty() ? "把你信赖的 AI，放在一起。\n添加 API，开启属于你的智能空间。" : "从一个问题开始。\n写作、编程与灵感，都在这里。", 15, MUTED, false));
        gap(messagesBox, 24);
        if (state.providers.isEmpty()) {
            messagesBox.addView(button("添加第一个 API   ＋", true, () -> navigate(1)));
            gap(messagesBox, 12); messagesBox.addView(text("支持 GPT · DeepSeek · GLM · 自定义接口", 11, MUTED, false));
        } else {
            String[] labels = {"✎  帮我润色一段文字", "⌘  一起分析代码问题", "✦  把想法整理成计划"};
            String[] prompts = {"请帮我润色下面这段文字：\n", "请帮我分析下面的代码，解释问题并给出改进建议：\n", "请帮我把以下想法整理成一份可执行的计划：\n"};
            for (int i = 0; i < labels.length; i++) {
                final String prompt = prompts[i];
                TextView b = button(labels[i], false, () -> { composer.setText(prompt); composer.setSelection(prompt.length()); composer.requestFocus();
                    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT); });
                b.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL); messagesBox.addView(b); gap(messagesBox, 9);
            }
        }
    }

    private void addMessage(Message m, boolean last) {
        boolean user = m.role.equals("user");
        LinearLayout block = col();
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(-1, -2); blockLp.bottomMargin = dp(20);
        messagesBox.addView(block, blockLp);
        LinearLayout caption = row();
        caption.addView(text(user ? "你" : "聚言 · " + state.current().model, 11, MUTED, true));
        block.addView(caption); gap(block, 8);
        LinearLayout bubble = col(); bubble.setPadding(dp(16), dp(14), dp(16), dp(14));
        bubble.setBackground(bg(user ? PALE : WHITE, 18, user ? 0 : LINE)); block.addView(bubble);
        TextView reasoning = text("", 13, MUTED, false); reasoning.setTextIsSelectable(true);
        if (!m.reasoning.isEmpty()) { reasoning.setText("思考过程\n" + m.reasoning); reasoning.setPadding(0, 0, 0, dp(12)); }
        else reasoning.setVisibility(View.GONE);
        bubble.addView(reasoning);
        TextView body = text(m.content.isEmpty() && m.status.equals("streaming") ? "正在连接…" : m.content, 16, INK, false);
        body.setTextIsSelectable(true); body.setLineSpacing(dp(6), 1); bubble.addView(body);
        TextView status = text("", 12, DANGER, false); status.setPadding(0, dp(10), 0, 0);
        if (!m.error.isEmpty()) status.setText(m.error); else status.setVisibility(View.GONE);
        bubble.addView(status);
        if (!user) {
            gap(block, 6); LinearLayout actions = row();
            TextView copy = text("复制", 12, MUTED, false); copy.setPadding(dp(4), dp(9), dp(18), dp(9));
            copy.setOnClickListener(v -> copy(m.content)); actions.addView(copy);
            if (last && !m.status.equals("streaming")) {
                TextView retry = text("重新生成", 12, TEAL, true); retry.setPadding(dp(4), dp(9), dp(12), dp(9));
                retry.setOnClickListener(v -> sendMessage(true)); actions.addView(retry);
            }
            block.addView(actions);
        }
        if (m == activeMessage && m.status.equals("streaming")) {
            streamingText = body; streamingReasoning = reasoning; streamingStatus = status;
        }
    }
    private void copy(String value) {
        if (value.isEmpty()) { toast("暂无可复制内容"); return; }
        ClipData clip = ClipData.newPlainText("聚言 AI", value);
        if (Build.VERSION.SDK_INT >= 33) {
            android.os.PersistableBundle extras = new android.os.PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip); toast("已复制，请注意剪贴板隐私");
    }
    private boolean nearBottom() {
        return messagesScroll != null && messagesBox != null && messagesBox.getHeight() - (messagesScroll.getScrollY() + messagesScroll.getHeight()) < dp(100);
    }
    private void scrollEnd() { if (messagesScroll != null) messagesScroll.post(() -> { if (!destroyed && messagesScroll != null) messagesScroll.fullScroll(View.FOCUS_DOWN); }); }

    private void showModelPicker() {
        if (activeCall != null) { toast("请先停止当前生成，再切换模型"); return; }
        if (state.providers.isEmpty()) { navigate(1); return; }
        List<String> names = new ArrayList<>(); List<Provider> ps = new ArrayList<>(); List<String> ms = new ArrayList<>();
        for (Provider p : state.providers) for (String m : p.models) { names.add(p.name + "  /  " + m); ps.add(p); ms.add(m); }
        new AlertDialog.Builder(this).setTitle("选择模型")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    Provider p = ps.get(which); selectedProvider = p.id; selectedModel = ms.get(which);
                    Conversation c = state.current();
                    if (c != null) { c.providerId = p.id; c.model = selectedModel; }
                    persist(); captureDraft(); render();
                }).setNegativeButton("取消", null).show();
    }
    private void newChat(boolean choose) {
        if (activeCall != null) { toast("请先停止当前生成"); return; }
        Provider p = currentProvider(); String m = currentModel();
        if (p != null) { selectedProvider = p.id; selectedModel = m; }
        state.currentId = ""; draft = ""; persist(); tab = 0; render();
        if (choose && state.providers.isEmpty()) toast("先在接口页面添加 API");
    }

    private void sendMessage(boolean retry) {
        if (activeCall != null) { toast("正在生成，请稍候或点击停止"); return; }
        if (storageBlocked) { alert("无法开始", "本地数据处于保护状态，请先在设置中处理数据解密问题。"); return; }
        Provider p = currentProvider(); String model = currentModel();
        if (p == null) { toast("请先添加或选择 API 接口"); navigate(1); return; }
        if (p.key.trim().isEmpty()) { editProvider(p, "", "", ""); return; }
        Conversation c = state.current();
        String prompt = composer == null ? draft.trim() : composer.getText().toString().trim();
        if (!retry && prompt.isEmpty()) { toast("先写下你的问题"); return; }
        if (retry && (c == null || c.messages.isEmpty())) return;
        if (!retry && c != null && c.messages.size() >= 200) { alert("当前对话较长", "每个对话最多保留 200 条消息，请新建对话继续。"); return; }
        if (c == null) {
            if (state.conversations.size() >= 100) { alert("对话数量已达上限", "最多保留 100 个对话，请在历史记录中删除不需要的对话后重试。"); return; }
            c = new Conversation(); c.providerId = p.id; c.model = model;
            c.title = prompt.length() > 24 ? prompt.substring(0, 24) + "…" : prompt;
            c.title = c.title.replace('\n', ' '); state.conversations.add(c); state.currentId = c.id;
        }
        if (retry) {
            Message last = c.messages.get(c.messages.size() - 1);
            if (last.role.equals("assistant")) c.messages.remove(c.messages.size() - 1);
            if (c.messages.isEmpty() || !c.messages.get(c.messages.size() - 1).role.equals("user")) return;
        } else { c.messages.add(new Message("user", prompt)); draft = ""; }
        c.updatedAt = System.currentTimeMillis();
        try {
            JSONObject body = AiProtocol.request(model, state.systemPrompt, c.messages, true);
            AiClient.Call call = new AiClient.Call(p.baseUrl, p.key, body, true);
            Message reply = new Message("assistant", ""); reply.status = "streaming"; c.messages.add(reply);
            activeCall = call; activeMessage = reply; activeConversation = c;
            if (!persist()) {
                activeCall = null; activeMessage = null; activeConversation = null;
                reply.status = "error"; reply.error = "本地保存失败，为保留记录已取消本次请求。请检查空间后重新生成。";
                render(); return;
            }
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
            tab = 0; render(); scrollEnd();
            executor.execute(() -> call.run(new AiClient.Listener() {
                public void onUpdate(String value, String reasoning) { main.post(() -> {
                    if (destroyed || activeCall != call) return;
                    reply.content = value; reply.reasoning = reasoning;
                    if (streamingText != null && tab == 0) {
                        boolean follow = nearBottom();
                        streamingText.setText(value.isEmpty() ? "正在思考…" : value);
                        if (!reasoning.isEmpty()) { streamingReasoning.setText("思考过程\n" + reasoning); streamingReasoning.setVisibility(View.VISIBLE); }
                        if (follow) scrollEnd();
                    }
                }); }
                public void onEnd(String value, String reasoning, boolean cancelled, String error) { main.post(() -> {
                    if (destroyed || activeCall != call) return;
                    reply.content = value; reply.reasoning = reasoning;
                    reply.status = cancelled ? "interrupted" : error.isEmpty() ? "complete" : "error";
                    reply.error = cancelled ? "已停止生成，可重新生成。" : error;
                    activeCall = null; activeMessage = null; activeConversation = null;
                    persist(); captureDraft(); if (tab == 0) render();
                }); }
            }));
        } catch (Exception e) { toast("无法创建请求，请检查接口配置"); persist(); }
    }

    private void chatActions() {
        Conversation c = state.current();
        if (c == null || c.messages.isEmpty()) { showModelPicker(); return; }
        new AlertDialog.Builder(this).setTitle(c.title).setItems(new String[]{"导出当前对话（纯文本）", "重命名对话", "删除当前对话"}, (d, which) -> {
            if (which == 0) {
                if (activeCall != null) { toast("请先停止生成再导出"); return; }
                exportPayload = conversationText(c); createDocument("text/plain", "聚言-对话.txt", EXPORT_CHAT);
            } else if (which == 1) {
                EditText name = field(c.title, "对话标题", false); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
                new AlertDialog.Builder(this).setTitle("重命名对话").setView(name).setPositiveButton("保存", (a, b) -> {
                    if (!name.getText().toString().trim().isEmpty()) { c.title = name.getText().toString().trim(); persist(); }
                }).setNegativeButton("取消", null).show();
            } else deleteConversation(c);
        }).show();
    }
    private void showHistory() {
        if (activeCall != null) { toast("请先停止当前生成，再切换对话"); return; }
        if (state.conversations.isEmpty()) { toast("还没有历史对话"); return; }
        List<Conversation> list = new ArrayList<>(state.conversations);
        Collections.sort(list, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        String[] titles = new String[list.size()];
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < list.size(); i++) titles[i] = list.get(i).title + "\n" + fmt.format(new Date(list.get(i).updatedAt)) + " · " + list.get(i).model;
        new AlertDialog.Builder(this).setTitle("历史对话 · " + list.size()).setItems(titles, (d, i) -> {
            captureDraft(); state.currentId = list.get(i).id; persist(); tab = 0; render();
        }).setNegativeButton("关闭", null).show();
    }
    private void deleteConversation(Conversation c) {
        if (activeCall != null) { toast("请先停止当前生成"); return; }
        new AlertDialog.Builder(this).setTitle("删除这个对话？").setMessage("删除后无法恢复，接口配置会保留。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    state.conversations.remove(c); if (state.currentId.equals(c.id)) state.currentId = "";
                    persist(); captureDraft(); render();
                }).show();
    }

    private void renderProviders() {
        LinearLayout list = col(); list.setPadding(dp(22), dp(5), dp(22), dp(16)); page.addView(scrolling(list));
        list.addView(text("一个入口，连接不同的智能。", 15, MUTED, false)); gap(list, 22);
        LinearLayout summary = row();
        summary.addView(text("已添加接口  " + String.format(Locale.ROOT, "%02d", state.providers.size()), 12, MUTED, true)); weight(summary);
        TextView imp = text("导入 JSON", 13, TEAL, true); imp.setPadding(dp(10), dp(8), 0, dp(8)); imp.setOnClickListener(v -> importConfig()); summary.addView(imp);
        list.addView(summary); gap(list, 10);
        for (Provider p : state.providers) {
            LinearLayout c = card(list), head = row();
            TextView logo = text(p.name.substring(0, Math.min(p.name.length(), 1)).toUpperCase(Locale.ROOT), 20, TEAL, true);
            logo.setGravity(Gravity.CENTER); logo.setBackground(bg(PALE, 13, 0)); head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout labels = col(); labels.setPadding(dp(12), 0, dp(6), 0);
            labels.addView(text(p.name, 17, INK, true)); gap(labels, 5);
            TextView host = text(Uri.parse(p.baseUrl).getHost(), 11, MUTED, false); host.setSingleLine(true); host.setEllipsize(TextUtils.TruncateAt.END); labels.addView(host);
            head.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView edit = text("管理", 13, TEAL, true); edit.setPadding(dp(10), dp(12), 0, dp(12)); edit.setOnClickListener(v -> providerActions(p)); head.addView(edit); c.addView(head);
            gap(c, 15); TextView models = text(String.join("  ·  ", p.models), 13, MUTED, false); models.setMaxLines(3); models.setEllipsize(TextUtils.TruncateAt.END); c.addView(models);
            gap(c, 14); LinearLayout statusRow = row();
            statusRow.addView(text(p.key.isEmpty() ? "○ 尚未填写密钥" : "● 密钥已加密保存", 11, p.key.isEmpty() ? DANGER : TEAL, false)); weight(statusRow);
            TextView use = text("开始对话  →", 13, TEAL, true); use.setPadding(dp(8), dp(6), 0, dp(6));
            use.setOnClickListener(v -> {
                if (activeCall != null) { toast("请先停止当前生成"); return; }
                selectedProvider = p.id; selectedModel = p.models.get(0); state.currentId = ""; persist(); navigate(0);
            }); statusRow.addView(use); c.addView(statusRow);
        }
        if (state.providers.isEmpty()) {
            LinearLayout empty = card(list); empty.addView(text("你的 AI，由你选择。", 21, INK, true)); gap(empty, 10);
            empty.addView(text("填写服务商的 API Key 和模型 ID，即可直接在手机上使用。", 14, MUTED, false)); gap(empty, 15);
            empty.addView(button("添加 API 接口", true, this::showTemplates));
        } else list.addView(button("＋ 添加更多接口", false, this::showTemplates));
        gap(list, 25); list.addView(text("快速接入", 12, MUTED, true)); gap(list, 12);
        templateCard(list, "OpenAI", "GPT 系列模型", "https://api.openai.com/v1", "gpt-4o-mini\ngpt-4o");
        templateCard(list, "DeepSeek", "通用对话与深度推理", "https://api.deepseek.com", "deepseek-chat\ndeepseek-reasoner");
        templateCard(list, "智谱 GLM", "中文创作与智能问答", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash");
        gap(list, 4); list.addView(text("模板模型仅供参考，请以服务商当前提供的模型 ID 和账号权限为准。订阅 ChatGPT 不等于拥有 API 额度。", 11, MUTED, false));
    }
    private void templateCard(LinearLayout parent, String name, String subtitle, String url, String models) {
        LinearLayout c = card(parent); LinearLayout r = row();
        LinearLayout labels = col(); labels.addView(text(name, 16, INK, true)); gap(labels, 4); labels.addView(text(subtitle, 12, MUTED, false));
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(text("＋", 24, TEAL, false)); c.addView(r);
        c.setOnClickListener(v -> editProvider(null, name, url, models));
    }
    private void showTemplates() {
        if (storageBlocked) { toast("请先处理本地数据解密问题"); return; }
        new AlertDialog.Builder(this).setTitle("添加 API 接口").setItems(new String[]{"OpenAI / GPT", "DeepSeek", "智谱 GLM", "自定义 OpenAI 兼容接口"}, (d, w) -> {
            if (w == 0) editProvider(null, "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini\ngpt-4o");
            else if (w == 1) editProvider(null, "DeepSeek", "https://api.deepseek.com", "deepseek-chat\ndeepseek-reasoner");
            else if (w == 2) editProvider(null, "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash");
            else editProvider(null, "", "", "");
        }).setNegativeButton("取消", null).show();
    }
    private void providerActions(Provider p) {
        new AlertDialog.Builder(this).setTitle(p.name).setItems(new String[]{"编辑配置 / 测试连接", "删除接口"}, (d, i) -> {
            if (activeCall != null) { toast("请先停止当前生成"); return; }
            if (i == 0) editProvider(p, "", "", "");
            else new AlertDialog.Builder(this).setTitle("删除接口？").setMessage("将删除此接口的密钥与配置。已有聊天记录会保留，可重新选择其他接口继续。")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (a, b) -> { state.providers.remove(p); persist(); render(); }).show();
        }).show();
    }

    private EditText field(String value, String hint, boolean multiline) {
        EditText e = new EditText(this); e.setTextSize(15); e.setTextColor(INK); e.setHintTextColor(MUTED); e.setText(value); e.setHint(hint);
        e.setPadding(dp(12), dp(12), dp(12), dp(12)); e.setBackground(bg(BG, 11, LINE));
        e.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        if (multiline) { e.setMinLines(2); e.setMaxLines(5); } else e.setSingleLine(true);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(multiline ? 4000 : 2048)}); return e;
    }
    private void labelField(LinearLayout form, String title, EditText field) {
        form.addView(text(title, 12, MUTED, true)); gap(form, 7); form.addView(field, new LinearLayout.LayoutParams(-1, -2)); gap(form, 16);
    }
    private void editProvider(Provider original, String name, String url, String models) {
        if (activeCall != null) { toast("请先停止当前生成，再编辑接口"); return; }
        if (storageBlocked) { toast("请先处理本地数据解密问题"); return; }
        if (original == null && state.providers.size() >= 30) { toast("最多添加 30 个接口"); return; }
        LinearLayout form = col(); form.setPadding(dp(22), dp(12), dp(22), dp(8));
        EditText nameInput = field(original == null ? name : original.name, "例如：我的 DeepSeek", false);
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        EditText urlInput = field(original == null ? url : original.baseUrl, "https://api.example.com/v1", false);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText keyInput = field(original == null ? "" : original.key, "填写你的 API Key", false);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        EditText modelsInput = field(original == null ? models : String.join("\n", original.models), "每行一个模型 ID，也可用逗号分隔", true);
        labelField(form, "接口名称", nameInput); labelField(form, "Base URL / 完整 Chat Completions 地址", urlInput);
        labelField(form, "API Key（仅本机加密保存）", keyInput);
        CheckBox reveal = new CheckBox(this); reveal.setText("显示密钥"); reveal.setTextSize(12); reveal.setTextColor(MUTED);
        reveal.setOnCheckedChangeListener((b, checked) -> { keyInput.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance()); keyInput.setSelection(keyInput.length()); });
        form.addView(reveal); gap(form, 10); labelField(form, "模型列表", modelsInput);
        TextView result = text("测试会向此地址发送你的密钥及一条简短请求，可能产生少量 API 费用。请确认地址可信。", 12, MUTED, false); form.addView(result);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(original == null ? "连接新的 AI" : "编辑接口")
                .setView(scrolling(form)).setNegativeButton("取消", null).setNeutralButton("测试连接", null).setPositiveButton("保存接口", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                Provider p = validatedProvider(nameInput, urlInput, keyInput, modelsInput, result, false);
                if (p == null) return;
                if (original != null) { p.id = original.id; state.providers.set(state.providers.indexOf(original), p); }
                else state.providers.add(p);
                if (!persist()) {
                    if (original != null) state.providers.set(state.providers.indexOf(p), original); else state.providers.remove(p);
                    result.setText("保存失败，请检查手机剩余空间后重试。"); return;
                }
                toast("接口已保存"); dialog.dismiss(); captureDraft(); render();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                Provider p = validatedProvider(nameInput, urlInput, keyInput, modelsInput, result, true); if (p == null) return;
                Button test = dialog.getButton(AlertDialog.BUTTON_NEUTRAL); test.setEnabled(false); result.setTextColor(MUTED); result.setText("正在测试第一个模型：" + p.models.get(0) + "…");
                try {
                    List<Message> messages = new ArrayList<>(); messages.add(new Message("user", "请仅回复 OK。"));
                    JSONObject body = AiProtocol.request(p.models.get(0), "", messages, false);
                    AiClient.Call call = new AiClient.Call(p.baseUrl, p.key, body, false); testCall = call;
                    executor.execute(() -> call.run(new AiClient.Listener() {
                        public void onUpdate(String s, String r) { }
                        public void onEnd(String s, String r, boolean cancelled, String error) { main.post(() -> {
                            if (destroyed || !dialog.isShowing() || testCall != call) return;
                            testCall = null; test.setEnabled(true); result.setTextColor(error.isEmpty() && !cancelled ? TEAL : DANGER);
                            result.setText(cancelled ? "测试已取消" : error.isEmpty() ? "连接成功，模型已返回文本。保存后即可开始对话。" : error);
                        }); }
                    }));
                } catch (Exception e) { test.setEnabled(true); result.setText("无法发起测试，请检查配置。"); }
            });
        });
        dialog.setOnDismissListener(d -> { if (testCall != null) { testCall.cancel(); testCall = null; } });
        dialog.show();
        if (state.screenPrivacy && dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private Provider validatedProvider(EditText n, EditText u, EditText k, EditText m, TextView result, boolean needKey) {
        try {
            Provider p = new Provider(); p.name = n.getText().toString().trim(); p.baseUrl = u.getText().toString().trim(); p.key = k.getText().toString().trim();
            if (p.name.isEmpty()) throw new IllegalArgumentException("请填写接口名称。");
            AiProtocol.endpoint(p.baseUrl);
            if (p.key.contains("\n") || p.key.contains("\r")) throw new IllegalArgumentException("密钥不能包含换行。");
            if (needKey && p.key.isEmpty()) throw new IllegalArgumentException("请先填写 API Key。");
            for (String item : m.getText().toString().split("[\n,，]+")) {
                String model = item.trim();
                if (!model.isEmpty() && !p.models.contains(model)) {
                    if (model.length() > 160 || model.matches(".*\\s+.*")) throw new IllegalArgumentException("模型 ID 不能包含空格，且不超过 160 字符。");
                    p.models.add(model);
                }
            }
            if (p.models.isEmpty() || p.models.size() > 30) throw new IllegalArgumentException("请填写 1 至 30 个模型 ID。");
            return p;
        } catch (IllegalArgumentException e) { result.setTextColor(DANGER); result.setText(e.getMessage()); return null; }
    }

    private void renderSettings() {
        LinearLayout list = col(); list.setPadding(dp(22), dp(5), dp(22), dp(18)); page.addView(scrolling(list));
        LinearLayout hero = card(list); hero.addView(text("你的数据，留在你的手机。", 21, INK, true)); gap(hero, 10);
        hero.addView(text("密钥与对话使用 Android Keystore + AES-GCM 加密。请求由本机直接发送到配置的服务商，不经过聚言服务器。", 14, MUTED, false));
        gap(list, 12); list.addView(text("对话与隐私", 12, MUTED, true)); gap(list, 12);
        setting(list, "系统提示词", "设置 AI 的角色与回答偏好", this::editSystemPrompt);
        setting(list, "隐私屏幕：" + (state.screenPrivacy ? "已开启" : "已关闭"), "开启后禁止截图，并隐藏最近任务预览", () -> {
            state.screenPrivacy = !state.screenPrivacy; applyPrivacy(); persist(); render();
        });
        setting(list, "上下文范围", "每次发送最近 20 条完整消息与系统提示词", () -> alert("上下文与数据使用", "每次请求默认发送最近 20 条完整消息（尽量从用户消息开始）和当前系统提示词。中断或失败的 AI 回复不会作为上下文发送。切换模型会继续使用当前对话上下文；如需独立聊天，请点右上角 ＋。\n\n每个对话最多 200 条消息，本机最多保留 100 个对话。"));
        gap(list, 14); list.addView(text("数据管理", 12, MUTED, true)); gap(list, 12);
        setting(list, "导入接口配置", "从 JSON 文件批量添加 API", this::importConfig);
        setting(list, "导出接口配置", "仅导出地址和模型，不含 API Key", () -> {
            try {
                JSONArray a = new JSONArray(); for (Provider p : state.providers) a.put(p.json(false));
                exportPayload = new JSONObject().put("version", 1).put("providers", a).toString(2);
                createDocument("application/json", "聚言-接口配置.json", EXPORT_CONFIG);
            } catch (Exception e) { toast("导出失败"); }
        });
        setting(list, "清空聊天记录", "保留接口配置及密钥", () -> {
            if (activeCall != null) { toast("请先停止当前生成"); return; }
            new AlertDialog.Builder(this).setTitle("清空所有对话？").setMessage("此操作无法恢复，请先导出需要保留的对话。")
                    .setNegativeButton("取消", null).setPositiveButton("清空对话", (d, w) -> { state.conversations.clear(); state.currentId = ""; persist(); toast("已清空对话"); }).show();
        });
        setting(list, "清空全部本地数据", "删除所有密钥、配置及对话", () -> {
            if (activeCall != null) { toast("请先停止当前生成"); return; }
            new AlertDialog.Builder(this).setTitle("删除全部本地数据？").setMessage("此操作将永久删除全部 API Key、配置及聊天记录，无法恢复。你需要重新添加接口。")
                    .setNegativeButton("取消", null).setPositiveButton("永久删除", (d, w) -> {
                        try {
                            store.clear(); state = new AppState(); storageBlocked = false;
                            selectedProvider = ""; selectedModel = ""; draft = ""; applyPrivacy();
                            persist(); render(); toast("已删除全部本地数据");
                        } catch (Exception e) { alert("清空失败", "无法重置系统密钥，请在 Android 系统设置中清除本应用数据。"); }
                    }).show();
        });
        gap(list, 18); TextView footer = text("聚言 AI  1.0.0\n个人使用 · 无广告 · 无账号", 12, MUTED, false); footer.setGravity(Gravity.CENTER); list.addView(footer);
    }
    private void setting(LinearLayout list, String title, String sub, Runnable click) {
        LinearLayout c = card(list), r = row(), labels = col(); labels.addView(text(title, 15, INK, true)); gap(labels, 6); labels.addView(text(sub, 12, MUTED, false));
        r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(text("›", 24, MUTED, false)); c.addView(r); c.setOnClickListener(v -> click.run());
    }
    private void editSystemPrompt() {
        LinearLayout form = col(); form.setPadding(dp(22), dp(10), dp(22), dp(10));
        EditText e = field(state.systemPrompt, "例如：你是一位专业的中文写作助手…", true); e.setMinLines(5); form.addView(e);
        gap(form, 12); form.addView(text("修改后应用于下一次请求。留空则不发送系统提示词。", 12, MUTED, false));
        new AlertDialog.Builder(this).setTitle("系统提示词").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> { state.systemPrompt = e.getText().toString().trim(); persist(); }).show();
    }
    private void showAbout() { alert("聚言 AI · 个人版", "一个轻量的安卓多模型 AI 客户端。\n\n支持 OpenAI Chat Completions 兼容格式的 HTTPS 接口，包括 GPT、DeepSeek 和 GLM 等文本模型。\n\n不内置付费额度；模型调用费用由服务商根据你的账户收取。订阅类会员通常不包含 API 额度。\n\n卸载应用会删除本机密钥与对话。配置导出不包含密钥。当前版本不包含联网搜索、文件上传、语音或图像生成。"); }

    private void importConfig() {
        if (activeCall != null) { toast("请先停止当前生成"); return; }
        if (storageBlocked) { toast("请先处理本地数据解密问题"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try { startActivityForResult(i, IMPORT_CONFIG); } catch (Exception e) { toast("系统文件选择器不可用"); }
    }
    private void createDocument(String mime, String name, int request) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType(mime); i.putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(i, request); } catch (Exception e) { toast("系统文件选择器不可用"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT_CONFIG) {
            executor.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalArgumentException("无法读取文件。");
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 262144) throw new IllegalArgumentException("配置文件不能超过 256 KB。"); }
                    List<Provider> imported = ConfigImport.parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    main.post(() -> { if (!destroyed) previewImport(imported); });
                } catch (Exception e) { main.post(() -> { if (!destroyed) alert("导入失败", e instanceof IllegalArgumentException ? e.getMessage() : "文件格式不正确，请使用提供的 JSON 模板。原有配置未改变。"); }); }
            });
        } else if (request == EXPORT_CONFIG || request == EXPORT_CHAT) {
            String payload = exportPayload; exportPayload = "";
            if (payload.isEmpty()) { toast("导出内容已过期，请重新操作"); return; }
            executor.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException(); out.write(payload.getBytes(StandardCharsets.UTF_8));
                    main.post(() -> { if (!destroyed) toast("已导出到所选文件"); });
                } catch (Exception e) { main.post(() -> { if (!destroyed) toast("无法写入文件，请重新选择保存位置"); }); }
            });
        }
    }
    private void previewImport(List<Provider> list) {
        if (list.size() + state.providers.size() > 30) { alert("接口数量过多", "最多保存 30 个接口，请先删除不需要的接口。"); return; }
        StringBuilder summary = new StringBuilder("将新增以下接口，不会覆盖已有配置：\n\n");
        boolean hasKeys = false;
        for (Provider p : list) {
            summary.append(p.name).append("\n").append(p.baseUrl).append("\n").append(p.models.size()).append(" 个模型\n\n");
            hasKeys |= !p.key.isEmpty();
        }
        summary.append(hasKeys ? "文件含 API Key，导入后会在本机加密保存；请妥善处理原始明文文件。" : "文件不含密钥的接口，需要在导入后补填 API Key。");
        summary.append("\n请确认地址可信。导入本身不会发送网络请求。");
        new AlertDialog.Builder(this).setTitle("确认导入 " + list.size() + " 个接口").setMessage(summary.toString()).setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    state.providers.addAll(list);
                    if (!persist()) { state.providers.removeAll(list); return; }
                    tab = 1; render(); toast("已导入接口");
                }).show();
    }
    private String conversationText(Conversation c) {
        StringBuilder b = new StringBuilder("聚言 AI · ").append(c.title).append("\n模型：").append(c.model).append("\n\n");
        for (Message m : c.messages) {
            b.append(m.role.equals("user") ? "你" : "AI").append("：\n");
            if (!m.reasoning.isEmpty()) b.append("[思考过程]\n").append(m.reasoning).append("\n\n");
            b.append(m.content).append("\n"); if (!m.error.isEmpty()) b.append('[').append(m.error).append("]\n"); b.append("\n");
        }
        return b.toString();
    }

    private final class Icon extends View {
        private final String kind; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(String kind, int color) { super(MainActivity.this); this.kind = kind; p.setColor(color); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); c.save(); c.translate(getPaddingLeft(), getPaddingTop());
            c.scale((getWidth() - getPaddingLeft() - getPaddingRight()) / 24f, (getHeight() - getPaddingTop() - getPaddingBottom()) / 24f);
            p.setStyle(Paint.Style.STROKE); Path q = new Path();
            switch (kind) {
                case "plus": c.drawLine(12, 4, 12, 20, p); c.drawLine(4, 12, 20, 12, p); break;
                case "send": q.moveTo(12, 19); q.lineTo(12, 5); q.moveTo(6, 11); q.lineTo(12, 5); q.lineTo(18, 11); c.drawPath(q, p); break;
                case "stop": p.setStyle(Paint.Style.FILL); c.drawRoundRect(6, 6, 18, 18, 2, 2, p); break;
                case "chat": c.drawRoundRect(3, 4, 21, 18, 4, 4, p); q.moveTo(7, 18); q.lineTo(7, 22); q.lineTo(12, 18); c.drawPath(q, p); c.drawLine(8, 9, 16, 9, p); c.drawLine(8, 13, 13, 13, p); break;
                case "history": c.drawCircle(12, 12, 9, p); c.drawLine(12, 6, 12, 12, p); c.drawLine(12, 12, 16, 14, p); break;
                case "plug": c.drawRoundRect(6, 7, 18, 16, 4, 4, p); c.drawLine(9, 3, 9, 7, p); c.drawLine(15, 3, 15, 7, p); c.drawLine(12, 16, 12, 22, p); break;
                case "settings": c.drawCircle(12, 12, 7, p); c.drawCircle(12, 12, 2.5f, p); for (int i = 0; i < 8; i++) { c.save(); c.rotate(i * 45, 12, 12); c.drawLine(12, 2, 12, 5, p); c.restore(); } break;
                case "info": c.drawCircle(12, 12, 9, p); c.drawLine(12, 11, 12, 17, p); c.drawPoint(12, 7, p); break;
                default: q.moveTo(12, 2); q.cubicTo(13, 9, 15, 11, 22, 12); q.cubicTo(15, 13, 13, 15, 12, 22); q.cubicTo(11, 15, 9, 13, 2, 12); q.cubicTo(9, 11, 11, 9, 12, 2); c.drawPath(q, p);
            }
            c.restore();
        }
    }
}
