package com.mnemoscape.auth.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jasypt 本地字段加密配置 (P0 R1.1 / R3 安全升级)。
 *
 * <p>本类只做"application.yml 启动期解密"。完整 Nacos Config 接入留给下一轮 P0。
 *
 * <h2>为什么用 PBEWithHmacSHA512AndAES_256（R3 从 MD5+DES 升级）</h2>
 * <ul>
 *   <li><b>PBEWithMD5AndDES 是 1999 年的算法</b>：56-bit DES 可被现代硬件暴力破解，
 *       MD5 有碰撞攻击——2026 年的安全审计里这属于合规红线（等保 / SOC2 都过不了）。</li>
 *   <li>AES-256 + HMAC-SHA512 是当前 Jasypt 支持的最强组合：
 *       AES-256 加密机密性，SHA-512 做 PBKDF 密钥派生 + 完整性校验。</li>
 *   <li>JDK 8u161+ / JDK 17 默认自带 unlimited JCE policy，
 *       无需额外安装 policy 文件；Jasypt 的 {@code RandomIvGenerator} 会为每条
 *       密文生成随机 IV，同一明文两次加密得到不同密文（语义安全）。</li>
 * </ul>
 *
 * <h2>怎么生成 ENC 占位符（算法已同步更新）</h2>
 * <pre>
 *   # 1) 拉 jasypt 命令行 jar（任何能跑 Java 的机器都可以）
 *   curl -L -o /tmp/jasypt.jar \
 *     https://repo1.maven.org/maven2/org/jasypt/jasypt/1.9.3/jasypt-1.9.3.jar
 *
 *   # 2) 加密明文 —— 注意 algorithm 参数必须与本类 ALGORITHM 一致
 *   java -cp /tmp/jasypt.jar \
 *        org.jasypt.intf.cli.JasyptPBEStringEncryptionCLI \
 *        input="root123" \
 *        password="$JASYPT_MASTER_KEY" \
 *        algorithm=PBEWithHmacSHA512AndAES_256 \
 *        ivGeneratorClassName=org.jasypt.iv.RandomIvGenerator
 *   # 输出形如：ENC(AbCdEfGh...)
 *
 *   # 3) 把 ENC(...) 粘到 application.yml 对应字段
 *   # 4) 启动服务时把 master-key 注入 JVM 系统属性：
 *        -Djasypt.encryptor.password=$JASYPT_MASTER_KEY
 *   # 推荐做法：环境变量 + .env.workpc，不进 yml / 不进 git。
 * </pre>
 *
 * <h2>master-key 长度要求（AES-256 特有）</h2>
 * PBKDF 会从 master-key 派生 256-bit AES key。虽然 SHA-512 对短输入也能扩展，
 * 但行业惯例（OWASP）建议 master-key 至少 <b>32 字符随机串</b>（约 192 bit 熵），
 * 推荐 64 字符。启动时做长度校验，过短直接 fail-fast。
 *
 * <h2>安全注意</h2>
 * <ul>
 *   <li>master-key 必须与 yml 分开存放（环境变量 / k8s Secret / Vault）。</li>
 *   <li>不要把 ENC(...) 当成"万能保险"——只是减小 git 泄漏的风险面，
 *       拿到 master-key 的人依然能解出全部明文。</li>
 *   <li>轮转 master-key 时必须同步重加密所有 ENC(...) 字段；
 *       从旧算法（MD5+DES）迁移时同理，<b>新旧密文不兼容，必须全量重加密</b>。</li>
 * </ul>
 */
@Configuration
public class JasyptConfig {

    /**
     * algorithm 必须与生成 ENC 时使用的一致；不匹配 → 启动报
     * {@code EncryptionOperationNotPossibleException}。
     * R3 升级：MD5+DES → HMAC-SHA512 + AES-256（详见类注释）。
     */
    private static final String ALGORITHM = "PBEWithHmacSHA512AndAES_256";

    /** AES-256 场景下 master-key 的最低可接受长度（字符）；推荐 64 */
    private static final int MIN_MASTER_KEY_LENGTH = 32;

    @Value("${jasypt.encryptor.password-property:jasypt.encryptor.password}")
    private String passwordProperty;

    /**
     * 自定义 StringEncryptor：手动从系统属性读 master-key，而不是 yml 字段。
     * 这样 master-key 永远不会通过 Spring environment 出现在日志 / actuator 端点里。
     */
    @Bean("jasyptStringEncryptor")
    public StringEncryptor stringEncryptor() {
        String masterKey = System.getProperty(passwordProperty);
        if (masterKey == null || masterKey.isBlank()) {
            // 启动期直接 fail-fast：缺少 master-key = yml 里的 ENC 字段无法解密。
            // 给运维一个明确错误，而不是在首次读取 DB 连接时才报模糊的解密失败。
            throw new IllegalStateException(
                    "Jasypt master key not found. Start the JVM with -D" + passwordProperty
                            + "=<your-master-key>");
        }
        if (masterKey.length() < MIN_MASTER_KEY_LENGTH) {
            // AES-256 场景下过短的 key 会显著降低暴力破解成本，启动期拦截
            throw new IllegalStateException(
                    "Jasypt master key too short: got " + masterKey.length()
                            + " chars, minimum is " + MIN_MASTER_KEY_LENGTH
                            + " (recommend a 64-char random string)");
        }
        PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword(masterKey);
        config.setAlgorithm(ALGORITHM);
        // R3 升级：AES 属于分组密码必须配 IV；RandomIvGenerator 每条密文独立随机 IV
        // （NoIvGenerator 只适用于 DES 这类流式旧算法）
        config.setIvGenerator(new org.jasypt.iv.RandomIvGenerator());
        config.setSaltGenerator(new org.jasypt.salt.RandomSaltGenerator());
        config.setKeyObtentionIterations(1000);
        // 高并发下解密（每次 bean 装配 / 每次 @Value 解析）走池化避免抖动
        encryptor.setPoolSize(4);
        encryptor.setConfig(config);
        return encryptor;
    }
}
