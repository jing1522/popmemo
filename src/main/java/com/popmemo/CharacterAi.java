package com.popmemo;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 调 DeepSeek（OpenAI 兼容接口）给一批事项各编一句角色台词。
 * 后台线程跑，回调一定回到主线程；任何失败都返回空表，调用方就当没这回事。
 */
class CharacterAi {

    interface Callback {
        void onLines(List<String> lines);
    }

    /** 和 MainActivity.CHAR_STYLES 同顺序。 */
    private static final String[] STYLE_DESC = {
            "傲娇，嘴硬心软，话里带刺但其实在关心",
            "温柔，轻声细语地鼓励",
            "严厉，直接、命令式、不废话",
            "中二，把日常小事说成史诗级使命",
    };

    static void fetchLines(String key, String charName, int style, List<String> examples,
                           List<Memo> memos, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<String> lines = tryFetch(key, charName, style, examples, memos);
            main.post(() -> callback.onLines(lines));
        }).start();
    }

    private static List<String> tryFetch(String key, String charName, int style,
                                         List<String> examples, List<Memo> memos) {
        List<String> empty = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            StringBuilder system = new StringBuilder()
                    .append("你在扮演「").append(charName)
                    .append("」，用这个角色对用户说话。口吻：")
                    .append(STYLE_DESC[Math.min(style, STYLE_DESC.length - 1)])
                    .append("。给用户的每一条待办各写一句 TA 会说的提醒/催促，口语化，每条不超过 40 字，")
                    .append("原样保留事项的说法。严格按输入顺序输出 JSON：{\"lines\":[\"第一句\",\"第二句\"]}");
            if (!examples.isEmpty()) {
                system.append("。用户喜欢的例句，学这个味道：「")
                        .append(join(examples))
                        .append("」");
            }

            StringBuilder user = new StringBuilder("待办清单：\n");
            for (int i = 0; i < memos.size(); i++) {
                user.append(i + 1).append(". ").append(memos.get(i).text).append('\n');
            }

            JSONObject body = new JSONObject();
            body.put("model", "deepseek-chat");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system.toString()));
            messages.put(new JSONObject().put("role", "user").put("content", user.toString()));
            body.put("messages", messages);
            body.put("temperature", 1.3);
            body.put("max_tokens", 400);
            body.put("response_format", new JSONObject().put("type", "json_object"));

            conn = (HttpURLConnection) new URL("https://api.deepseek.com/chat/completions")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream stream = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder raw = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    raw.append(line);
                }
            }
            if (code != 200) {
                return empty;
            }

            String content = new JSONObject(raw.toString())
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            JSONArray lines = new JSONObject(content).getJSONArray("lines");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < lines.length(); i++) {
                out.add(lines.getString(i).trim());
            }
            return out;
        } catch (Exception e) {
            return empty;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > 0) {
                sb.append("」「");
            }
            sb.append(line);
        }
        return sb.toString();
    }
}
