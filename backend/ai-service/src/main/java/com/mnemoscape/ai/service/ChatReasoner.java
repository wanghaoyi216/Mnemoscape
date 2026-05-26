package com.mnemoscape.ai.service;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.exception.AiUpstreamException;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真实 LLM 对话内核（v2）。
 *
 * <p>取代 v1 的"模板拼接"实现：所有调用都通过 Spring AI 的
 * {@link ChatClient} 走到 NVIDIA Integrate API（OpenAI-兼容协议，模型
 * MiniMax-M2.7）。同步路径用 {@code .call()}；流式路径返回 {@link Flux}
 * 让 {@code ChatController} 直接桥接到 SSE，无需任何 {@code Thread.sleep}。
 *
 * <p>三道屏障保证安全降级：
 * <ol>
 *   <li>Prompt-injection guard — 调用模型 <i>之前</i> 把"忽略前面指令"等模式直接拦下，
 *       回中英文双语兜底文案。</li>
 *   <li>占位 key 检测 — {@code application.yml} 里的占位符前缀仍存在时，
 *       立即抛 {@link AiUpstreamException}，前端拿到 502 + AI_UPSTREAM_UNAVAILABLE。</li>
 *   <li>调用层 try/catch — 把上游 4xx/5xx/超时 / IO 异常归一化成
 *       {@link AiUpstreamException}（reason 区分 AUTH / TIMEOUT / UPSTREAM_ERROR）。</li>
 * </ol>
 */
@Service
public class ChatReasoner {

    private static final Logger log = LoggerFactory.getLogger(ChatReasoner.class);

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final String[] PLAN_KEYS = {
            "找", "检索", "搜索", "匹配", "共鸣", "路径", "路线", "对比", "相似",
            "哪些", "哪个", "谁", "find", "search", "match", "resonance", "route",
            "compare", "similar", "who", "which", "recommend"
    };

    public enum Intent { CHAT, PLAN }

    private final ChatClient chatClient;
    private final AiUpstreamProperties props;
    private final String configuredApiKey;

    public ChatReasoner(@Qualifier("mnemoscapeChatClientBuilder") ChatClient.Builder builder,
                        AiUpstreamProperties props,
                        org.springframework.core.env.Environment env) {
        this.chatClient = builder.build();
        this.props = props;
        this.configuredApiKey = env.getProperty("spring.ai.openai.api-key", "");
    }

    /* ---------------- Intent classification (kept identical to v1 for plan UI) ---- */

    /**
     * 意图识别 v2 — 与前端 AiMascotDock 保持一致的保守策略：
     *
     *  • 任何寒暄 / 自我介绍 / 短句 → CHAT，绝不规划。
     *  • 命中"具体的检索/分析"词（去掉了过于泛化的 谁/who/which）且长度 ≥ 8 → PLAN。
     *  • 含 4 位年份 + 长度 > 12 → PLAN。
     *
     *  之前的版本把"谁/who/which"也算 PLAN，导致"你是谁?"被错误规划。
     */
    public Intent classify(String question) {
        if (question == null) return Intent.CHAT;
        String q = question.trim();
        if (q.isEmpty()) return Intent.CHAT;

        // 寒暄白名单
        String low = q.toLowerCase(Locale.ROOT);
        String[] greetings = {
                "你好", "您好", "早上好", "晚上好", "你是谁", "自我介绍", "介绍一下你自己",
                "hi", "hello", "hey", "who are you", "introduce yourself",
                "thanks", "thank you", "谢谢", "感谢"
        };
        for (String g : greetings) {
            if (low.startsWith(g) || low.equals(g)) return Intent.CHAT;
        }

        // 短句一律 CHAT（中文按字符数；保守起见用 trim 后的长度）
        if (q.replaceAll("\\s+", "").length() < 8) return Intent.CHAT;

        // 真正的检索/规划信号
        String[] planSignals = {
                "帮我找", "帮我检索", "帮我搜索", "帮我整理", "帮我推荐",
                "检索", "搜索", "匹配", "共鸣", "路径", "路线",
                "对比", "相似", "推荐一段", "推荐一个",
                "find my", "search my", "look up", "compare", "similar",
                "recommend", "summarize", "analyse", "analyze"
        };
        for (String kw : planSignals) {
            if (low.contains(kw.toLowerCase(Locale.ROOT))) return Intent.PLAN;
        }
        Matcher m = YEAR.matcher(low);
        if (m.find() && q.length() > 12) return Intent.PLAN;
        return Intent.CHAT;
    }

    public List<String> buildPlan(String question, boolean zh) {
        List<String> plan = new ArrayList<>();
        if (zh) {
            plan.add("解析时间/地点/情绪关键词");
            plan.add("检索个人记忆向量库");
            plan.add("匹配相似情感节点（脱敏）");
            plan.add("组装多模态结果卡");
        } else {
            plan.add("Parse time, place, emotion keywords");
            plan.add("Search personal memory vectors");
            plan.add("Match similar emotion nodes (anonymized)");
            plan.add("Assemble multimodal cards");
        }
        String low = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (low.matches(".*(路径|路线|route|map|atlas).*")) {
            plan.add(3, zh ? "比对地理轨迹重叠" : "Compare geographic overlap");
        }
        return plan;
    }

    /* ---------------- Real LLM calls ---- */

    /** 同步调用：返回完整答案。被 {@code POST /chat} 使用。 */
    public String generateAnswer(AiChatRequest req) {
        String guard = checkInjection(req);
        if (guard != null) return guard;
        ensureRealKeyOrThrow();

        try {
            String userPrompt = buildUserPrompt(req);
            return chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (AiUpstreamException e) {
            throw e;
        } catch (Exception e) {
            throw classify(e);
        }
    }

    /**
     * 流式调用：把 ChatClient 的 token Flux 直接返回，由 controller 桥到 SSE。
     *
     * <p>注意：此处不做"等待全部完成"的 join —— controller 收到第一个 chunk 就开始
     * 推 {@code event: token}，节奏完全由模型真实流速驱动（不再用 Thread.sleep）。
     *
     * <p>把 key 校验与构建 prompt 包在 {@link Flux#defer} 里，让 {@link AiUpstreamException}
     * 走 Flux 的错误信号，而不是在订阅之前就以同步异常的形式逃出 — 这样
     * controller 的 {@code .doOnError} 才能正确把 missing-key 翻译成
     * {@code event: error} 帧而不是 500。
     */
    public Flux<String> streamAnswer(AiChatRequest req) {
        String guard = checkInjection(req);
        if (guard != null) {
            return Flux.just(guard);
        }
        return Flux.defer(() -> {
            try {
                ensureRealKeyOrThrow();
                String userPrompt = buildUserPrompt(req);
                return chatClient.prompt()
                        .user(userPrompt)
                        .stream()
                        .content()
                        .onErrorMap(e -> e instanceof AiUpstreamException ? e : classify(e));
            } catch (AiUpstreamException e) {
                return Flux.<String>error(e);
            } catch (Exception e) {
                return Flux.<String>error(classify(e));
            }
        });
    }

    /* ---------------- internal helpers ---- */

    private void ensureRealKeyOrThrow() {
        if (configuredApiKey == null
                || configuredApiKey.isBlank()
                || configuredApiKey.startsWith(props.getPlaceholderKeyPrefix())) {
            log.warn(
                "[AI] NVIDIA_API_KEY env var is NOT set on the host — current api-key "
              + "is the application.yml placeholder default (prefix '{}'). This is "
              + "*not* a hardcoded key; it only exists because Spring AI requires a "
              + "non-empty value to wire up the OpenAiAutoConfiguration beans at "
              + "startup. To enable real LLM calls: "
              + "(1) export NVIDIA_API_KEY on the host shell, OR "
              + "(2) put NVIDIA_API_KEY=nvapi-xxx into backend/.env.workpc and re-run "
              + "Start-LocalDevServices.ps1, OR "
              + "(3) set it in your docker-compose .env file. "
              + "Refusing to send the placeholder upstream; falling back to structured "
              + "AI_UPSTREAM_UNAVAILABLE.",
                props.getPlaceholderKeyPrefix());
            throw new AiUpstreamException(
                    AiUpstreamException.Reason.MISSING_KEY,
                    "NVIDIA_API_KEY environment variable is not set on the server. "
                  + "Set it via env (PowerShell: $env:NVIDIA_API_KEY='nvapi-xxx', "
                  + "Bash: export NVIDIA_API_KEY=nvapi-xxx, or backend/.env.workpc), "
                  + "then restart ai-service.");
        }
    }

    /**
     * 防注入：在调用模型之前直接拦截。命中即返回 {@code String}，否则返回 {@code null}。
     * <p>该兜底文案与 v1 保持一致（regression-prevention 3.9）。
     */
    public String checkInjection(AiChatRequest req) {
        if (req == null || req.getQuestion() == null) return null;
        String low = req.getQuestion().toLowerCase(Locale.ROOT);
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");
        if (low.contains("ignore previous") || low.contains("忽略之前")
                || low.contains("system prompt") || low.contains("系统提示词")) {
            return zh
                ? "检测到异常指令，星空使者无法执行此操作。请尝试用自然语言描述你想找的记忆。"
                : "Suspicious instruction detected. Try describing the memory you want to find in plain language.";
        }
        return null;
    }

    /** 把用户问题 + 记忆 digest + locale 拼成模型 prompt。 */
    private String buildUserPrompt(AiChatRequest req) {
        boolean zh = req.getLocale() == null || req.getLocale().startsWith("zh");
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "用户语种: zh\n" : "User locale: en\n");
        if (req.getContext() != null && !req.getContext().isEmpty()) {
            sb.append(zh ? "以下是用户授权的近 N 条记忆摘要 (JSON-like):\n"
                         : "Authorized recent memory digests (JSON-like):\n");
            int i = 0;
            for (AiChatRequest.MemoryDigest d : req.getContext()) {
                sb.append("[")
                  .append(i++)
                  .append("] id=").append(safe(d.getId()))
                  .append(", title=").append(safe(d.getTitle()))
                  .append(", location=").append(safe(d.getLocation()))
                  .append(", year=").append(d.getYear() == null ? "" : d.getYear())
                  .append(", snippet=").append(safe(d.getSnippet()))
                  .append("\n");
            }
        } else {
            sb.append(zh ? "(暂无记忆 context — 必要时用 milvusSearchTool 主动检索)\n"
                         : "(no memory context — call milvusSearchTool when needed)\n");
        }
        sb.append("\n").append(zh ? "用户问题:\n" : "User question:\n").append(req.getQuestion());
        return sb.toString();
    }

    /**
     * 把任意上游异常翻译成结构化 {@link AiUpstreamException}，
     * controller / SSE 层据此映射 502/503 与 SSE error 帧。
     */
    private AiUpstreamException classify(Throwable t) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        String low = msg == null ? "" : msg.toLowerCase(Locale.ROOT);
        AiUpstreamException.Reason reason;
        if (low.contains("401") || low.contains("403") || low.contains("unauthor")) {
            reason = AiUpstreamException.Reason.AUTHENTICATION;
        } else if (low.contains("timeout") || low.contains("timed out")) {
            reason = AiUpstreamException.Reason.TIMEOUT;
        } else if (low.contains("429") || low.contains("5") || low.contains("connection")) {
            reason = AiUpstreamException.Reason.UPSTREAM_ERROR;
        } else {
            reason = AiUpstreamException.Reason.UNKNOWN;
        }
        return new AiUpstreamException(reason, props.getFallbackMessage()
                + (msg == null ? "" : " (" + msg + ")"), t);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
