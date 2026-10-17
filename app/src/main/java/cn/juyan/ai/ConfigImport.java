package cn.juyan.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class ConfigImport {
    static List<AppState.Provider> parse(String input) throws Exception {
        JSONObject root = new JSONObject(input);
        if (root.optInt("version", 1) != 1) throw new IllegalArgumentException("不支持的配置版本，请使用 version: 1。");
        JSONArray a = root.getJSONArray("providers");
        if (a.length() < 1 || a.length() > 30) throw new IllegalArgumentException("文件需包含 1 至 30 个接口。");
        List<AppState.Provider> providers = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            AppState.Provider p = AppState.Provider.from(a.getJSONObject(i));
            p.id = AppState.id(); p.name = p.name.trim(); p.key = p.key.trim(); p.baseUrl = p.baseUrl.trim();
            if (p.name.isEmpty() || p.name.length() > 40) throw new IllegalArgumentException("接口名称须为 1 至 40 字符。");
            AiProtocol.endpoint(p.baseUrl);
            if (p.key.length() > 2048 || p.key.contains("\n") || p.key.contains("\r")) throw new IllegalArgumentException("API Key 格式不正确。");
            if (p.models.isEmpty() || p.models.size() > 30) throw new IllegalArgumentException("每个接口需包含 1 至 30 个模型。");
            List<String> clean = new ArrayList<>();
            for (String item : p.models) {
                String m = item.trim();
                if (m.isEmpty() || m.length() > 160 || m.matches(".*\\s+.*")) throw new IllegalArgumentException("模型 ID 不能为空、含空格或超过 160 字符。");
                if (!clean.contains(m)) clean.add(m);
            }
            p.models.clear(); p.models.addAll(clean); providers.add(p);
        }
        return providers;
    }
}
