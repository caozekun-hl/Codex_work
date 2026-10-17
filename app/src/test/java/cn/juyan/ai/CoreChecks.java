package cn.juyan.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;

/** Offline checks run against actual production classes and JSON-java. No live API keys. */
public final class CoreChecks {
    private static int checks;
    private static FakeHttps next;
    public static void main(String[] args) throws Exception {
        endpointChecks(); requestChecks(); sseChecks(); configChecks(); stateChecks(); transportChecks();
        System.out.println("PASS: " + checks + " core checks (no network, no credentials)");
    }
    private static void eq(Object expected, Object actual) {
        checks++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }
    private static void yes(boolean value) { eq(true, value); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked c) throws Exception {
        boolean rejected = false; try { c.run(); } catch (Exception e) { rejected = true; }
        yes(rejected);
    }
    private static void endpointChecks() throws Exception {
        eq("https://api.deepseek.com/chat/completions", AiProtocol.endpoint(" https://api.deepseek.com/ "));
        eq("https://api.openai.com/v1/chat/completions", AiProtocol.endpoint("https://api.openai.com/v1/"));
        eq("https://open.bigmodel.cn/api/paas/v4/chat/completions", AiProtocol.endpoint("https://open.bigmodel.cn/api/paas/v4"));
        eq("https://example.com/v1/chat/completions", AiProtocol.endpoint("https://example.com/v1/chat/completions///"));
        for (String u : new String[]{"http://example.com", "https://user:secret@example.com/v1", "https://example.com?api_key=secret", "https://example.com#secret", "not-a-url", "https://", "file:///tmp/a"})
            rejects(() -> AiProtocol.endpoint(u));
    }
    private static void requestChecks() throws Exception {
        List<AppState.Message> history = new ArrayList<>();
        history.add(new AppState.Message("user", "你好"));
        AppState.Message failed = new AppState.Message("assistant", "incomplete"); failed.status = "error"; history.add(failed);
        history.add(new AppState.Message("user", "请再试一次"));
        JSONObject request = AiProtocol.request("deepseek-chat", "system", history, true);
        eq("deepseek-chat", request.getString("model")); eq(true, request.getBoolean("stream"));
        JSONArray ms = request.getJSONArray("messages"); eq(3, ms.length()); eq("system", ms.getJSONObject(0).getString("role"));
        eq("请再试一次", ms.getJSONObject(2).getString("content")); yes(!request.toString().contains("incomplete"));
        history.clear();
        for (int i = 0; i < 31; i++) history.add(new AppState.Message(i % 2 == 0 ? "user" : "assistant", "message " + i));
        ms = AiProtocol.request("glm-4-flash", "", history, true).getJSONArray("messages");
        yes(ms.length() <= 20); eq("user", ms.getJSONObject(0).getString("role")); eq("message 30", ms.getJSONObject(ms.length() - 1).getString("content"));
        AppState.Message pending = new AppState.Message("assistant", "pending"); pending.status = "streaming"; history.add(pending);
        yes(!AiProtocol.request("m", "", history, true).toString().contains("pending"));
    }
    private static void sseChecks() throws Exception {
        List<String> events = new ArrayList<>();
        String wire = "\uFEFF: comment\r\ndata: {\"text\":\"你好\"}\r\n\r\ndata: first\ndata: second\n\nevent: keepalive\n\ndata: [DONE]\n\ndata: ignored\n\n";
        Reader fragments = new StringReader(wire) {
            @Override public int read(char[] c, int off, int len) throws java.io.IOException { return super.read(c, off, Math.min(len, 1)); }
        };
        AiProtocol.readEvents(fragments, e -> { events.add(e); return !e.equals("[DONE]"); });
        eq(3, events.size()); eq("你好", new JSONObject(events.get(0)).getString("text")); eq("first\nsecond", events.get(1));
        events.clear(); AiProtocol.readEvents(new StringReader("data: final"), e -> { events.add(e); return true; }); eq("final", events.get(0));
        rejects(() -> AiProtocol.readEvents(new StringReader("data: " + "x".repeat(AiProtocol.RESPONSE_LIMIT + 1)), e -> true));
    }
    private static JSONObject config(String name, String url, String key) throws Exception {
        return new JSONObject().put("name", name).put("baseUrl", url).put("apiKey", key).put("models", new JSONArray().put("deepseek-chat").put("deepseek-chat"));
    }
    private static void configChecks() throws Exception {
        JSONObject p = config("test", "https://api.example.com/v1", "example-placeholder");
        String raw = new JSONObject().put("version", 1).put("providers", new JSONArray().put(p)).toString();
        List<AppState.Provider> parsed = ConfigImport.parse(raw);
        eq(1, parsed.size()); eq(1, parsed.get(0).models.size()); eq("example-placeholder", parsed.get(0).key);
        yes(!parsed.get(0).json(false).has("apiKey")); yes(parsed.get(0).json(true).has("apiKey"));
        yes(!ConfigImport.parse(raw).get(0).id.equals(parsed.get(0).id));
        rejects(() -> ConfigImport.parse("{\"providers\":[]}"));
        rejects(() -> ConfigImport.parse(new JSONObject().put("version", 2).put("providers", new JSONArray().put(p)).toString()));
        JSONObject bad = config("bad", "http://api.example.com", "");
        rejects(() -> ConfigImport.parse(new JSONObject().put("providers", new JSONArray().put(p).put(bad)).toString()));
        JSONObject badKey = config("bad", "https://api.example.com", "a\nb");
        rejects(() -> ConfigImport.parse(new JSONObject().put("providers", new JSONArray().put(badKey)).toString()));
        JSONObject badModel = config("bad", "https://api.example.com", "").put("models", new JSONArray().put("model with spaces"));
        rejects(() -> ConfigImport.parse(new JSONObject().put("providers", new JSONArray().put(badModel)).toString()));
    }
    private static void stateChecks() throws Exception {
        AppState s = new AppState();
        AppState.Conversation c = new AppState.Conversation();
        AppState.Message m = new AppState.Message("assistant", "已生成的一部分"); m.status = "streaming"; c.messages.add(m);
        s.conversations.add(c); s.currentId = c.id; s.screenPrivacy = true;
        AppState copy = AppState.from(s.json());
        eq(c.id, copy.current().id); eq(true, copy.screenPrivacy); eq("interrupted", copy.current().messages.get(0).status);
        eq("已生成的一部分", copy.current().messages.get(0).content);
    }
    private static final class Result implements AiClient.Listener {
        String content, reasoning, error; boolean cancelled;
        public void onUpdate(String c, String r) { }
        public void onEnd(String c, String r, boolean x, String e) { content = c; reasoning = r; cancelled = x; error = e; }
    }
    private static Result call(String body, String type, int status) throws Exception {
        next = new FakeHttps(new URL("https://api.example.com/v1/chat/completions"), body, type, status);
        AiClient.Call call = new AiClient.Call("https://api.example.com/v1", "test-only-placeholder", new JSONObject().put("model", "m"), true);
        Result r = new Result(); call.run(r); return r;
    }
    private static void transportChecks() throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") ? new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { return next; }
        } : null);
        Result r = call("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: [DONE]\n\n", "text/event-stream", 200);
        eq("你好", r.content); eq("思考", r.reasoning); eq("", r.error); eq(false, r.cancelled);
        eq(false, next.getInstanceFollowRedirects()); eq("Bearer test-only-placeholder", next.getRequestProperty("Authorization"));
        yes(next.sent.toString(StandardCharsets.UTF_8).contains("model"));
        r = call("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", "text/event-stream", 200);
        eq("partial", r.content); yes(!r.error.isEmpty());
        r = call("{\"choices\":[{\"message\":{\"content\":\"fallback JSON\"}}]}", "application/json", 200);
        eq("fallback JSON", r.content); eq("", r.error);
        r = call("must not be shown: secret", "application/json", 401); yes(r.error.contains("401")); yes(!r.error.contains("secret"));
        r = call("", "application/json", 302); yes(r.error.contains("重定向"));
        r = call("not-json", "application/json", 200); yes(r.error.contains("格式不兼容"));
        r = call("data: [DONE]\n\n", "text/event-stream", 200); yes(r.error.contains("没有返回文本"));
        r = call("data: {\"error\":{\"message\":\"private\"}}\n\n", "text/event-stream", 200); yes(!r.error.contains("private")); yes(!r.error.isEmpty());
        AiClient.Call cancelled = new AiClient.Call("https://api.example.com", "", new JSONObject(), true);
        cancelled.cancel(); r = new Result(); cancelled.run(r); eq(true, r.cancelled);
    }
    private static final class FakeHttps extends HttpsURLConnection {
        final byte[] bytes; final String type; final int code;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        FakeHttps(URL u, String body, String type, int code) { super(u); bytes = body.getBytes(StandardCharsets.UTF_8); this.type = type; this.code = code; }
        public void connect() { } public void disconnect() { } public boolean usingProxy() { return false; }
        public String getCipherSuite() { return "TEST"; } public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return null; } public Principal getPeerPrincipal() { return null; }
        public Principal getLocalPrincipal() { return null; }
        public int getResponseCode() { return code; } public String getContentType() { return type; }
        public OutputStream getOutputStream() { return sent; } public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
    }
}
