package cn.juyan.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class AiProtocol {
    static final int CONTEXT_MESSAGES = 20;
    static final int RESPONSE_LIMIT = 2_000_000;

    static String endpoint(String input) {
        String base = input.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            URI u = new URI(base);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null ||
                    u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        } catch (Exception e) {
            throw new IllegalArgumentException("请填写有效的 HTTPS Base URL，不要包含密钥、查询参数或片段。");
        }
    }

    static JSONObject request(String model, String system, List<AppState.Message> history, boolean stream) throws Exception {
        JSONArray messages = new JSONArray();
        if (!system.trim().isEmpty()) messages.put(new JSONObject().put("role", "system").put("content", system.trim()));
        List<AppState.Message> usable = new ArrayList<>();
        for (AppState.Message m : history) {
            if (m.role.equals("user") || (m.role.equals("assistant") && m.status.equals("complete") && !m.content.isEmpty()))
                usable.add(m);
        }
        int start = Math.max(0, usable.size() - CONTEXT_MESSAGES);
        while (start < usable.size() && !usable.get(start).role.equals("user")) start++;
        for (int i = start; i < usable.size(); i++) {
            AppState.Message m = usable.get(i);
            messages.put(new JSONObject().put("role", m.role).put("content", m.content));
        }
        return new JSONObject().put("model", model).put("messages", messages).put("stream", stream);
    }

    interface EventConsumer { boolean accept(String data) throws Exception; }

    /** SSE is framed by blank lines, not TCP chunks. Multi-line data events are supported. */
    static void readEvents(Reader reader, EventConsumer consumer) throws Exception {
        BufferedReader br = new BufferedReader(reader);
        StringBuilder event = new StringBuilder();
        String line;
        boolean first = true;
        while ((line = br.readLine()) != null) {
            if (first) { line = line.replace("\uFEFF", ""); first = false; }
            if (line.isEmpty()) {
                if (event.length() > 0) {
                    if (!consumer.accept(event.substring(0, event.length() - 1))) return;
                    event.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                event.append(data).append('\n');
                if (event.length() > RESPONSE_LIMIT) throw new IOException("事件过大");
            }
        }
        if (event.length() > 0) consumer.accept(event.substring(0, event.length() - 1));
    }

    static String statusMessage(int code) {
        if (code == 401 || code == 403) return "认证失败，请检查 API Key、接口地址和模型访问权限（HTTP " + code + "）。";
        if (code == 402) return "账户余额不足，请到服务商平台检查余额（HTTP 402）。";
        if (code == 404) return "接口或模型不存在，请检查 Base URL 和模型 ID（HTTP 404）。";
        if (code == 429) return "请求过于频繁或额度已用完，请稍后重试并检查服务商额度（HTTP 429）。";
        if (code >= 500) return "服务商暂时不可用，请稍后重试（HTTP " + code + "）。";
        return "请求未成功，请检查模型、参数或账户状态（HTTP " + code + "）。";
    }
}
