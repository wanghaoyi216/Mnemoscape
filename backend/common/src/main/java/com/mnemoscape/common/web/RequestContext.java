package com.mnemoscape.common.web;

import com.mnemoscape.common.exception.BizException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 网关注入身份信息的访问入口。
 *
 * <p>{@link MdcContextFilter} 已经把网关 {@code X-User-Id} / {@code X-User-Name} header
 * 翻译成 request attribute；本类提供受控读取，把"attribute 缺失"这种网关绕过场景
 * 直接转成 401，避免下游服务用 null userId 误写库导致 500。
 */
public final class RequestContext {

    public static final String USER_ID_ATTR = "userId";
    public static final String USER_NAME_ATTR = "username";

    private RequestContext() {
    }

    /** 强校验：拿不到 userId 直接抛 401。用于所有需要身份的 controller 入口。 */
    public static String requireUserId(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ID_ATTR);
        if (value == null || value.toString().isBlank()) {
            throw new BizException(401, "未通过身份认证，请重新登录");
        }
        return value.toString();
    }

    /** 弱读取：拿不到返回 null。用于半公开接口（如可匿名浏览的 PUBLIC 资源）。 */
    public static String optionalUserId(HttpServletRequest request) {
        Object value = request.getAttribute(USER_ID_ATTR);
        return value == null ? null : value.toString();
    }

    public static String optionalUsername(HttpServletRequest request) {
        Object value = request.getAttribute(USER_NAME_ATTR);
        return value == null ? null : value.toString();
    }
}
