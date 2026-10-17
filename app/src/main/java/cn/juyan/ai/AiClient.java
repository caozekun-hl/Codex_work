package cn.juyan.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

final class AiClient {
    interface Listener {
        void onUpdate(String content, String reasoning);
        void onEnd(String content, String reasoning, boolean cancelled, String error);
    }

    static final class Call {
        private final String endpoint, key, body;
        private final boolean stream;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile HttpsURLConnection connection;

        Call(String baseUrl, String key, JSONObject request, boolean stream) {
            this.endpoint = AiProtocol.endpoint(baseUrl); this.key = key;
            this.body = request.toString(); this.stream = stream;
        }

        void cancel() {
            cancelled.set(true);
            HttpsURLConnection c = connection;
            if (c != null) {
                Thread t = new Thread(c::disconnect, "juyan-cancel"); t.setDaemon(true); t.start();
            }
        }

        void run(Listener listener) {
            StringBuilder text = new StringBuilder(), reasoning = new StringBuilder();
            String error = "";
            try {
                if (cancelled.get()) return;
                HttpsURLConnection c = (HttpsURLConnection) new URL(endpoint).openConnection();
                connection = c;
                c.setConnectTimeout(20_000); c.setReadTimeout(120_000);
                c.setInstanceFollowRedirects(false); // Never forward a key to a redirected host.
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + key);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Accept", stream ? "text/event-stream" : "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                if (cancelled.get()) return;
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) throw new ApiException("接口发生重定向，请直接填写最终 HTTPS API 地址。");
                if (code < 200 || code >= 300) throw new ApiException(AiProtocol.statusMessage(code));
                String type = c.getContentType();
                try (InputStream in = c.getInputStream(); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")) {
                        final boolean[] finished = {false};
                        final long[] lastUpdate = {0};
                        AiProtocol.readEvents(reader, data -> {
                            if (cancelled.get()) return false;
                            if (data.trim().equals("[DONE]")) { finished[0] = true; return false; }
                            JSONObject o = new JSONObject(data);
                            if (o.has("error")) throw new ApiException("服务商在生成中返回错误，请检查模型权限、额度或稍后重试。");
                            JSONArray choices = o.optJSONArray("choices");
                            if (choices == null || choices.length() == 0) return true;
                            JSONObject choice = choices.getJSONObject(0), delta = choice.optJSONObject("delta");
                            if (delta != null) {
                                append(delta, "content", text); append(delta, "reasoning_content", reasoning);
                            }
                            if (!choice.isNull("finish_reason") && choice.has("finish_reason")) finished[0] = true;
                            if (text.length() + reasoning.length() > AiProtocol.RESPONSE_LIMIT)
                                throw new ApiException("回复过长，已停止接收，请缩小问题范围。");
                            long now = System.nanoTime();
                            if (now - lastUpdate[0] > 70_000_000) {
                                listener.onUpdate(text.toString(), reasoning.toString()); lastUpdate[0] = now;
                            }
                            return true;
                        });
                        if (!finished[0] && !cancelled.get()) throw new ApiException("连接在回复完成前中断，可重新生成。");
                    } else {
                        StringBuilder raw = new StringBuilder(); char[] buf = new char[4096]; int n;
                        while ((n = reader.read(buf)) != -1) {
                            if (cancelled.get()) break;
                            raw.append(buf, 0, n);
                            if (raw.length() > AiProtocol.RESPONSE_LIMIT) throw new ApiException("回复过长，请缩小问题范围。");
                        }
                        if (!cancelled.get()) {
                            JSONObject o = new JSONObject(raw.toString());
                            if (o.has("error")) throw new ApiException("服务商返回错误，请检查配置和账户状态。");
                            JSONObject m = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                            append(m, "content", text); append(m, "reasoning_content", reasoning);
                        }
                    }
                }
                if (text.length() == 0 && !cancelled.get()) throw new ApiException("服务商没有返回文本内容，请确认所选模型支持文本聊天。");
            } catch (ApiException e) { error = e.getMessage(); }
            catch (SocketTimeoutException e) { error = "连接或回复超时，请检查网络，或稍后重试。"; }
            catch (UnknownHostException e) { error = "无法解析接口地址，请检查网络和 Base URL。"; }
            catch (SSLException e) { error = "HTTPS 证书验证失败，请使用可信的 HTTPS 接口。"; }
            catch (org.json.JSONException e) { error = "回复格式不兼容，需要支持 OpenAI Chat Completions 格式的接口。"; }
            catch (Exception e) { error = "连接失败，请检查网络及 HTTPS 接口地址。"; }
            finally {
                HttpsURLConnection c = connection;
                if (c != null) c.disconnect(); connection = null;
                listener.onEnd(text.toString(), reasoning.toString(), cancelled.get(), error);
            }
        }

        private static void append(JSONObject o, String field, StringBuilder target) {
            Object v = o.opt(field);
            if (v instanceof String) target.append((String) v);
        }
    }

    private static final class ApiException extends Exception {
        ApiException(String message) { super(message); }
    }
}
