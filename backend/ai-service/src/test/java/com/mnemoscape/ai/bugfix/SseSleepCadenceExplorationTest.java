package com.mnemoscape.ai.bugfix;

import com.mnemoscape.ai.config.AiUpstreamProperties;
import com.mnemoscape.ai.controller.ChatController;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import com.mnemoscape.ai.service.ChatReasoner;
import com.mnemoscape.ai.service.VisionDescriber;
import com.mnemoscape.ai.tools.MilvusSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Bug condition exploration test — Bug 1 (F1+F2 cadence) — POST-FIX form.
 *
 * <p>BEFORE the fix, {@code ChatController.stream(...)} explicitly called
 * {@code Thread.sleep(60)} between every 48-char window AND emitted a
 * {@code memoryContextScan} stub tool event. The token cadence was
 * therefore sleep-driven (~60 ms) — that is the bug counterexample.
 *
 * <p>AFTER the fix, the controller subscribes to the {@code Flux<String>}
 * returned by {@code ChatReasoner.streamAnswer(...)} and emits {@code event:
 * token} per chunk with NO artificial pacing. When the upstream key is
 * the placeholder, the controller MUST emit a single {@code event: error}
 * frame (carrying {@code AI_UPSTREAM_UNAVAILABLE}) — and crucially must
 * NEVER emit any {@code Thread.sleep}-paced tokens.
 *
 * <p>This test asserts:
 * <ol>
 *   <li>The stream ALWAYS finishes promptly (under 5 s) — no sleep loops.</li>
 *   <li>NO {@code event: tool_start} / {@code event: tool_end} hard-coded
 *       stub frame (the fixed controller defers tool lifecycle to the
 *       model + Spring AI tool callbacks).</li>
 *   <li>The error pathway emits exactly one {@code event: error} frame
 *       carrying {@code AI_UPSTREAM_UNAVAILABLE} when the API key is the
 *       placeholder — proving the controller no longer fabricates a
 *       successful "AI" stream.</li>
 * </ol>
 *
 * <p>Validates: Requirements 1.2, 1.3, 2.2, 2.3, 2.5.
 */
class SseSleepCadenceExplorationTest {

    @Test
    void streamMustNotEmitStubbedToolFrames_andMustErrorOnPlaceholderKey() throws Exception {
        // Build a real ChatReasoner that refuses to call the model (placeholder key).
        ChatModel chatModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ChatClient.Builder streamingBuilder = ChatClient.builder(chatModel);
        AiUpstreamProperties props = new AiUpstreamProperties();
        props.setPlaceholderKeyPrefix("nvapi-placeholder");
        Environment env = new MockEnvironment()
                .withProperty("spring.ai.openai.api-key",
                        "nvapi-placeholder-set-real-key-via-env-for-real-ai-calls");
        VisionDescriber visionDescriber = new VisionDescriber(props, env, "https://integrate.api.nvidia.com");
        MilvusSearchTool milvusTool = new MilvusSearchTool(null, null, null);
        ChatReasoner reasoner = new ChatReasoner(builder, streamingBuilder, props, env, visionDescriber, milvusTool);
        ChatController controller = new ChatController(reasoner);

        AiChatRequest req = new AiChatRequest();
        req.setQuestion("帮我整理本月的情绪轨迹");
        req.setLocale("zh-CN");
        req.setContext(List.of());

        // Recording emitter: capture the sequence of event NAMES sent.
        List<String> sentEvents = new ArrayList<>();
        long startNs = System.nanoTime();
        CountDownLatch done = new CountDownLatch(1);

        SseEmitter recordingEmitter = new SseEmitter(120_000L) {
            @Override
            public void send(SseEmitter.SseEventBuilder builder) {
                // SseEventBuilderImpl holds a private `name` field. We reach in
                // via reflection — Spring does not override toString or expose
                // a getter, so this is the only stable way to identify which
                // event the controller is emitting in unit tests.
                String name = extractEventName(builder);
                if (name == null) {
                    sentEvents.add("other");
                } else {
                    sentEvents.add(name);
                }
            }
            @Override public void complete() { done.countDown(); }
            @Override public void completeWithError(Throwable ex) { done.countDown(); }
        };

        Method streamMethod = ChatController.class.getDeclaredMethod(
                "stream", SseEmitter.class, AiChatRequest.class, String.class);
        streamMethod.setAccessible(true);
        streamMethod.invoke(controller, recordingEmitter, req, (String) null);

        if (!done.await(5, TimeUnit.SECONDS)) {
            fail("ChatController.stream did not complete within 5s — likely "
                    + "blocking on Thread.sleep loops. That is the unfixed-code "
                    + "counterexample.");
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        // POST-FIX INVARIANT 1: complete in well under the 60-ms-per-chunk budget.
        assertTrue(
                elapsedMs < 3_000L,
                () -> "Stream took " + elapsedMs + "ms — implies sleep-paced loop. "
                        + "Required fix: subscribe to ChatClient.stream(...) Flux."
        );

        // POST-FIX INVARIANT 2: NO hard-coded memoryContextScan tool frames.
        assertEquals(0L,
                sentEvents.stream().filter(e -> e.startsWith("tool_")).count(),
                "Counterexample: controller emitted hard-coded tool_start/tool_end "
                        + "frames. Required fix: tool lifecycle is owned by the model "
                        + "via Spring AI FunctionCallback, not by the controller.");

        // POST-FIX INVARIANT 3: there is at least one 'error' frame because the
        // upstream key is the placeholder. The unfixed code never emits errors —
        // it fabricates a successful 'token' stream, which is the bug.
        assertTrue(sentEvents.contains("error"),
                () -> "Expected an SSE 'error' frame on placeholder-key conditions. "
                        + "Sent events: " + sentEvents);
    }

    /**
     * Spring's {@code SseEventBuilderImpl} accumulates raw SSE text into a
     * private {@code StringBuilder sb} field as the chained {@code .name(..)}
     * / {@code .data(..)} calls happen. The field stores fragments like
     * {@code "event:meta\n"} we can scan. There is no public getter, no
     * toString override — this reflection step is the stable way to identify
     * the event name in a unit test.
     */
    private static String extractEventName(SseEmitter.SseEventBuilder builder) {
        String raw = sniffBuilderText(builder);
        if (raw == null) return null;
        for (String marker : new String[]{
                "token", "meta", "error", "done", "tool_start", "tool_end"
        }) {
            if (raw.contains("event:" + marker)) return marker;
        }
        return null;
    }

    private static String sniffBuilderText(SseEmitter.SseEventBuilder builder) {
        // Spring's SseEventBuilderImpl flushes the in-progress StringBuilder
        // into `dataToSend` as soon as data(...) is called — so by the time
        // we see the builder via send(), `sb` is usually null and the event
        // metadata lives in the DataWithMediaType records inside `dataToSend`.
        // We collect every readable text fragment we can find and scan it.
        StringBuilder out = new StringBuilder();
        Class<?> c = builder.getClass();
        while (c != null) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(builder);
                    appendReadable(out, v);
                } catch (Exception ignored) { /* skip */ }
            }
            c = c.getSuperclass();
        }
        if (out.length() > 0) return out.toString();
        try {
            return String.valueOf(builder.build());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendReadable(StringBuilder out, Object v) {
        if (v == null) return;
        if (v instanceof CharSequence cs) {
            out.append(cs).append('\n');
            return;
        }
        if (v instanceof Iterable<?> it) {
            for (Object element : it) {
                appendReadable(out, element);
            }
            return;
        }
        // DataWithMediaType is a record-style class; iterate its fields.
        Class<?> ec = v.getClass();
        String pkg = ec.getPackage() == null ? "" : ec.getPackage().getName();
        if (pkg.startsWith("org.springframework.web")) {
            while (ec != null && !ec.equals(Object.class)) {
                for (java.lang.reflect.Field f : ec.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object inner = f.get(v);
                        if (inner instanceof CharSequence cs) out.append(cs).append('\n');
                    } catch (Exception ignored) { /* skip */ }
                }
                ec = ec.getSuperclass();
            }
        }
    }
}
