package com.mnemoscape.asset.service;

import com.mnemoscape.asset.config.StorageProperties;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 资产污染 allowlist 判定单元测试。
 *
 * <p>覆盖范围（任务 A4）：
 * <ul>
 *   <li>{@link StorageProperties#isPublicObject} — 顶层目录白名单判定</li>
 *   <li>{@link AssetService#checkReadPermission} — 私有对象 owner 判定（userId 一致 vs 不一致）</li>
 *   <li>legacy orphan 根级指纹（{@code <uuid>-...}）判定 — 反射调 {@code looksLikeLegacyUpload}</li>
 * </ul>
 *
 * <p>注：{@code AssetService.listMinioStaticInternal} 内的过滤逻辑是私有方法，本类仅对其
 * 等价语义做覆盖——{@code isPublicObject} + {@code checkReadPermission} + 根级裸文件
 * "无 UUID 前缀"分支（这部分直接走 {@code isPublicObject("bare.png") == false}）。
 */
class AssetServiceAllowlistTest {

    private AssetService assetService;

    @BeforeEach
    void setUp() {
        // checkReadPermission 不依赖 MinIO / 凭据，因此 mock 即可。
        MinioClient minio = mock(MinioClient.class);
        StorageProperties props = new StorageProperties();
        assetService = new AssetService(minio, props);
    }

    // -----------------------------------------------------------------------
    // 1. isPublicObject — 白名单内顶层目录放行
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("photo/foo.png → 白名单内顶层目录 → true")
    void photoDirIsPublic() {
        assertTrue(StorageProperties.isPublicObject("photo/foo.png"));
    }

    @Test
    @DisplayName("video/clip.mp4 → 白名单内顶层目录 → true")
    void videoDirIsPublic() {
        assertTrue(StorageProperties.isPublicObject("video/clip.mp4"));
    }

    @Test
    @DisplayName("audio/track.mp3 → 白名单内顶层目录 → true")
    void audioDirIsPublic() {
        assertTrue(StorageProperties.isPublicObject("audio/track.mp3"));
    }

    @Test
    @DisplayName("gif/anim.gif → 白名单内顶层目录 → true")
    void gifDirIsPublic() {
        assertTrue(StorageProperties.isPublicObject("gif/anim.gif"));
    }

    @Test
    @DisplayName("icon 与 icons 都放行（兼容历史单/复数）")
    void iconAndIconsAreBothPublic() {
        assertTrue(StorageProperties.isPublicObject("icon/star.svg"));
        assertTrue(StorageProperties.isPublicObject("icons/star.svg"));
    }

    @Test
    @DisplayName("photo 嵌套子目录（photo/2024/summer/a.png）→ true（白名单只看顶层目录）")
    void photoNestedDirsAreStillPublic() {
        assertTrue(StorageProperties.isPublicObject("photo/2024/summer/a.png"));
    }

    // -----------------------------------------------------------------------
    // 2. isPublicObject — 白名单外 / 根级裸文件不放行
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("chat/bar.png → 白名单外 → false")
    void chatDirIsNotPublic() {
        assertFalse(StorageProperties.isPublicObject("chat/bar.png"));
    }

    @Test
    @DisplayName("support/tickets/tmp/__pycache__ 顶层目录 → 全部 false")
    void supportTicketsTmpPycacheAreNotPublic() {
        assertFalse(StorageProperties.isPublicObject("support/ticket-1.txt"));
        assertFalse(StorageProperties.isPublicObject("tickets/0001.json"));
        assertFalse(StorageProperties.isPublicObject("tmp/upload-12345.png"));
        assertFalse(StorageProperties.isPublicObject("__pycache__/foo.cpython-312.pyc"));
    }

    @Test
    @DisplayName("legacy-orphan/old.png → false（已迁移目录不放回公共列表）")
    void legacyOrphanDirIsNotPublic() {
        assertFalse(StorageProperties.isPublicObject("legacy-orphan/old.png"));
        assertFalse(StorageProperties.isPublicObject("legacy-orphan/chat/x.png"));
    }

    @Test
    @DisplayName("users/{userId}/img.png → false（isPublicObject 本身只看白名单；owner 检查由 checkReadPermission 处理）")
    void usersDirIsNotPublicByItself() {
        assertFalse(StorageProperties.isPublicObject("users/u-123/img.png"));
    }

    @Test
    @DisplayName("根级裸文件 bare.png → false（无子目录就不是预置素材）")
    void rootLevelBareFileIsNotPublic() {
        assertFalse(StorageProperties.isPublicObject("bare.png"));
        assertFalse(StorageProperties.isPublicObject("random.jpg"));
    }

    @Test
    @DisplayName("null → false；空串 → false；前缀撞名（如 'photoextra/x.png'）→ false")
    void edgeCasesAreNotPublic() {
        assertFalse(StorageProperties.isPublicObject(null));
        assertFalse(StorageProperties.isPublicObject(""));
        // 注意：'photoextra' 不是 'photo'，不因 startsWith 误判放行
        assertFalse(StorageProperties.isPublicObject("photoextra/x.png"));
    }

    // -----------------------------------------------------------------------
    // 3. checkReadPermission — owner 检查（userId 一致 → 通过；不一致 → 抛 403）
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("users/{self}/img.png → owner 一致 → 不抛异常（视为可读）")
    void ownerCheckSucceeds() {
        String userId = "user-42";
        // 不应抛异常
        assetService.checkReadPermission("users/" + userId + "/img.png", userId);
    }

    @Test
    @DisplayName("users/{other}/img.png → owner 不一致 → 抛 BizException 403")
    void ownerCheckFailsForOtherUser() {
        String self = "user-42";
        String other = "user-99";
        try {
            assetService.checkReadPermission("users/" + other + "/img.png", self);
            assertFalse(true, "应抛出 BizException 403");
        } catch (com.mnemoscape.common.exception.BizException e) {
            assertTrue(e.getCode() == 403, "expected 403, got " + e.getCode());
        }
    }

    @Test
    @DisplayName("匿名读 users/{u}/img.png → 抛 403（ownerId 与 null 不匹配）")
    void anonymousCannotReadPrivate() {
        try {
            assetService.checkReadPermission("users/user-42/img.png", null);
            assertFalse(true, "应抛出 BizException 403");
        } catch (com.mnemoscape.common.exception.BizException e) {
            assertTrue(e.getCode() == 403, "expected 403, got " + e.getCode());
        }
    }

    @Test
    @DisplayName("photo/foo.png（公共对象）→ 任何调用者（含匿名）都视为可读")
    void publicObjectReadableByAnyone() {
        // 公共对象不进 users/ 分支，直接放行
        assetService.checkReadPermission("photo/foo.png", null);
        assetService.checkReadPermission("photo/foo.png", "user-1");
    }

    // -----------------------------------------------------------------------
    // 4. looksLikeLegacyUpload 风格判定 — 反射调私有方法
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("looksLikeLegacyUpload：标准 <uuid>-filename → true")
    void legacyUploadFingerprintMatches() throws Exception {
        Method m = AssetService.class.getDeclaredMethod("looksLikeLegacyUpload", String.class);
        m.setAccessible(true);
        String good = "550e8400-e29b-41d4-a716-446655440000-rand.png";
        assertTrue((Boolean) m.invoke(null, good));
    }

    @Test
    @DisplayName("looksLikeLegacyUpload：非 UUID 前缀（如 random.png）→ false（交给 migrateOffAllowlist 处理）")
    void legacyUploadFingerprintRejectsBareFile() throws Exception {
        Method m = AssetService.class.getDeclaredMethod("looksLikeLegacyUpload", String.class);
        m.setAccessible(true);
        assertFalse((Boolean) m.invoke(null, "random.png"));
        assertFalse((Boolean) m.invoke(null, "123-not-a-uuid.png"));
        assertFalse((Boolean) m.invoke(null, ""));
        assertFalse((Boolean) m.invoke(null, (Object) null));
    }
}
