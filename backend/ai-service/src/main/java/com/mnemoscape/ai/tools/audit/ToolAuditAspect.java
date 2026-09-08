package com.mnemoscape.ai.tools.audit;

import net.logstash.logback.argument.StructuredArguments;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * AI 工具调用审计切面（P0 R3）。
 *
 * <p>拦截任何带 {@link Tool} 注解的方法，按结构化 JSON 格式写入
 * {@code ai-tool-audit} logger（logback 配置见
 * {@code ai-service/src/main/resources/logback-spring.xml} 的
 * {@code AI_TOOL_AUDIT} appender）。写入字段：
 *
 * <ul>
 *   <li>{@code toolName}    — 来自 {@link Tool#value()}</li>
 *   <li>{@code userId}      — 从 {@link SecurityContextHolder} 拿；拿不到记
 *       {@code anonymous}（避免日志字段为空导致 ELK 索引炸掉）</li>
 *   <li>{@code argsHash}    — {@code SHA-256} 十六进制 digest of
 *       {@link org.aspectj.lang.JoinPoint#getArgs()}</li>
 *   <li>{@code resultHash}  — 成功调用时计算；异常路径下用 {@code exception}</li>
 *   <li>{@code timestamp}   — UTC ISO-8601 字符串，便于离线聚合</li>
 *   <li>{@code status}      — {@code success} / {@code failure}</li>
 *   <li>{@code ts}          — epoch millis，配合文件 appender 自带时间戳做兜底</li>
 *   <li>{@code latencyMs}   — 调用耗时（毫秒）</li>
 *   <li>{@code errorClass}  — 仅 failure 时存在，记录异常类型而非 stacktrace
 *       （stacktrace 由日志框架的 %ex 字段自动捕获，避免重复打）</li>
 * </ul>
 *
 * <p>为什么不写 DB？AI 工单调用 QPS 可达数十/秒，写库会让 PG / MySQL 抖动；
 * 改写到 logback → Filebeat / Vector 投递到 ES / ClickHouse，几乎零开销。
 * 真要合规追溯，从 ES 反查即可。
 *
 * <p>为什么不打 {@code args} 原值？很多 tool 入参里有用户原始 prompt 的切片，
 * 可能含敏感个人信息（PII），与"audit 但不泄漏"的设计目标冲突。
 */
@Aspect
@Component
public class ToolAuditAspect {

    /**
     * 专用 logger 名 — logback 里配置了 {@code AI_TOOL_AUDIT} appender，
     * additivity=false 确保审计日志只走独立文件 / 独立 ELK 索引。
     */
    private static final Logger AUDIT = LoggerFactory.getLogger("ai-tool-audit");

    @Around("@annotation(tool)")
    public Object audit(ProceedingJoinPoint pjp, Tool tool) throws Throwable {
        long startNanos = System.nanoTime();
        String toolName = tool.value();
        String userId = resolveUserId();
        String argsHash = sha256(serializeArgs(pjp.getArgs()));

        Object result;
        // 预置 failure 兜底：若 try 内抛出未被后续逻辑覆盖的异常形态，finally 读取时
        // 保证变量已初始化（definite assignment；修复模块此前无法通过编译的存量问题）
        String status = "failure";
        String resultHash = "exception";
        String errorClass = null;
        try {
            result = pjp.proceed();
            status = "success";
            resultHash = sha256(serializeResult(result));
            return result;
        } catch (Throwable t) {
            status = "failure";
            resultHash = "exception";
            errorClass = t.getClass().getSimpleName();
            // 必须把异常继续往上抛 —— audit 不能吞掉业务异常
            throw t;
        } finally {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            AUDIT.info("ai-tool-call",
                    StructuredArguments.kv("toolName", toolName),
                    StructuredArguments.kv("userId", userId),
                    StructuredArguments.kv("argsHash", argsHash),
                    StructuredArguments.kv("resultHash", resultHash),
                    StructuredArguments.kv("status", status),
                    StructuredArguments.kv("timestamp", Instant.now().toString()),
                    StructuredArguments.kv("ts", System.currentTimeMillis()),
                    StructuredArguments.kv("latencyMs", latencyMs),
                    StructuredArguments.kv("errorClass", errorClass),
                    StructuredArguments.kv("method", pjp.getSignature().toShortString())
            );
        }
    }

    /**
     * 安全地拿当前用户。AI 工具虽然在 SecurityContext 里有 principal（由
     * JwtAuthFilter 注入），但某些路径（异步任务 / 测试 stub）可能没有；
     * 直接 null 会让 ELK 字段类型不稳定，统一记 "anonymous"。
     */
    private static String resolveUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) return "anonymous";
            Object principal = auth.getPrincipal();
            if (principal == null) return "anonymous";
            return principal.toString();
        } catch (Exception e) {
            return "anonymous";
        }
    }

    /**
     * 把参数数组序列化做哈希。Object#toString 已经足够稳定（业务类都是 POJO）；
     * 用 Jackson 会带来额外的依赖耦合与异常处理负担，且审计只关心"调用形态"
     * 是否一致，不需要解析字段语义。
     */
    private static String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(64);
        sb.append('[');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(args[i] == null ? "null" : args[i].toString());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String serializeResult(Object result) {
        if (result == null) return "null";
        return result.toString();
    }

    /** 取 SHA-256 十六进制；截断到 16 字符以减少日志体积。 */
    private static String sha256(String input) {
        if (input == null) return "null";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // 16 hex chars = 64 bits，碰撞概率已足够避免误判；审计只需粗粒度"调用形态"指纹
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在任何合规 JDK 都存在；理论上不可能触发
            return "hash-error";
        }
    }

    /**
     * 仅供单测用：暴露 sha256 helper 便于断言。勿用于业务路径。
     */
    static String sha256ForTest(String input) {
        return sha256(input);
    }

    /**
     * 仅供单测用：暴露 MethodSignature 解析逻辑。当前 aspect 直接从
     * {@code Tool#value()} 拿 toolName，不依赖此方法；保留以备后续"按方法签名
     * 自动派生 toolName"的需求。
     */
    @SuppressWarnings("unused")
    static String resolveToolNameFromMethod(ProceedingJoinPoint pjp, Tool tool) {
        if (tool != null && !tool.value().isBlank()) return tool.value();
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method m = sig.getMethod();
        return m.getDeclaringClass().getSimpleName() + "#" + m.getName();
    }
}