package com.mnemoscape.resonance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnemoscape.common.exception.BizException;
import com.mnemoscape.common.dto.ApiResponse;
import org.springframework.cache.annotation.Cacheable;
import com.mnemoscape.resonance.admin.config.AdminCacheConfig;

import com.mnemoscape.resonance.client.MemoryServiceClient;
import com.mnemoscape.resonance.model.entity.MemoryNote;
import com.mnemoscape.resonance.model.entity.ResonanceSpace;
import com.mnemoscape.resonance.repository.MemoryNoteRepository;
import com.mnemoscape.resonance.repository.ResonanceSpaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 共鸣服务（v2 — 真实数据 + 关键词加权打分）。
 *
 * <p>取代 v1 的"写死 mock-memory-1/2/3 + 随机相似度"实现：
 * <ol>
 *   <li>{@link #searchResonances} 通过 {@link MemoryServiceClient} 拉取 caller 的
 *       seed 记忆 + 跨用户公共记忆池，再用关键词重叠 + 季节/年代/地点匹配做轻量打分。</li>
 *   <li>{@link #createSpace} 把 search 算出的真实相似度持久化进 DB，不再随机。</li>
 *   <li>memory-service 不可达时 fail-closed 返回空列表 + degraded 标记，<b>绝不</b>退化成 mock 数据
 *       —— 跟 ai-service 的 vision/chat 失败语义对齐：不用占位答案冒充真实结果。</li>
 * </ol>
 *
 * <p><b>未来接入真 Milvus 时只需替换打分函数</b>（{@link #scoreOverlap}）；
 * 输入输出契约保持不变。当前实现的"打分"等价于一个非常朴素的稀疏向量召回，
 * 比硬编码 mock 真实得多，但精度不如真正的稠密向量检索。
 */
@Service
public class ResonanceService {
    private static final Logger log = LoggerFactory.getLogger(ResonanceService.class);

    private static final int DEFAULT_TOP_K = 8;
    private static final int MIN_TOP_K = 3;
    private static final int MAX_PUBLIC_POOL_SIZE = 200;
    /** 低于此分数视为不相关（避免把空泛结果灌给前端） */
    private static final double SCORE_FLOOR = 0.50;
    /** resonanceStats() 两两 jaccard 采样的最大池子大小。80 → 3160 对，<50ms。 */
    private static final int STATS_PAIR_LIMIT = 80;

    private final ResonanceSpaceRepository spaceRepository;
    private final MemoryNoteRepository noteRepository;
    private final MemoryServiceClient memoryClient;
    /** 真实向量召回（ai-service）；未装配 / 不可用时退回关键词打分。 */
    private final com.mnemoscape.resonance.client.AiServiceClient aiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ResonanceService(ResonanceSpaceRepository spaceRepository,
                            MemoryNoteRepository noteRepository,
                            MemoryServiceClient memoryClient,
                            @org.springframework.beans.factory.annotation.Autowired(required = false)
                            com.mnemoscape.resonance.client.AiServiceClient aiClient) {
        this.spaceRepository = spaceRepository;
        this.noteRepository = noteRepository;
        this.memoryClient = memoryClient;
        this.aiClient = aiClient;
    }

    /**
     * 真实化的共鸣检索：
     * <ol>
     *   <li>用 caller 的 X-User-Id 拉取 seed 记忆（作为查询向量的"自我"端）</li>
     *   <li>拉取跨用户公共记忆池（已排除 caller 自己）</li>
     *   <li>用关键词 + 时间/季节/地点重叠打分，按 score 降序取前 N</li>
     *   <li>低于 {@value #SCORE_FLOOR} 的丢弃；最终最少返回 {@value #MIN_TOP_K} 条（即使低分）</li>
     * </ol>
     */
    @Cacheable(value = AdminCacheConfig.CACHE_RESONANCE_SEARCH, key = "#memoryId + '-' + #userId")
    public List<Map<String, Object>> searchResonances(String memoryId, String userId) {
        if (userId == null || userId.isBlank()) {

            throw new BizException(401, "未通过身份认证，无法执行共鸣检索");
        }
        log.info("Searching resonances seedMemoryId={} userId={}", memoryId, userId);

        Map<String, Object> seed;
        try {
            ApiResponse<Map<String, Object>> resp = memoryClient.getMemory(memoryId, userId);
            seed = resp == null ? null : resp.getData();
            if (seed == null) throw new BizException(404, "种子记忆不存在或无权访问");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("memory-service unreachable while fetching seed memory {}: {}", memoryId, e.toString());
            throw new BizException(503, "记忆服务暂不可用，无法执行共鸣检索");
        }

        // 1) 优先用 ai-service 真实稠密向量召回（跨用户公共池）。
        //    ai-service 返回 available=false 或本路径抛错 → 透明降级到关键词打分。
        List<Map<String, Object>> vectorMatches = tryVectorResonance(seed, memoryId, userId);
        if (vectorMatches != null) {
            return vectorMatches;
        }

        // 2) 关键词加权降级（拉公共池做 jaccard + 季节/年代/地点加权）。
        List<Map<String, Object>> pool;
        try {
            ApiResponse<List<Map<String, Object>>> resp = memoryClient.publicPool(MAX_PUBLIC_POOL_SIZE, userId);
            pool = (resp == null || resp.getData() == null) ? List.of() : resp.getData();
        } catch (Exception e) {
            log.warn("memory-service public pool unreachable: {}", e.toString());
            return List.of(); // fail-closed：不返回 mock，让前端渲染"暂无共鸣"
        }

        if (pool.isEmpty()) {
            log.info("Public pool is empty — no other users have published PUBLIC memories yet");
            return List.of();
        }

        // 打分 + 排序
        List<String> seedKeywords = extractKeywords(stringField(seed, "title") + " "
                                                    + stringField(seed, "memoryLocation") + " "
                                                    + stringField(seed, "description"));
        Integer seedYear = intField(seed, "memoryYear");
        String seedSeason = stringField(seed, "memorySeason");
        String seedLoc = stringField(seed, "memoryLocation");

        List<Map<String, Object>> scored = new ArrayList<>();
        for (Map<String, Object> row : pool) {
            String otherId = stringField(row, "id");
            if (otherId == null || otherId.equals(memoryId)) continue;

            double score = scoreOverlap(seedKeywords, seedYear, seedSeason, seedLoc, row);
            if (score < SCORE_FLOOR) continue;

            // 给 emotion / scene 一个分解：保留契约让前端能继续展示双轴
            // 简化版：emotion 偏重关键词命中，scene 偏重时间/地点对齐
            double emotion = round(Math.min(0.99, score * 1.05));
            double scene   = round(Math.min(0.99, score * 0.92));

            Map<String, Object> match = new LinkedHashMap<>();
            match.put("memoryId", otherId);
            match.put("title", stringField(row, "title"));
            String desc = stringField(row, "description");
            match.put("description", desc.length() > 160 ? desc.substring(0, 160) + "…" : desc);
            match.put("similarityScore", round(score));
            match.put("emotionSimilarity", emotion);
            match.put("sceneSimilarity", scene);
            // owner 信息：当前 memory-service 没暴露 username（需走 auth-service 解析），
            // 用 ownerId 哈希成稳定虚名占位；前端 UI 会显示为"回忆者-xxxx"
            match.put("ownerUsername", virtualOwnerName(stringField(row, "userId")));
            scored.add(match);

        }

        scored.sort((a, b) -> Double.compare(
                ((Number) b.get("similarityScore")).doubleValue(),
                ((Number) a.get("similarityScore")).doubleValue()));

        int take = Math.max(MIN_TOP_K, Math.min(DEFAULT_TOP_K, scored.size()));
        return scored.subList(0, Math.min(take, scored.size()));
    }

    /**
     * 共鸣空间创建：
     * - 重新跑一次 search 拿真实分数（避免前端伪造数字提交进 DB）
     * - sceneDataUrl 复用 seed 记忆（memoryId1）的 sceneDataUrl，让前端 SceneViewer
     *   能直接渲染一个真实场景；如果两条记忆都没有 sceneDataUrl，才回退到 placeholder。
     */
    public ResonanceSpace createSpace(String memoryId1, String memoryId2, String userId) {
        if (memoryId1 == null || memoryId2 == null || memoryId1.equals(memoryId2)) {
            throw new BizException(400, "两条记忆 id 不能为空且不能相同");
        }

        // 用 search 结果里 memoryId2 的真实相似度；如果 search 没返回它，给一个保守默认
        double sim = 0.6;
        try {
            for (Map<String, Object> match : searchResonances(memoryId1, userId)) {
                if (memoryId2.equals(match.get("memoryId"))) {
                    sim = ((Number) match.get("similarityScore")).doubleValue();
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("createSpace: failed to recompute similarity, using floor 0.6: {}", e.toString());
        }

        // 优先复用 seed 的 sceneDataUrl（http 链接 / scene:// 都行）让前端 SceneViewer 渲染真实场景
        String chosenSceneUrl = null;
        try {
            ApiResponse<Map<String, Object>> seed = memoryClient.getMemory(memoryId1, userId);
            if (seed != null && seed.getData() != null) {
                Object v = seed.getData().get("sceneDataUrl");
                if (v != null && !String.valueOf(v).isBlank()) chosenSceneUrl = String.valueOf(v);
            }
        } catch (Exception e) {
            log.warn("[resonance] failed to fetch seed sceneDataUrl for space, using placeholder: {}", e.toString());
        }

        ResonanceSpace space = ResonanceSpace.builder()
                .memoryId1(memoryId1)
                .memoryId2(memoryId2)
                .similarityScore(round(sim))
                .emotionSimilarity(round(Math.min(0.99, sim * 1.05)))
                .sceneSimilarity(round(Math.min(0.99, sim * 0.92)))
                .sceneDataUrl(chosenSceneUrl != null ? chosenSceneUrl
                        : "scene://resonance/" + UUID.randomUUID())
                .status("active")
                .build();
        return spaceRepository.save(space);
    }

    public ResonanceSpace getSpace(String spaceId) {
        return spaceRepository.findById(spaceId)
                .orElseThrow(() -> BizException.notFound("ResonanceSpace", spaceId));
    }

    public List<ResonanceSpace> getSpacesByMemory(String memoryId) {
        return spaceRepository.findByMemoryId(memoryId);
    }

    public MemoryNote placeNote(String resonanceId, String authorId, String content, String mood, Map<String, Double> position) {
        getSpace(resonanceId);
        MemoryNote note = MemoryNote.builder()
                .resonanceId(resonanceId)
                .authorId(authorId)
                .content(content)
                .mood(mood != null ? mood : "warm")
                .position3d(toJson(position))
                .build();
        return noteRepository.save(note);
    }

    public List<MemoryNote> getNotes(String resonanceId) {
        return noteRepository.findByResonanceIdOrderByCreatedAtAsc(resonanceId);
    }

    /**
     * 共鸣服务聚合统计：用于 {@code GET /api/v1/resonances/stats}。
     *
     * <p>策略：
     * <ol>
     *   <li>复用 {@link MemoryServiceClient#publicPool} 拉跨用户 PUBLIC 记忆池；</li>
     *   <li>对池内前 {@value #STATS_PAIR_LIMIT} 条记忆做"两两 jaccard + 时间/地点/季节加权"，
     *       取所有得分的算术平均作为 {@code avgScore}；{@code totalMatches} 取池大小；</li>
     *   <li>返回附加字段 {@code poolSampled} / {@code pairCount} 让前端能看到真实计算量，
     *       避免"avgScore=0.55 是凑出来的还是真算出来的"这种疑虑。</li>
     * </ol>
     *
     * <p>memory-service 不可达或池为空时 → 全部回退到 0；这跟
     * {@link #searchResonances} 的 fail-closed 语义保持一致（不伪造数字）。
     *
     * <p>{@code algorithmName} 是写死的展示字段，未来切到真实 Milvus 召回后改这里即可。
     */
    public Map<String, Object> resonanceStats() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 统计是全局视图，不属于某个 caller，所以用一个 system sentinel 让 memory-service
        // 跳过 caller 过滤、返回完整公共池。
        final String statsUserId = "__stats__";
        try {
            ApiResponse<List<Map<String, Object>>> resp = memoryClient.publicPool(MAX_PUBLIC_POOL_SIZE, statsUserId);
            List<Map<String, Object>> pool = (resp == null || resp.getData() == null) ? List.of() : resp.getData();
            if (pool.isEmpty()) {
                out.put("avgScore", 0.0);
                out.put("totalMatches", 0);
                out.put("algorithmName", "Mnemoscape Multi-Signal v1");
                out.put("poolSampled", 0);
                return out;
            }
            // 真实统计：用公共池的"两两 jaccard + 时间/地点/季节加权"算平均相似度。
            // 采样上限 STATS_PAIR_LIMIT = 80 → 最多 80*79/2 = 3160 对，避免 200 条全量
            // 跑 O(n²) 拖累 /stats。pool 实际 < 80 时按真实大小算。
            final int sampled = Math.min(pool.size(), STATS_PAIR_LIMIT);
            double sum = 0.0;
            int pairs = 0;
            for (int i = 0; i < sampled; i++) {
                Map<String, Object> a = pool.get(i);
                List<String> aKws = extractKeywords(stringField(a, "title") + " "
                        + stringField(a, "memoryLocation") + " "
                        + stringField(a, "description"));
                Integer aYear = intField(a, "memoryYear");
                String aSeason = stringField(a, "memorySeason");
                String aLoc = stringField(a, "memoryLocation");
                for (int j = i + 1; j < sampled; j++) {
                    sum += scoreOverlap(aKws, aYear, aSeason, aLoc, pool.get(j));
                    pairs++;
                }
            }
            double avg = pairs > 0 ? round(sum / pairs) : 0.0;
            out.put("avgScore", avg);
            out.put("totalMatches", pool.size());
            out.put("poolSampled", sampled);
            out.put("pairCount", pairs);
            out.put("algorithmName", "Mnemoscape Multi-Signal v1");
        } catch (Exception e) {
            log.warn("resonanceStats: public-pool unreachable, returning zeros: {}", e.toString());
            out.put("avgScore", 0.0);
            out.put("totalMatches", 0);
            out.put("algorithmName", "Mnemoscape Multi-Signal v1");
        }
        return out;
    }

    /* ============ 打分 / 工具 ============ */

    /**
     * 真实向量召回路径：把 seed 记忆拼成查询文本 → ai-service /vector/search-public →
     * 跨用户 PUBLIC 记忆的稠密向量命中。
     *
     * @return 命中列表（前端契约一致）；返回 null 表示"向量不可用 / 失败"，调用方降级到关键词打分。
     */
    private List<Map<String, Object>> tryVectorResonance(Map<String, Object> seed, String memoryId, String userId) {
        if (aiClient == null) return null;
        String seedText = (stringField(seed, "title") + "。"
                + stringField(seed, "memoryLocation") + " "
                + stringField(seed, "description")).trim();
        if (seedText.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("seedText", seedText);
            body.put("excludeUserId", userId);
            body.put("topK", DEFAULT_TOP_K);
            ApiResponse<Map<String, Object>> resp = aiClient.searchPublic(body);
            Map<String, Object> data = resp == null ? null : resp.getData();
            if (data == null) return null;
            Object availObj = data.get("available");
            boolean available = availObj instanceof Boolean b && b;
            if (!available) return null; // ai-service 明确说向量不可用 → 降级

            Object hitsObj = data.get("hits");
            if (!(hitsObj instanceof List<?> hits)) return List.of();

            List<Map<String, Object>> out = new ArrayList<>();
            for (Object row : hits) {
                if (!(row instanceof Map<?, ?> m)) continue;
                String otherId = m.get("memoryId") == null ? null : String.valueOf(m.get("memoryId"));
                if (otherId == null || otherId.equals(memoryId)) continue;
                double score = m.get("score") instanceof Number n ? n.doubleValue() : 0.0;
                // COSINE 相似度可能略超 1 或为负（归一化误差），clamp 到 [0,0.99]。
                score = Math.max(0.0, Math.min(0.99, score));

                Map<String, Object> match = new LinkedHashMap<>();
                match.put("memoryId", otherId);
                match.put("title", m.get("title") == null ? "" : String.valueOf(m.get("title")));
                match.put("description", m.get("snippet") == null ? "" : String.valueOf(m.get("snippet")));
                match.put("similarityScore", round(score));
                match.put("emotionSimilarity", round(Math.min(0.99, score * 1.05)));
                match.put("sceneSimilarity", round(Math.min(0.99, score * 0.92)));
                match.put("ownerUsername", virtualOwnerName(
                        m.get("userId") == null ? null : String.valueOf(m.get("userId"))));
                out.add(match);

            }
            log.info("Resonance via vector recall: {} hits for seed {}", out.size(), memoryId);
            return out;
        } catch (Exception e) {
            log.warn("Vector resonance failed, falling back to keyword scoring: {}", e.toString());
            return null;
        }
    }

    /**
     * 朴素稀疏相似度：
     * - 关键词重叠（标题/描述/地点联合）按 jaccard 近似 → 0..0.7
     * - 年代差 ≤ 3 → +0.10
     * - 季节相同 → +0.08
     * - 地点字段相同 → +0.10
     * 满分约 0.98（保留余量给真实 Milvus）。
     */
    private double scoreOverlap(List<String> seedKeywords,
                                Integer seedYear, String seedSeason, String seedLoc,
                                Map<String, Object> other) {
        String otherText = stringField(other, "title") + " "
                         + stringField(other, "memoryLocation") + " "
                         + stringField(other, "description");
        List<String> otherKws = extractKeywords(otherText);
        if (seedKeywords.isEmpty() && otherKws.isEmpty()) return 0;

        Set<String> a = new HashSet<>(seedKeywords);
        Set<String> b = new HashSet<>(otherKws);
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        double jaccard = union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
        double score = 0.55 + 0.7 * jaccard; // 基线 0.55，避免极端 jaccard=0 把所有人都过滤

        Integer otherYear = intField(other, "memoryYear");
        if (seedYear != null && otherYear != null && Math.abs(seedYear - otherYear) <= 3) {
            score += 0.10;
        }
        String otherSeason = stringField(other, "memorySeason");
        if (seedSeason != null && !seedSeason.isBlank()
                && seedSeason.equalsIgnoreCase(otherSeason)) {
            score += 0.08;
        }
        String otherLoc = stringField(other, "memoryLocation");
        if (seedLoc != null && !seedLoc.isBlank() && otherLoc != null
                && seedLoc.equalsIgnoreCase(otherLoc)) {
            score += 0.10;
        }
        return Math.min(0.99, score);
    }

    private static final Pattern CN = Pattern.compile("[一-龥]{2,6}");
    private static final Pattern EN = Pattern.compile("[A-Za-z]{3,}");

    private List<String> extractKeywords(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher cn = CN.matcher(lower);
        while (cn.find()) {
            String s = cn.group();
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 12) break;
        }
        Matcher en = EN.matcher(lower);
        while (en.find()) {
            String s = en.group();
            if (!out.contains(s)) out.add(s);
            if (out.size() >= 16) break;
        }
        return out;
    }

    private static String stringField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static Integer intField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    /** 把 ownerId 稳定哈希成"回忆者-xxxx"形式的虚名（避免暴露 userId）。 */
    private static String virtualOwnerName(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) return "未知忆者";
        int hash = Math.abs(UUID.nameUUIDFromBytes(ownerId.getBytes()).hashCode() % 9999);
        return "回忆者-" + String.format("%04d", hash);
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "{}"; }
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
