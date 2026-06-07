package com.mnemoscape.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网络检索工具（DuckDuckGo Instant Answer + Lite HTML 兜底）。
 *
 * <p>触发场景：用户问的内容明显不在自己记忆库 / 知识图谱里，而是要求外部信息
 * （天气、汇率、知识、地名解释、人物简介、最新事件等）。星空使者会用本工具
 * 拉一组 snippet 做参考，再合成中文回答。
 *
 * <p>实现：
 * <ol>
 *   <li>先请求 {@code https://api.duckduckgo.com/?q=...&format=json} —— DDG 有
 *       免 key 的 Instant Answer JSON，对常见知识词条会直接返回 Abstract。</li>
 *   <li>当 IA 没命中（很常见），降级到 {@code https://html.duckduckgo.com/html/?q=...}
 *       Lite HTML 页面，正则抠出前 N 条结果（title + snippet + url）。</li>
 * </ol>
 *
 * <p>注意：本工具受 {@code mnemoscape.ai.tools.web-search.enabled} 开关控制
 * （默认 true）；线下环境如果连不出去可以关闭防止超时阻塞 chat。
 */
@Configuration
public class WebSearchTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    public static class Request {
        /** 必填：自然语言搜索词 */
        public String query;
        /** 想要的结果数；默认 5，最多 10 */
        public Integer maxResults;
    }

    public static class Result {
        public String title;
        public String snippet;
        public String url;
        public String source; // "ddg-ia" | "ddg-html"
    }

    public static class Response {
        public String abstractText;       // DDG IA 的 Abstract（如果命中）
        public String abstractSource;
        public String abstractUrl;
        public List<Result> results = new ArrayList<>();
        public boolean degraded = false;
        public String message;
    }

    @Value("${mnemoscape.ai.tools.web-search.enabled:true}")
    private boolean enabled;

    /** 单例 HTTP 客户端 — JDK HttpClient（v2.2.1 同款，避开 RestClient 转换器问题）。 */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    @Bean
    public FunctionCallback webSearchToolCallback() {
        Function<Request, Response> fn = this::search;
        return FunctionCallback.builder()
                .description("Search the public internet (DuckDuckGo) for information that "
                        + "is NOT in the user's memory library — e.g. weather, news, "
                        + "general knowledge, place lookups, encyclopedia. Returns a list "
                        + "of snippets and an optional abstract. Do NOT use for the user's "
                        + "personal memories — use milvusSearchTool / memoryDetailTool for "
                        + "those.")
                .function("webSearchTool", fn)
                .inputType(Request.class)
                .build();
    }

    public Response search(Request req) {
        Response resp = new Response();
        if (!enabled) {
            resp.degraded = true;
            resp.message = "web search disabled";
            return resp;
        }
        if (req == null || req.query == null || req.query.isBlank()) {
            resp.degraded = true;
            resp.message = "query required";
            return resp;
        }
        int max = req.maxResults == null ? 5 : Math.max(1, Math.min(10, req.maxResults));
        String q = req.query.trim();

        // 1) Instant Answer
        try {
            String url = "https://api.duckduckgo.com/?q=" + urlEncode(q) + "&format=json&no_html=1&skip_disambig=1";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mnemoscape-EchoEnvoy/1.0")
                    .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(r.body(), Map.class);
                Object abs = body.get("AbstractText");
                if (abs != null && !String.valueOf(abs).isBlank()) {
                    resp.abstractText = String.valueOf(abs);
                    resp.abstractSource = String.valueOf(body.getOrDefault("AbstractSource", ""));
                    resp.abstractUrl = String.valueOf(body.getOrDefault("AbstractURL", ""));
                }
                Object related = body.get("RelatedTopics");
                if (related instanceof List<?> list) {
                    for (Object o : list) {
                        if (resp.results.size() >= max) break;
                        if (!(o instanceof Map<?, ?> mWild)) continue;
                        @SuppressWarnings("unchecked")
                        Map<String, Object> mm = (Map<String, Object>) mWild;
                        Object text = mm.get("Text");
                        Object firstUrl = mm.get("FirstURL");
                        if (text == null || firstUrl == null) continue;
                        Result res = new Result();
                        String t = String.valueOf(text);
                        int dot = t.indexOf(" - ");
                        res.title = dot > 0 ? t.substring(0, dot) : t;
                        res.snippet = dot > 0 ? t.substring(dot + 3) : "";
                        res.url = String.valueOf(firstUrl);
                        res.source = "ddg-ia";
                        resp.results.add(res);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("DDG IA failed (will fall back to HTML): {}", e.getMessage());
        }

        // 2) HTML fallback (only if IA didn't fill enough results)
        if (resp.results.size() < max) {
            try {
                String url = "https://html.duckduckgo.com/html/?q=" + urlEncode(q);
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                              + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        .GET().build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (r.statusCode() == 200) {
                    parseHtmlResults(r.body(), resp.results, max);
                } else {
                    if (resp.results.isEmpty()) {
                        resp.degraded = true;
                        resp.message = "DDG HTML returned " + r.statusCode();
                    }
                }
            } catch (Exception e) {
                log.warn("webSearchTool HTML fallback failed: {}", e.toString());
                if (resp.results.isEmpty()) {
                    resp.degraded = true;
                    resp.message = "DDG HTML unreachable: " + e.getClass().getSimpleName();
                }
            }
        }

        return resp;
    }

    /** 极简 HTML 抠词 — 不依赖 Jsoup，避免给 ai-service 多带依赖。 */
    private static final Pattern RESULT_BLOCK = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.+?)</a>.*?(?:class=\"result__snippet\"[^>]*>(.+?)</a>|class=\"result__snippet\"[^>]*>(.+?)</div>)",
            Pattern.DOTALL);

    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private void parseHtmlResults(String html, List<Result> sink, int max) {
        Matcher m = RESULT_BLOCK.matcher(html);
        while (m.find() && sink.size() < max) {
            String href = m.group(1);
            String title = stripTags(m.group(2));
            String snippet = stripTags(m.group(3) == null ? m.group(4) : m.group(3));
            String url = decodeDdgRedirect(href);
            if (url == null || url.isBlank()) continue;
            Result r = new Result();
            r.title = title;
            r.snippet = snippet;
            r.url = url;
            r.source = "ddg-html";
            sink.add(r);
        }
    }

    private static String stripTags(String s) {
        if (s == null) return "";
        String t = TAG.matcher(s).replaceAll("");
        t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&#x27;", "'");
        return WS.matcher(t).replaceAll(" ").trim();
    }

    /** DDG html 把所有结果 url 套了一层 /l/?uddg=...&kh=... ，需要解出来。 */
    private static String decodeDdgRedirect(String href) {
        if (href == null) return null;
        // 支持相对路径 //duckduckgo.com/l/?uddg=...
        int idx = href.indexOf("uddg=");
        if (idx < 0) return href;
        String tail = href.substring(idx + 5);
        int amp = tail.indexOf('&');
        String enc = amp >= 0 ? tail.substring(0, amp) : tail;
        try {
            return java.net.URLDecoder.decode(enc, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return href;
        }
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    public static Map<String, Object> resultToMap(Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", r.title);
        m.put("snippet", r.snippet);
        m.put("url", r.url);
        m.put("source", r.source);
        return m;
    }
}
