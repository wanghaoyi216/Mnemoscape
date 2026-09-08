package com.mnemoscape.auth.controller;

import com.mnemoscape.auth.model.dto.AuthResponse;
import com.mnemoscape.auth.model.dto.LoginRequest;
import com.mnemoscape.auth.model.dto.RegisterRequest;
import com.mnemoscape.auth.model.dto.UserProfileResponse;
import com.mnemoscape.auth.service.AuthService;
import com.mnemoscape.common.dto.ApiResponse;
import com.mnemoscape.common.ratelimit.RateLimit;
import com.mnemoscape.common.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @RateLimit(key = "auth:register", limit = 5, windowSeconds = 60,
            dimension = RateLimit.Dimension.IP,
            message = "注册请求过于频繁，请 1 分钟后再试")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(authService.register(request)));
    }

    @RateLimit(key = "auth:login", limit = 10, windowSeconds = 60,
            dimension = RateLimit.Dimension.IP,
            message = "登录失败次数过多，请 1 分钟后再试")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @RateLimit(key = "auth:refresh", limit = 20, windowSeconds = 60,
            dimension = RateLimit.Dimension.USER_OR_IP,
            message = "Token 刷新过于频繁，请稍后再试")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        if (bearer == null || !bearer.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("Missing refresh token"));
        }
        String refreshToken = bearer.substring(7).trim();
        if (refreshToken.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.badRequest("Missing refresh token"));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.refreshToken(refreshToken)));
    }

    @GetMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> profile(HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        return ResponseEntity.ok(ApiResponse.success(authService.getProfile(userId)));
    }

    @PutMapping("/profile")
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateProfile(
            @RequestBody java.util.Map<String, String> body, HttpServletRequest request) {
        String userId = RequestContext.requireUserId(request);
        String avatarUrl = body.get("avatarUrl");
        String backgroundImageUrl = body.get("backgroundImageUrl");
        UserProfileResponse updated = authService.updateProfile(userId, avatarUrl, backgroundImageUrl);
        return ResponseEntity.ok(ApiResponse.success(updated));
    }

    /**
     * 登出。读取当前请求里的 Bearer access token，把对应 jti 写入 Redis 黑名单。
     * 网关与所有下游服务都会从 Redis 看到该 jti 已撤销，立即拒绝后续请求。
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        if (bearer != null && bearer.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authService.logout(bearer.substring(7).trim());
        }
        return ResponseEntity.ok(ApiResponse.success("Signed out", null));
    }
}
