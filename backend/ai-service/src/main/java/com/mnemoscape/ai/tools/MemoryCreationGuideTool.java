package com.mnemoscape.ai.tools;

import com.mnemoscape.ai.tools.audit.Tool;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 记忆创建引导工具 — AI 主动引导用户补充记忆细节。
 *
 * <p>当用户描述一段记忆但细节不够丰富时，模型调用此工具生成
 * 引导性问题，帮助用户回忆更多感官细节和情感体验。
 */
@Configuration
public class MemoryCreationGuideTool {

    public static class Request {
        public String currentDescription;
        public String title;
        public String location;
        public String season;
        public Integer year;
    }

    public static class Response {
        public List<String> guidingQuestions = new ArrayList<>();
        public List<String> missingDimensions = new ArrayList<>();
        public String encouragement;
    }

    @Bean
    public FunctionCallback memoryCreationGuideToolCallback() {
        Function<Request, Response> fn = this::guide;
        return FunctionCallback.builder()
                .description("Generate guiding questions to help the user enrich their memory description. "
                        + "Analyzes what sensory/emotional details are missing and suggests specific "
                        + "questions like 'What did the air smell like?' or 'Who was with you?'. "
                        + "Use when the user is creating or editing a memory and the description "
                        + "feels thin or lacks sensory detail.")
                .function("memoryCreationGuideTool", fn)
                .inputType(Request.class)
                .build();
    }

    @Tool("memoryCreationGuideTool")
    public Response guide(Request req) {
        Response resp = new Response();
        String desc = (req != null && req.currentDescription != null) ? req.currentDescription : "";

        if (!containsAny(desc, "看", "见", "颜色", "光", "色彩", "see", "look", "color", "light")) {
            resp.missingDimensions.add("visual");
            resp.guidingQuestions.add("那个场景里的光线是什么样的？是温暖的金色阳光，还是柔和的月光？");
        }
        if (!containsAny(desc, "听", "声", "音", "歌", "响", "hear", "sound", "music", "voice")) {
            resp.missingDimensions.add("auditory");
            resp.guidingQuestions.add("当时你能听到什么声音？风声、人声、还是某首歌？");
        }
        if (!containsAny(desc, "闻", "味", "香", "臭", "smell", "scent", "fragrance")) {
            resp.missingDimensions.add("olfactory");
            resp.guidingQuestions.add("空气中有什么气味吗？花香、雨后的泥土味、还是食物的香气？");
        }
        if (!containsAny(desc, "触", "摸", "冷", "热", "温", "风", "touch", "feel", "cold", "warm", "wind")) {
            resp.missingDimensions.add("tactile");
            resp.guidingQuestions.add("你还记得当时的温度吗？皮肤上有什么感觉——微风、阳光的温暖？");
        }
        if (!containsAny(desc, "人", "谁", "朋友", "家人", "他", "她", "我们", "who", "friend", "family", "together")) {
            resp.missingDimensions.add("people");
            resp.guidingQuestions.add("那个时刻有谁陪在你身边？还是你独自一人？");
        }
        if (!containsAny(desc, "感", "心", "情", "开心", "难过", "激动", "平静", "feel", "emotion", "happy", "sad")) {
            resp.missingDimensions.add("emotion");
            resp.guidingQuestions.add("回想那个瞬间，你的心情是怎样的？是平静、喜悦、还是有些感伤？");
        }

        if (resp.guidingQuestions.isEmpty()) {
            resp.encouragement = "这段记忆描述得很丰富了！如果你愿意，可以再补充一些当时脑海中闪过的念头。";
        } else {
            resp.encouragement = "每一个细节都是珍贵的时光碎片，慢慢回忆，不着急。";
        }

        return resp;
    }

    private boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
