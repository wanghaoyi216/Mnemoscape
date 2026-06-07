package com.mnemoscape.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 {@code memoryLocation}（自由文本）→ 经纬度 {@code [lng, lat]}。
 *
 * <p>策略：
 * <ol>
 *   <li>本地内存表（精选大城市 + 著名景点）首先命中 — O(1) 常量响应，离线也能跑；
 *       这张表是<b>服务端</b>的"已知 anchor"集合，与前端无关，不构成
 *       Bug 2 描述的"客户端伪造坐标"。</li>
 *   <li>未命中时走外部 Nominatim（OpenStreetMap）公共 API，遵守速率限制，
 *       结果会被缓存到 {@link #remoteCache} 减少重复请求。</li>
 *   <li>外部失败 → 返回 {@link Optional#empty()}，调用方记忆条目可保留 null
 *       坐标，atlas 视图相应的过滤掉。</li>
 * </ol>
 *
 * <p>开关：{@code mnemoscape.memory.geocoder.enabled} 控制总开关；
 *         {@code mnemoscape.memory.geocoder.remote-enabled} 控制是否打外网。
 *         单元测试可只用本地表。
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    /**
     * 服务端 anchor 表。<b>这是后端的"已知地名 → 坐标"表，跟前端 client gazetteer
     * 不是同一个东西</b> —— 前端 fix 后绝对不会持有这个表，atlas 视图只展示后端
     * 给的坐标。
     */
    private static final Map<String, double[]> ANCHORS = new HashMap<>();
    static {
        // 中国主要城市
        ANCHORS.put("北京",     new double[]{116.4074, 39.9042});
        ANCHORS.put("上海",     new double[]{121.4737, 31.2304});
        ANCHORS.put("天津",     new double[]{117.2010, 39.0842});
        ANCHORS.put("重庆",     new double[]{106.5516, 29.5630});
        ANCHORS.put("广州",     new double[]{113.2644, 23.1291});
        ANCHORS.put("深圳",     new double[]{114.0579, 22.5431});
        ANCHORS.put("成都",     new double[]{104.0665, 30.5723});
        ANCHORS.put("杭州",     new double[]{120.1551, 30.2741});
        ANCHORS.put("南京",     new double[]{118.7969, 32.0603});
        ANCHORS.put("武汉",     new double[]{114.3055, 30.5928});
        ANCHORS.put("西安",     new double[]{108.9398, 34.3416});
        ANCHORS.put("苏州",     new double[]{120.5853, 31.2989});
        ANCHORS.put("青岛",     new double[]{120.3826, 36.0671});
        ANCHORS.put("厦门",     new double[]{118.0894, 24.4798});
        ANCHORS.put("长沙",     new double[]{112.9388, 28.2278});
        ANCHORS.put("郑州",     new double[]{113.6253, 34.7466});
        ANCHORS.put("济南",     new double[]{117.1201, 36.6512});
        ANCHORS.put("哈尔滨",   new double[]{126.6425, 45.7569});
        ANCHORS.put("沈阳",     new double[]{123.4291, 41.7968});
        ANCHORS.put("大连",     new double[]{121.6147, 38.9140});
        ANCHORS.put("昆明",     new double[]{102.8329, 24.8801});
        ANCHORS.put("贵阳",     new double[]{106.7135, 26.5783});
        ANCHORS.put("南宁",     new double[]{108.3669, 22.8170});
        ANCHORS.put("兰州",     new double[]{103.8343, 36.0611});
        ANCHORS.put("银川",     new double[]{106.2306, 38.4872});
        ANCHORS.put("西宁",     new double[]{101.7782, 36.6171});
        ANCHORS.put("乌鲁木齐", new double[]{87.6168, 43.8256});
        ANCHORS.put("拉萨",     new double[]{91.1409, 29.6450});
        ANCHORS.put("呼和浩特", new double[]{111.7491, 40.8424});
        ANCHORS.put("太原",     new double[]{112.5489, 37.8706});
        ANCHORS.put("石家庄",   new double[]{114.5149, 38.0428});
        ANCHORS.put("合肥",     new double[]{117.2272, 31.8206});
        ANCHORS.put("福州",     new double[]{119.2965, 26.0745});
        ANCHORS.put("南昌",     new double[]{115.8921, 28.6764});
        ANCHORS.put("海口",     new double[]{110.3312, 20.0311});
        ANCHORS.put("三亚",     new double[]{109.5119, 18.2528});
        ANCHORS.put("香港",     new double[]{114.1694, 22.3193});
        ANCHORS.put("澳门",     new double[]{113.5439, 22.1987});
        ANCHORS.put("台北",     new double[]{121.5654, 25.0330});
        ANCHORS.put("大理",     new double[]{100.2257, 25.5916});
        ANCHORS.put("丽江",     new double[]{100.2335, 26.8721});
        ANCHORS.put("九寨沟",   new double[]{103.9201, 33.2604});
        // 国际重点
        ANCHORS.put("东京",     new double[]{139.6917, 35.6895});
        ANCHORS.put("首尔",     new double[]{126.9780, 37.5665});
        ANCHORS.put("新加坡",   new double[]{103.8198, 1.3521});
        ANCHORS.put("曼谷",     new double[]{100.5018, 13.7563});
        ANCHORS.put("纽约",     new double[]{-74.0060, 40.7128});
        ANCHORS.put("旧金山",   new double[]{-122.4194, 37.7749});
        ANCHORS.put("洛杉矶",   new double[]{-118.2437, 34.0522});
        ANCHORS.put("伦敦",     new double[]{-0.1278, 51.5074});
        ANCHORS.put("巴黎",     new double[]{2.3522, 48.8566});
        ANCHORS.put("柏林",     new double[]{13.4050, 52.5200});
        ANCHORS.put("悉尼",     new double[]{151.2093, -33.8688});
        ANCHORS.put("迪拜",     new double[]{55.2708, 25.2048});
        ANCHORS.put("莫斯科",   new double[]{37.6173, 55.7558});
        // 中国二三线城市补充 (v6 扩充 — 让"在创建记忆时填城市名"能更稳地命中坐标)
        ANCHORS.put("无锡",     new double[]{120.3119, 31.4912});
        ANCHORS.put("宁波",     new double[]{121.5440, 29.8683});
        ANCHORS.put("温州",     new double[]{120.6720, 28.0006});
        ANCHORS.put("绍兴",     new double[]{120.5820, 30.0298});
        ANCHORS.put("嘉兴",     new double[]{120.7556, 30.7468});
        ANCHORS.put("徐州",     new double[]{117.1845, 34.2618});
        ANCHORS.put("常州",     new double[]{119.9740, 31.8112});
        ANCHORS.put("南通",     new double[]{120.8945, 31.9803});
        ANCHORS.put("扬州",     new double[]{119.4214, 32.3932});
        ANCHORS.put("镇江",     new double[]{119.4527, 32.2044});
        ANCHORS.put("烟台",     new double[]{121.4480, 37.4646});
        ANCHORS.put("威海",     new double[]{122.1166, 37.5097});
        ANCHORS.put("淄博",     new double[]{118.0549, 36.8137});
        ANCHORS.put("洛阳",     new double[]{112.4540, 34.6197});
        ANCHORS.put("开封",     new double[]{114.3413, 34.7972});
        ANCHORS.put("许昌",     new double[]{113.8265, 34.0227});
        ANCHORS.put("绵阳",     new double[]{104.7414, 31.4640});
        ANCHORS.put("自贡",     new double[]{104.7733, 29.3525});
        ANCHORS.put("宜宾",     new double[]{104.6308, 28.7602});
        ANCHORS.put("乐山",     new double[]{103.7613, 29.5821});
        ANCHORS.put("德阳",     new double[]{104.3947, 31.1267});
        ANCHORS.put("桂林",     new double[]{110.2900, 25.2740});
        ANCHORS.put("北海",     new double[]{109.1190, 21.4811});
        ANCHORS.put("柳州",     new double[]{109.4216, 24.3145});
        ANCHORS.put("珠海",     new double[]{113.5767, 22.2710});
        ANCHORS.put("佛山",     new double[]{113.1218, 23.0218});
        ANCHORS.put("东莞",     new double[]{113.7518, 23.0207});
        ANCHORS.put("中山",     new double[]{113.3826, 22.5210});
        ANCHORS.put("惠州",     new double[]{114.4126, 23.0794});
        ANCHORS.put("汕头",     new double[]{116.6822, 23.3535});
        ANCHORS.put("汕尾",     new double[]{115.3645, 22.7787});
        ANCHORS.put("揭阳",     new double[]{116.3727, 23.5497});
        ANCHORS.put("湛江",     new double[]{110.3593, 21.2705});
        ANCHORS.put("茂名",     new double[]{110.9192, 21.6593});
        ANCHORS.put("赣州",     new double[]{114.9408, 25.8531});
        ANCHORS.put("九江",     new double[]{115.9926, 29.7124});
        ANCHORS.put("芜湖",     new double[]{118.3760, 31.3263});
        ANCHORS.put("黄山",     new double[]{118.3171, 29.7090});
        ANCHORS.put("唐山",     new double[]{118.1802, 39.6306});
        ANCHORS.put("秦皇岛",   new double[]{119.5862, 39.9425});
        ANCHORS.put("张家口",   new double[]{114.8794, 40.8118});
        ANCHORS.put("承德",     new double[]{117.9398, 40.9762});
        ANCHORS.put("保定",     new double[]{115.4646, 38.8740});
        ANCHORS.put("廊坊",     new double[]{116.7042, 39.5237});
        ANCHORS.put("邯郸",     new double[]{114.4778, 36.6018});
        ANCHORS.put("长春",     new double[]{125.3245, 43.8868});
        ANCHORS.put("吉林",     new double[]{126.5500, 43.8378});
        ANCHORS.put("延边",     new double[]{129.5135, 42.9046});
        ANCHORS.put("丹东",     new double[]{124.3833, 40.1247});
        ANCHORS.put("鞍山",     new double[]{122.9956, 41.1108});
        ANCHORS.put("抚顺",     new double[]{123.9572, 41.8804});
        ANCHORS.put("齐齐哈尔", new double[]{123.9520, 47.3543});
        ANCHORS.put("大庆",     new double[]{125.1166, 46.5895});
        ANCHORS.put("牡丹江",   new double[]{129.6181, 44.5828});
        ANCHORS.put("漠河",     new double[]{122.5388, 53.4731});
        ANCHORS.put("张家界",   new double[]{110.4790, 29.1170});
        ANCHORS.put("湘潭",     new double[]{112.9255, 27.8459});
        ANCHORS.put("株洲",     new double[]{113.1339, 27.8275});
        ANCHORS.put("岳阳",     new double[]{113.1300, 29.3570});
        ANCHORS.put("常德",     new double[]{111.6914, 29.0402});
        ANCHORS.put("宜昌",     new double[]{111.2867, 30.6919});
        ANCHORS.put("襄阳",     new double[]{112.1448, 32.0419});
        ANCHORS.put("黄冈",     new double[]{114.8722, 30.4536});
        ANCHORS.put("天水",     new double[]{105.7249, 34.5808});
        ANCHORS.put("敦煌",     new double[]{94.6608, 40.1421});
        ANCHORS.put("嘉峪关",   new double[]{98.2891, 39.7773});
        ANCHORS.put("喀什",     new double[]{75.9893, 39.4677});
        ANCHORS.put("吐鲁番",   new double[]{89.1841, 42.9476});
        ANCHORS.put("阿勒泰",   new double[]{88.1396, 47.8484});
        ANCHORS.put("伊犁",     new double[]{81.3179, 43.9119});
        ANCHORS.put("石河子",   new double[]{86.0410, 44.3066});
        ANCHORS.put("克拉玛依", new double[]{84.8739, 45.5959});
        ANCHORS.put("日喀则",   new double[]{88.8853, 29.2675});
        ANCHORS.put("林芝",     new double[]{94.3625, 29.6547});
        ANCHORS.put("那曲",     new double[]{92.0517, 31.4761});
        ANCHORS.put("玉树",     new double[]{97.0083, 33.0040});
        ANCHORS.put("迪庆",     new double[]{99.7066, 27.8268});
        ANCHORS.put("香格里拉", new double[]{99.7066, 27.8268});
        ANCHORS.put("普洱",     new double[]{100.9722, 22.7773});
        ANCHORS.put("西双版纳", new double[]{100.7971, 22.0019});
        ANCHORS.put("玉龙",     new double[]{100.2331, 26.8214});
        ANCHORS.put("泸沽湖",   new double[]{100.7864, 27.7136});
        ANCHORS.put("阳朔",     new double[]{110.4988, 24.7783});
        ANCHORS.put("张掖",     new double[]{100.4495, 38.9326});
        // 国际重点 (v6)
        ANCHORS.put("大阪",     new double[]{135.5023, 34.6937});
        ANCHORS.put("京都",     new double[]{135.7681, 35.0116});
        ANCHORS.put("北海道",   new double[]{141.3469, 43.0642});
        ANCHORS.put("冲绳",     new double[]{127.6809, 26.2125});
        ANCHORS.put("釜山",     new double[]{129.0756, 35.1796});
        ANCHORS.put("济州",     new double[]{126.5312, 33.4996});
        ANCHORS.put("芝加哥",   new double[]{-87.6298, 41.8781});
        ANCHORS.put("西雅图",   new double[]{-122.3321, 47.6062});
        ANCHORS.put("波士顿",   new double[]{-71.0589, 42.3601});
        ANCHORS.put("华盛顿",   new double[]{-77.0369, 38.9072});
        ANCHORS.put("拉斯维加斯", new double[]{-115.1398, 36.1699});
        ANCHORS.put("迈阿密",   new double[]{-80.1918, 25.7617});
        ANCHORS.put("夏威夷",   new double[]{-157.8581, 21.3099});
        ANCHORS.put("多伦多",   new double[]{-79.3832, 43.6532});
        ANCHORS.put("温哥华",   new double[]{-123.1207, 49.2827});
        ANCHORS.put("墨尔本",   new double[]{144.9631, -37.8136});
        ANCHORS.put("奥克兰",   new double[]{174.7633, -36.8485});
        ANCHORS.put("罗马",     new double[]{12.4964, 41.9028});
        ANCHORS.put("米兰",     new double[]{9.1900, 45.4642});
        ANCHORS.put("威尼斯",   new double[]{12.3155, 45.4408});
        ANCHORS.put("巴塞罗那", new double[]{2.1734, 41.3851});
        ANCHORS.put("马德里",   new double[]{-3.7038, 40.4168});
        ANCHORS.put("阿姆斯特丹", new double[]{4.9041, 52.3676});
        ANCHORS.put("布拉格",   new double[]{14.4378, 50.0755});
        ANCHORS.put("维也纳",   new double[]{16.3738, 48.2082});
        ANCHORS.put("苏黎世",   new double[]{8.5417, 47.3769});
        ANCHORS.put("日内瓦",   new double[]{6.1432, 46.2044});
        ANCHORS.put("赫尔辛基", new double[]{24.9384, 60.1699});
        ANCHORS.put("斯德哥尔摩", new double[]{18.0686, 59.3293});
        ANCHORS.put("哥本哈根", new double[]{12.5683, 55.6761});
        ANCHORS.put("奥斯陆",   new double[]{10.7522, 59.9139});
        ANCHORS.put("雷克雅未克", new double[]{-21.9426, 64.1466});
        ANCHORS.put("伊斯坦布尔", new double[]{28.9784, 41.0082});
        ANCHORS.put("孟买",     new double[]{72.8777, 19.0760});
        ANCHORS.put("德里",     new double[]{77.1025, 28.7041});
        ANCHORS.put("加尔各答", new double[]{88.3639, 22.5726});
        ANCHORS.put("吉隆坡",   new double[]{101.6869, 3.1390});
        ANCHORS.put("雅加达",   new double[]{106.8456, -6.2088});
        ANCHORS.put("马尼拉",   new double[]{120.9842, 14.5995});
        ANCHORS.put("胡志明",   new double[]{106.6297, 10.8231});
        ANCHORS.put("河内",     new double[]{105.8542, 21.0285});
        ANCHORS.put("普吉",     new double[]{98.3923, 7.8804});
        ANCHORS.put("清迈",     new double[]{98.9925, 18.7883});
        ANCHORS.put("巴厘岛",   new double[]{115.1889, -8.4095});
        ANCHORS.put("开罗",     new double[]{31.2357, 30.0444});
        ANCHORS.put("约翰内斯堡", new double[]{28.0473, -26.2041});
        ANCHORS.put("开普敦",   new double[]{18.4241, -33.9249});
        ANCHORS.put("内罗毕",   new double[]{36.8219, -1.2921});
        ANCHORS.put("圣保罗",   new double[]{-46.6333, -23.5505});
        ANCHORS.put("里约热内卢", new double[]{-43.1729, -22.9068});
        ANCHORS.put("布宜诺斯艾利斯", new double[]{-58.3816, -34.6037});
        ANCHORS.put("墨西哥城", new double[]{-99.1332, 19.4326});
        // 英文别名 (v6 扩充)
        ANCHORS.put("osaka",     new double[]{135.5023, 34.6937});
        ANCHORS.put("kyoto",     new double[]{135.7681, 35.0116});
        ANCHORS.put("seattle",   new double[]{-122.3321, 47.6062});
        ANCHORS.put("chicago",   new double[]{-87.6298, 41.8781});
        ANCHORS.put("boston",    new double[]{-71.0589, 42.3601});
        ANCHORS.put("miami",     new double[]{-80.1918, 25.7617});
        ANCHORS.put("vancouver", new double[]{-123.1207, 49.2827});
        ANCHORS.put("toronto",   new double[]{-79.3832, 43.6532});
        ANCHORS.put("melbourne", new double[]{144.9631, -37.8136});
        ANCHORS.put("rome",      new double[]{12.4964, 41.9028});
        ANCHORS.put("milan",     new double[]{9.1900, 45.4642});
        ANCHORS.put("barcelona", new double[]{2.1734, 41.3851});
        ANCHORS.put("madrid",    new double[]{-3.7038, 40.4168});
        ANCHORS.put("amsterdam", new double[]{4.9041, 52.3676});
        ANCHORS.put("vienna",    new double[]{16.3738, 48.2082});
        ANCHORS.put("zurich",    new double[]{8.5417, 47.3769});
        ANCHORS.put("istanbul",  new double[]{28.9784, 41.0082});
        ANCHORS.put("mumbai",    new double[]{72.8777, 19.0760});
        ANCHORS.put("delhi",     new double[]{77.1025, 28.7041});
        ANCHORS.put("bali",      new double[]{115.1889, -8.4095});
        ANCHORS.put("cairo",     new double[]{31.2357, 30.0444});
        ANCHORS.put("rio de janeiro", new double[]{-43.1729, -22.9068});
        // 英文别名
        ANCHORS.put("tokyo",     new double[]{139.6917, 35.6895});
        ANCHORS.put("seoul",     new double[]{126.9780, 37.5665});
        ANCHORS.put("singapore", new double[]{103.8198, 1.3521});
        ANCHORS.put("bangkok",   new double[]{100.5018, 13.7563});
        ANCHORS.put("new york",  new double[]{-74.0060, 40.7128});
        ANCHORS.put("san francisco", new double[]{-122.4194, 37.7749});
        ANCHORS.put("los angeles",   new double[]{-118.2437, 34.0522});
        ANCHORS.put("london",    new double[]{-0.1278, 51.5074});
        ANCHORS.put("paris",     new double[]{2.3522, 48.8566});
        ANCHORS.put("berlin",    new double[]{13.4050, 52.5200});
        ANCHORS.put("sydney",    new double[]{151.2093, -33.8688});
        ANCHORS.put("dubai",     new double[]{55.2708, 25.2048});
        ANCHORS.put("moscow",    new double[]{37.6173, 55.7558});
    }

    private final Map<String, double[]> remoteCache = new HashMap<>();

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mnemoscape.memory.geocoder.enabled:true}")
    private boolean enabled;

    @Value("${mnemoscape.memory.geocoder.remote-enabled:false}")
    private boolean remoteEnabled;

    @Value("${mnemoscape.memory.geocoder.nominatim-base:https://nominatim.openstreetmap.org}")
    private String nominatimBase;

    /**
     * 解析地名为 [lng, lat]。
     * <p>命中规则按 anchor 表 → 远程 nominatim → null 顺序衰减。
     */
    public Optional<double[]> resolve(String location) {
        if (!enabled || location == null || location.isBlank()) return Optional.empty();
        String norm = location.trim().toLowerCase().replaceAll("\\s+", "");

        // 1. 判断是否为简单城市/区域名（完全等于锚点，或者锚点名+市/省/特区/区等，无其他文字）
        boolean isSimpleCity = false;
        double[] directAnchor = null;
        for (Map.Entry<String, double[]> e : ANCHORS.entrySet()) {
            String anchorKey = e.getKey().toLowerCase();
            if (norm.equals(anchorKey) || 
                norm.equals(anchorKey + "市") || 
                norm.equals(anchorKey + "省") || 
                norm.equals(anchorKey + "特别行政区") || 
                norm.equals(anchorKey + "特区") || 
                norm.equals(anchorKey + "city") || 
                norm.equals(anchorKey + "town") || 
                norm.equals(anchorKey + "district")) {
                isSimpleCity = true;
                directAnchor = e.getValue();
                break;
            }
        }

        // 如果是简单城市名，直接返回预置坐标（避免对简单查询发起外部 API 调度）
        if (isSimpleCity && directAnchor != null) {
            return Optional.of(directAnchor.clone());
        }

        // 2. 对于较详细的地址，优先检查远程缓存与外部 Nominatim 地理编码
        double[] cached = remoteCache.get(norm);
        if (cached != null) return Optional.of(cached.clone());

        if (remoteEnabled) {
            try {
                String url = nominatimBase + "/search?format=json&limit=1&q="
                        + URLEncoder.encode(location.trim(), StandardCharsets.UTF_8);
                HttpHeaders headers = new HttpHeaders();
                headers.set("User-Agent", "Mnemoscape/1.0 (memory-service)");
                org.springframework.http.HttpEntity<Void> req = new org.springframework.http.HttpEntity<>(headers);
                String body = restTemplate.exchange(URI.create(url),
                        org.springframework.http.HttpMethod.GET, req, String.class)
                        .getBody();
                if (body != null) {
                    JsonNode arr = objectMapper.readTree(body);
                    if (arr.isArray() && arr.size() > 0) {
                        double lat = arr.get(0).get("lat").asDouble();
                        double lon = arr.get(0).get("lon").asDouble();
                        double[] coords = new double[]{lon, lat};
                        remoteCache.put(norm, coords);
                        return Optional.of(coords);
                    }
                }
            } catch (Exception e) {
                log.debug("Geocoder remote fetch failed for '{}': {}", location, e.toString());
            }
        }

        // 3. 如果远程未启用或解析失败，降级使用 anchor 包含查找
        for (Map.Entry<String, double[]> e : ANCHORS.entrySet()) {
            if (norm.contains(e.getKey().toLowerCase())) {
                return Optional.of(e.getValue().clone());
            }
        }

        return Optional.empty();
    }


    /** 仅供单元测试或回填脚本使用：直接读 anchor 表，不打网络。 */
    public Optional<double[]> resolveAnchorOnly(String location) {
        if (location == null || location.isBlank()) return Optional.empty();
        String norm = location.trim().toLowerCase().replaceAll("\\s+", "");
        for (Map.Entry<String, double[]> e : ANCHORS.entrySet()) {
            if (norm.contains(e.getKey().toLowerCase())) return Optional.of(e.getValue().clone());
        }
        return Optional.empty();
    }
}
