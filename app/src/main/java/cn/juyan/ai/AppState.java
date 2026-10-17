package cn.juyan.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AppState {
    final List<Provider> providers = new ArrayList<>();
    final List<Conversation> conversations = new ArrayList<>();
    String currentId = "";
    String systemPrompt = "你是一位乐于助人、严谨且友好的 AI 助手。请优先使用中文回答。";
    boolean screenPrivacy = false;

    Provider provider(String id) {
        for (Provider p : providers) if (p.id.equals(id)) return p;
        return null;
    }

    Conversation current() {
        for (Conversation c : conversations) if (c.id.equals(currentId)) return c;
        return null;
    }

    JSONObject json() throws JSONException {
        JSONObject o = new JSONObject().put("version", 1).put("currentId", currentId)
                .put("systemPrompt", systemPrompt).put("screenPrivacy", screenPrivacy);
        JSONArray ps = new JSONArray(), cs = new JSONArray();
        for (Provider p : providers) ps.put(p.json(true));
        for (Conversation c : conversations) cs.put(c.json());
        return o.put("providers", ps).put("conversations", cs);
    }

    static AppState from(JSONObject o) throws JSONException {
        if (o.optInt("version", 1) != 1) throw new JSONException("不支持的数据版本");
        AppState s = new AppState();
        s.currentId = o.optString("currentId", "");
        s.systemPrompt = o.optString("systemPrompt", s.systemPrompt);
        s.screenPrivacy = o.optBoolean("screenPrivacy", false);
        JSONArray ps = o.optJSONArray("providers"), cs = o.optJSONArray("conversations");
        if (ps != null) for (int i = 0; i < ps.length(); i++) s.providers.add(Provider.from(ps.getJSONObject(i)));
        if (cs != null) for (int i = 0; i < cs.length(); i++) s.conversations.add(Conversation.from(cs.getJSONObject(i)));
        return s;
    }

    static String id() { return UUID.randomUUID().toString(); }

    static final class Provider {
        String id = AppState.id(), name = "", baseUrl = "", key = "";
        final List<String> models = new ArrayList<>();

        JSONObject json(boolean includeKey) throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("name", name).put("baseUrl", baseUrl)
                    .put("models", new JSONArray(models));
            if (includeKey) o.put("apiKey", key);
            return o;
        }

        static Provider from(JSONObject o) throws JSONException {
            Provider p = new Provider();
            p.id = o.optString("id", AppState.id());
            p.name = o.getString("name");
            p.baseUrl = o.getString("baseUrl");
            p.key = o.optString("apiKey", "");
            JSONArray a = o.getJSONArray("models");
            for (int i = 0; i < a.length(); i++) p.models.add(a.getString(i));
            return p;
        }
    }

    static final class Conversation {
        String id = AppState.id(), title = "新的对话", providerId = "", model = "";
        long updatedAt = System.currentTimeMillis();
        final List<Message> messages = new ArrayList<>();

        JSONObject json() throws JSONException {
            JSONArray a = new JSONArray();
            for (Message m : messages) a.put(m.json());
            return new JSONObject().put("id", id).put("title", title).put("providerId", providerId)
                    .put("model", model).put("updatedAt", updatedAt).put("messages", a);
        }

        static Conversation from(JSONObject o) throws JSONException {
            Conversation c = new Conversation();
            c.id = o.getString("id"); c.title = o.getString("title");
            c.providerId = o.getString("providerId"); c.model = o.getString("model");
            c.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
            JSONArray a = o.getJSONArray("messages");
            for (int i = 0; i < a.length(); i++) c.messages.add(Message.from(a.getJSONObject(i)));
            return c;
        }
    }

    static final class Message {
        String role, content = "", reasoning = "", status = "complete", error = "";
        Message(String role, String content) { this.role = role; this.content = content; }
        JSONObject json() throws JSONException {
            return new JSONObject().put("role", role).put("content", content).put("reasoning", reasoning)
                    .put("status", status).put("error", error);
        }
        static Message from(JSONObject o) throws JSONException {
            Message m = new Message(o.getString("role"), o.getString("content"));
            m.reasoning = o.optString("reasoning", ""); m.status = o.optString("status", "complete");
            m.error = o.optString("error", "");
            if (m.status.equals("streaming")) {
                m.status = "interrupted"; m.error = "上次生成已中断，可重新生成。";
            }
            return m;
        }
    }
}
