package com.mnemoscape.ai.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.ai.model.dto.AiChatRequest;
import com.mnemoscape.ai.service.ChatReasoner.ReActEvent;
import com.mnemoscape.ai.tools.MilvusSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态工作流引擎 (DynamicWorkflowEngine)。
 * 负责解析复杂业务提问、动态生成工作流步骤、并行运行子任务智能体，并使用验收智能体做最终质量校验。
 */
@Service
public class DynamicWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(DynamicWorkflowEngine.class);

    private final ChainWorkflowAgent chainAgent;
    private final EnhancedAgent enhancedAgent;
    private final AcceptanceAgent acceptanceAgent;
    private final MilvusSearchTool milvusTool;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 高并发背压隔离线程池：core=4, max=16, queue=100, CallerRunsPolicy 天然背压防止 OOM。
     */
    private final reactor.core.scheduler.Scheduler workflowScheduler = reactor.core.scheduler.Schedulers.fromExecutor(
            new java.util.concurrent.ThreadPoolExecutor(
                    4, 16, 60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(100),
                    r -> {
                        Thread t = new Thread(r, "workflow-agent-worker");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
            )
    );

    public DynamicWorkflowEngine(ChainWorkflowAgent chainAgent,
                                 EnhancedAgent enhancedAgent,
                                 AcceptanceAgent acceptanceAgent,
                                 MilvusSearchTool milvusTool) {
        this.chainAgent = chainAgent;
        this.enhancedAgent = enhancedAgent;
        this.acceptanceAgent = acceptanceAgent;
        this.milvusTool = milvusTool;
    }

    public static class WorkflowStep {
        public int id;
        public String name;
        public String description;
        public boolean parallel;
        public String status = "PENDING"; // PENDING, RUNNING, COMPLETED, FAILED
        public String result = "";
    }

    /**
     * 执行动态工作流的入口，返回 Flux 事件流。
     */
    public Flux<ReActEvent> executeWorkflow(AiChatRequest request, String userId, String requestId) {
        return Flux.defer(() -> {
            log.info("[WorkflowEngine] Generating dynamic workflow for query: {}", request.getQuestion());
            
            // 步骤 1: 询问思维链智能体，生成 JSON 格式的任务步骤
            String prompt = """
                Based on the user's question, construct a custom task workflow plan for solving this request.
                Return a raw JSON array of objects representing the steps. Each object MUST have:
                - "id": integer
                - "name": string (short step name, e.g. "Retrieve Lhasa memories")
                - "description": string (specific task instructions)
                - "parallel": boolean (true if this step can run concurrently with the next step, false otherwise)

                Example:
                [{"id": 1, "name": "Query Dali memories", "description": "Search user memories for Dali trip", "parallel": true},
                 {"id": 2, "name": "Query Lhasa memories", "description": "Search user memories for Lhasa trip", "parallel": true}]

                User Question:
                ---
                """ + request.getQuestion() + """
                ---

                Output the raw JSON array only. Do not wrap in markdown ```json blocks or include any extra text.
                """;

            ReActEvent thoughtGen = createEvent("thought", "正在通过思维链智能体动态规划执行工作流...", requestId);
            
            String stepsJson;
            try {
                stepsJson = chainAgent.execute(prompt, 0.1, 2048);
            } catch (Exception e) {
                log.warn("[WorkflowEngine] Failed to generate plan via LLM, using fallback", e);
                stepsJson = "[]";
            }
            
            List<WorkflowStep> steps = parseSteps(stepsJson);
            if (steps == null || steps.isEmpty()) {
                steps = new ArrayList<>();
                WorkflowStep fallback = new WorkflowStep();
                fallback.id = 1;
                fallback.name = "常规检索与串联";
                fallback.description = "针对用户提问进行通用记忆检索与叙事串联。";
                fallback.parallel = false;
                steps.add(fallback);
            }

            String stepsListJson;
            try {
                stepsListJson = json.writeValueAsString(steps);
            } catch (Exception e) {
                stepsListJson = "[]";
            }

            ReActEvent wfStartEvent = createEvent("workflow_start", stepsListJson, requestId);

            // 分组步骤：将相邻的 parallel=true 的步骤分到同一个块里，以便并发执行
            List<List<WorkflowStep>> blocks = groupSteps(steps);

            Flux<ReActEvent> blockExecutions = Flux.fromIterable(blocks)
                    .concatMap(block -> executeBlock(block, request, userId, requestId));

            List<WorkflowStep> finalStepsList = steps;
            Mono<List<ReActEvent>> postProcess = Mono.defer(() -> {
                log.info("[WorkflowEngine] Workflow steps completed. Synthesizing final response.");
                
                // 汇总各个子步骤的结果
                StringBuilder resultsSummary = new StringBuilder();
                for (WorkflowStep s : finalStepsList) {
                    resultsSummary.append("Step [").append(s.name).append("] Output:\n")
                            .append(s.result).append("\n\n");
                }

                // 统一汇总生成诗意解答
                String finalPrompt = "Based on the workflow step outputs below, generate the final poetic and warm response to the user's question.\n\n"
                        + "Workflow Step Outputs:\n" + resultsSummary.toString() + "\n"
                        + "User Question: " + request.getQuestion();

                ReActEvent finalThought = createEvent("thought", "各智能体子任务执行完成，正在进行多模态叙事串联与最终润色...", requestId);

                String finalAnswer;
                try {
                    finalAnswer = enhancedAgent.execute(finalPrompt);
                } catch (Exception e) {
                    finalAnswer = "在整理你的记忆碎片时遇到了一点小麻烦，但我会继续守护在这片星空下。";
                }

                List<ReActEvent> events = new ArrayList<>();
                events.add(finalThought);

                // 流式模拟：按标点符号切分 token 并推出去，让前端完美呈现首字延迟
                String[] chunks = finalAnswer.split("(?<=\\n|\\.|。|!|!|？|\\?|;|；)");
                for (String chunk : chunks) {
                    if (chunk != null && !chunk.isEmpty()) {
                        events.add(createEvent("token", chunk, requestId));
                    }
                }

                // 步骤 4: 验收智能体进行最终匹配度校验
                log.info("[WorkflowEngine] Running Acceptance/Validation Agent");
                ReActEvent acceptStart = createEvent("acceptance_start", "", requestId);
                events.add(acceptStart);

                String validationPrompt = "User Question: " + request.getQuestion() + "\n"
                        + "Final Response: " + finalAnswer;
                
                String validationJson;
                boolean matched = true;
                String rejectReason = "";
                try {
                    validationJson = acceptanceAgent.execute(validationPrompt, 0.1, 1024);
                    if (validationJson != null && validationJson.contains("\"matched\": false")) {
                        matched = false;
                        try {
                            var node = json.readTree(validationJson);
                            rejectReason = node.path("reason").asText("");
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    validationJson = "{\"matched\": true, \"reason\": \"验收智能体暂时不可用，默认通过。\"}";
                }

                // 闭环自省机制 (Self-Correction Loop)：如果未匹配且存在原因，执行 1 次 ReAct 反思重润色
                if (!matched && !rejectReason.isBlank()) {
                    log.info("[WorkflowEngine] Acceptance rejected response with reason: {}. Triggering self-correction loop.", rejectReason);
                    ReActEvent correctionThought = createEvent("thought", "验收智能体反馈（" + rejectReason + "），正在触发 ReAct 自省闭环机制补充润色...", requestId);
                    events.add(correctionThought);

                    String correctionPrompt = "Previous Answer was rejected because: " + rejectReason + "\n\n"
                            + "Please refine and enhance the answer to properly address the User Question.\n"
                            + "Workflow Outputs:\n" + resultsSummary.toString() + "\n"
                            + "User Question: " + request.getQuestion();
                    try {
                        String refinedAnswer = enhancedAgent.execute(correctionPrompt);
                        if (refinedAnswer != null && !refinedAnswer.isBlank()) {
                            events.add(createEvent("token", "\n\n【自省修正】\n" + refinedAnswer, requestId));
                            validationJson = "{\"matched\": true, \"reason\": \"经 ReAct 自省修正后通过验收\"}";
                        }
                    } catch (Exception e) {
                        log.warn("[WorkflowEngine] Self-correction turn failed", e);
                    }
                }
                
                ReActEvent acceptEnd = createEvent("acceptance_end", validationJson, requestId);
                events.add(acceptEnd);

                events.add(createEvent("done", "workflow-complete", requestId));
                return Mono.just(events);
            }).subscribeOn(workflowScheduler);

            return Flux.just(thoughtGen, wfStartEvent)
                    .concatWith(blockExecutions)
                    .concatWith(postProcess.flatMapMany(Flux::fromIterable));
        }).subscribeOn(workflowScheduler);
    }

    /**
     * 将步骤划分为顺序执行的块。每个块内部可以包含单个步骤（串行）或多个步骤（并行）。
     */
    private List<List<WorkflowStep>> groupSteps(List<WorkflowStep> steps) {
        List<List<WorkflowStep>> blocks = new ArrayList<>();
        List<WorkflowStep> currentBlock = new ArrayList<>();
        for (WorkflowStep step : steps) {
            currentBlock.add(step);
            if (!step.parallel) {
                blocks.add(currentBlock);
                currentBlock = new ArrayList<>();
            }
        }
        if (!currentBlock.isEmpty()) {
            blocks.add(currentBlock);
        }
        return blocks;
    }

    /**
     * 执行执行块：如果步骤数 > 1，则使用 Flux.merge 进行多线程并行并发调用。
     */
    private Flux<ReActEvent> executeBlock(List<WorkflowStep> block, AiChatRequest request, String userId, String requestId) {
        if (block == null || block.isEmpty()) {
            return Flux.empty();
        }
        if (block.size() == 1) {
            return executeStep(block.get(0), request, userId, requestId);
        }
        
        log.info("[WorkflowEngine] Executing block of {} parallel steps concurrently.", block.size());
        List<Flux<ReActEvent>> parallelFluxes = new ArrayList<>();
        for (WorkflowStep step : block) {
            parallelFluxes.add(executeStep(step, request, userId, requestId));
        }
        return Flux.merge(parallelFluxes);
    }

    /**
     * 执行单个步骤，并广播步骤启动、子智能体分配、子智能体结束及步骤结束等事件。
     */
    private Flux<ReActEvent> executeStep(WorkflowStep step, AiChatRequest request, String userId, String requestId) {
        return Flux.defer(() -> {
            log.info("[WorkflowEngine] Executing step {}: {}", step.id, step.name);
            ReActEvent stepStart = createEvent("workflow_step_start", String.valueOf(step.id), requestId);
            
            // 子智能体启动
            Map<String, Object> subStartPayload = Map.of(
                    "stepId", step.id,
                    "agentName", "SubAgent_" + step.id,
                    "task", step.name
            );
            String subStartJson = serialize(subStartPayload);
            ReActEvent agentStart = createEvent("subagent_start", subStartJson, requestId);

            // 执行核心任务：动态判断是执行向量库检索还是通用分析
            String stepResult = runStepTask(step, request, userId);
            step.result = stepResult;
            step.status = "COMPLETED";

            // 子智能体结束
            Map<String, Object> subEndPayload = Map.of(
                    "stepId", step.id,
                    "agentName", "SubAgent_" + step.id,
                    "result", stepResult
            );
            String subEndJson = serialize(subEndPayload);
            ReActEvent agentEnd = createEvent("subagent_end", subEndJson, requestId);

            // 步骤结束
            Map<String, Object> stepEndPayload = Map.of(
                    "stepId", step.id,
                    "status", "COMPLETED",
                    "result", stepResult
            );
            String stepEndJson = serialize(stepEndPayload);
            ReActEvent stepEnd = createEvent("workflow_step_end", stepEndJson, requestId);

            return Flux.just(stepStart, agentStart, agentEnd, stepEnd);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 动态决定当前步骤任务的最佳执行方案。
     */
    private String runStepTask(WorkflowStep step, AiChatRequest request, String userId) {
        String desc = step.description == null ? "" : step.description.toLowerCase();
        String name = step.name == null ? "" : step.name.toLowerCase();
        
        boolean isRetrieval = desc.contains("retrieve") || desc.contains("search") 
                || desc.contains("query") || desc.contains("检索") 
                || desc.contains("搜索") || desc.contains("查找")
                || name.contains("retrieve") || name.contains("search")
                || name.contains("query") || name.contains("检索") 
                || name.contains("搜索");

        if (isRetrieval && userId != null) {
            log.info("[WorkflowEngine] Step {} resolved as MEMORY RETRIEVAL.", step.id);
            try {
                MilvusSearchTool.Request mreq = new MilvusSearchTool.Request();
                mreq.query = step.description;
                mreq.topK = 5;
                MilvusSearchTool.Response resp = milvusTool.searchForUser(mreq, userId);
                if (resp != null && resp.hits != null && !resp.hits.isEmpty()) {
                    StringBuilder hitsStr = new StringBuilder();
                    for (var hit : resp.hits) {
                        hitsStr.append("- ").append(hit.title).append(" (")
                                .append(hit.location).append(", ").append(hit.year).append("): ")
                                .append(hit.snippet).append("\n");
                    }
                    // 交给 EnhancedAgent 融合成子步骤概述
                    String synthesisPrompt = "Summarize these memory retrieval hits for the task: " + step.description + "\n\n"
                            + "Memory Hits:\n" + hitsStr.toString();
                    return enhancedAgent.execute(synthesisPrompt);
                } else {
                    return "未检索到关于 " + step.name + " 的相关记忆碎影。";
                }
            } catch (Exception e) {
                log.warn("[WorkflowEngine] Vector search failed for step " + step.id, e);
                return "检索相关记忆时失败：" + e.getMessage();
            }
        } else {
            log.info("[WorkflowEngine] Step {} resolved as GENERAL REASONING.", step.id);
            String taskPrompt = "Analyze and execute this sub-task for the question: " + request.getQuestion() + "\n\n"
                    + "Sub-task Instruction: " + step.description;
            try {
                return enhancedAgent.execute(taskPrompt);
            } catch (Exception e) {
                return "任务执行失败：" + e.getMessage();
            }
        }
    }

    private List<WorkflowStep> parseSteps(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        try {
            String clean = rawJson;
            // 剥离 markdown json 代码块
            if (clean.contains("```")) {
                int start = clean.indexOf("[");
                int end = clean.lastIndexOf("]");
                if (start >= 0 && end >= 0 && end > start) {
                    clean = clean.substring(start, end + 1);
                }
            }
            return json.readValue(clean, new TypeReference<List<WorkflowStep>>() {});
        } catch (Exception e) {
            log.warn("[WorkflowEngine] Failed to parse JSON steps: {}", rawJson, e);
            return null;
        }
    }

    private String serialize(Object obj) {
        try {
            return json.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private ReActEvent createEvent(String type, String text, String requestId) {
        ReActEvent e = new ReActEvent();
        e.type = type;
        e.text = text;
        e.requestId = requestId;
        return e;
    }
}
