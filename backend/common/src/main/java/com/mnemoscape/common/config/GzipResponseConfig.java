package com.mnemoscape.common.config;

import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 启用 HTTP 响应 GZip 压缩 —— 减少 admin stats / 记忆列表等大 JSON 的网络字节数。
 *
 * <p><b>配置策略</b>：
 * <ul>
 *   <li>仅压缩 {@code text/*} / {@code application/json} / {@code application/xml} / 消息体</li>
 *   <li>只压 1KB 以上的响应 —— 避免压缩 < 1KB 的小响应反而因头部 overhead 变大</li>
 *   <li>{@code DEFLATE} 替代算法不启用（浏览器支持率低）</li>
 * </ul>
 *
 * <p><b>为什么不放 application.yml</b>：所有 service 共用此压缩配置，集中放
 * common 模块的 auto-config 里比散到 6 个 yml 干净；服务关闭 {@code server.compression.enabled}
 * 也只需在自己的 application.yml 加一行覆盖即可。
 *
 * <p><b>预期收益</b>：admin stats 类端点（如 {@code /api/v1/admin/stats/memory-trends}
 * 返回 1.5MB+ JSON）压缩后 ~ 150KB，节省 90% 带宽。
 */
@Configuration
public class GzipResponseConfig {

    @Bean
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> gzipResponseCustomizer() {
        return factory -> factory.setCompression(compression());
    }

    private static Compression compression() {
        Compression compression = new Compression();
        compression.setEnabled(true);
        compression.setMinResponseSize(DataSize.ofKilobytes(1));
        compression.setMimeTypes(new String[]{
                "text/html",
                "text/xml",
                "text/plain",
                "text/css",
                "text/javascript",
                "application/json",
                "application/xml",
                "application/javascript",
                "application/x-javascript",
                "application/xml+rss",
                "image/svg+xml"
        });
        return compression;
    }
}
