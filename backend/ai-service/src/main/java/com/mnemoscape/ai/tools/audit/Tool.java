package com.mnemoscape.ai.tools.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记某个方法是一个 AI 工具入口，会被 {@link ToolAuditAspect} 拦截审计。
 *
 * <p>P0 R3 —— AI 工具调用审计：所有 {@code @Tool} 方法被调用前后，aspect 会
 * 计算入参 / 返回值的 SHA-256 摘要、写结构化 JSON 到 logback 的
 * {@code ai-tool-audit} logger，便于后续做合规追溯和滥用检测。
 *
 * <p>使用规则：
 * <ul>
 *   <li>{@link #value()} 必须与 {@code FunctionCallback.builder().function(name, fn)}
 *       中的 {@code name} 完全一致，否则审计日志的 toolName 字段会与模型侧不匹配。</li>
 *   <li>建议标注在每个工具的 {@code public Response xxx(Request req)} 方法上；
 *       同一个类里被多次方法引用的情况会被多次审计，符合"工具调用一次记一次"的语义。</li>
 *   <li><b>方法必须同步返回</b>（R3 明确约束）：当前 10 个工具类均返回同步
 *       {@code Response}。aspect 在 {@code pjp.proceed()} 之后立刻对返回值做
 *       SHA-256 序列化——如果方法返回 {@code Mono<T>} / {@code Flux<T>}，
 *       摘要算的是响应式包装对象的字符串而非真实业务结果（且订阅前异常无法捕获）。
 *       未来若出现响应式工具，需单独实现 {@code ReactiveToolAuditAspect}
 *       （在 doOnNext / doFinally 里审计），不要复用本切面。</li>
 *   <li>参数列表任意：aspect 通过 {@code ProceedingJoinPoint.getArgs()}
 *       拿参数列表，不需要依赖 Spring 的 MethodSignature。</li>
 * </ul>
 *
 * <p>为减少对 10 个 tool 类的侵入，aspect 同时支持"未标注但处于
 * {@code com.mnemoscape.ai.tools} 包下的方法"也尝试匹配（默认关闭；当前默认
 * 必须显式标注，符合"显式优于隐式"原则）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {
    /** 工具名；模型调用的契约名，应当与 FunctionCallback.function(name, fn) 一致。 */
    String value();
}