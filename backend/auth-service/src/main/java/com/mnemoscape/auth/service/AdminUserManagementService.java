package com.mnemoscape.auth.service;

import com.mnemoscape.auth.model.entity.User;
import com.mnemoscape.auth.repository.UserRepository;
import com.mnemoscape.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理后台 — 用户管理业务逻辑。
 *
 * <p>承载原 {@code AdminUserManagementController} 中的全部事务方法与查询逻辑:
 * <ul>
 *   <li>分页 / 搜索 / 过滤角色查询</li>
 *   <li>更改单用户角色(USER ↔ ADMIN)</li>
 *   <li>验证 / 禁用单用户</li>
 *   <li>删除单用户(hard delete)</li>
 *   <li>批量删除</li>
 * </ul>
 *
 * <p>事务边界:每个写操作方法贴 {@link Transactional},确保查询+修改+保存在同一事务。
 * 批量删除整个循环在单一事务内(与原 Controller 行为一致)。
 *
 * <p>业务校验(状态值合法性、资源存在性 404、防止降级/删除最后一个 ADMIN、
 * 防止管理员删除自己)全部在此层完成。HTTP 格式校验(ids 为空、size 超限、
 * 参数缺失)留在 Controller 快速失败。
 */
@Slf4j
@Service
public class AdminUserManagementService {

    private final UserRepository userRepository;

    public AdminUserManagementService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 分页查询用户列表(支持搜索 / 角色过滤 / verified 过滤 / 排序)。
     *
     * <p>角色过滤值合法性({@code USER}/{@code ADMIN})作为业务校验在此层完成。
     *
     * @param page     页码(已由 Controller 夹紧到 >= 0)
     * @param size     每页大小(已由 Controller 夹紧到 [1, MAX_PAGE_SIZE])
     * @param search   用户名 / 邮箱模糊匹配,null 或空表示不过滤
     * @param role     角色精确匹配,null 或空表示不过滤
     * @param verified verified 精确匹配,null 表示不过滤
     * @param sortBy   排序字段
     * @param sortDir  排序方向("asc" / 其他视为 desc)
     * @return 分页结果
     */
    public Page<User> listUsers(int page, int size, String search, String role,
                                Boolean verified, String sortBy, String sortDir) {
        Sort sort = "asc".equalsIgnoreCase(sortDir)
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Specification<User> spec = (root, q, cb) -> cb.conjunction();
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)
            ));
        }
        if (role != null && !role.isBlank()) {
            String r = role.trim().toUpperCase();
            if (!r.equals("USER") && !r.equals("ADMIN")) {
                throw new BizException(400, "INVALID_ROLE_FILTER");
            }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("role"), r));
        }
        if (verified != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("verified"), verified));
        }

        return userRepository.findAll(spec, PageRequest.of(page, size, sort));
    }

    /**
     * 查询单个用户(404 由 Controller 抛或在此抛均可;统一在此抛)。
     */
    public User getUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
    }

    /**
     * 修改单用户角色。
     *
     * <p>业务校验:目标用户存在;角色值为 {@code USER}/{@code ADMIN};
     * 防止把最后一个 ADMIN 降级。
     *
     * @param userId 目标用户 id
     * @param role   新角色(已由 Controller 校验非空;值合法性在此层校验)
     * @return 更新后的 User 实体
     */
    @Transactional
    public User changeRole(String userId, String role) {
        String r = role.trim().toUpperCase();
        if (!r.equals("USER") && !r.equals("ADMIN")) {
            throw new BizException(400, "INVALID_ROLE");
        }
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        // 防止把最后一个 ADMIN 降级
        if (r.equals("USER") && "ADMIN".equals(u.getRole())) {
            long adminCount = userRepository.findAll(
                    (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
            ).size();
            if (adminCount <= 1) {
                throw new BizException(400, "CANNOT_DEMOTE_LAST_ADMIN");
            }
        }
        u.setRole(r);
        u.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    /**
     * 修改 verified 状态(管理员手动验证 / 冻结用户)。
     *
     * @param userId   目标用户 id
     * @param verified 新 verified 值
     * @return 更新后的 User 实体
     */
    @Transactional
    public User changeVerified(String userId, boolean verified) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        u.setVerified(verified);
        u.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    /**
     * 删除单个用户(hard delete)。
     *
     * <p>业务校验:目标用户存在;防止删除最后一个 ADMIN;防止管理员删除自己。
     *
     * @param userId   目标用户 id
     * @param callerId 调用者(管理员)id,来自 HTTP 头 {@code X-User-Id},可为 null
     */
    @Transactional
    public void deleteUser(String userId, String callerId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BizException(404, "USER_NOT_FOUND"));
        // 防止删除最后一个 ADMIN
        if ("ADMIN".equals(u.getRole())) {
            long adminCount = userRepository.findAll(
                    (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
            ).size();
            if (adminCount <= 1) {
                throw new BizException(400, "CANNOT_DELETE_LAST_ADMIN");
            }
        }
        // 防止管理员删除自己
        if (callerId != null && callerId.equals(userId)) {
            throw new BizException(400, "CANNOT_DELETE_SELF");
        }
        userRepository.delete(u);
    }

    /**
     * 批量删除用户。整个循环在单一事务内(与原 Controller 行为一致):
     * 任何单条失败不回滚整体,而是计入 failed 列表;只有方法级异常才会回滚。
     *
     * <p>校验/计数规则:
     * <ul>
     *   <li>null / 空 / 等于 callerId 的 id 直接计入 failed</li>
     *   <li>删除最后一个 ADMIN 会破坏不变量,该条计入 failed 并跳过</li>
     *   <li>动态维护剩余 ADMIN 计数,避免删完 ADMIN 后误判</li>
     * </ul>
     *
     * @param ids      待删除 id 列表(已由 Controller 校验非空 / size 上限)
     * @param callerId 调用者(管理员)id,可为 null
     * @return 删除结果(成功数 + 失败 id 列表)
     */
    @Transactional
    public BatchDeleteResult batchDelete(List<String> ids, String callerId) {
        long adminCount = userRepository.findAll(
                (root, q, cb) -> cb.equal(root.get("role"), "ADMIN")
        ).size();

        int deleted = 0;
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || id.equals(callerId)) {
                failed.add(id);
                continue;
            }
            try {
                var opt = userRepository.findById(id);
                if (opt.isPresent()) {
                    User u = opt.get();
                    if ("ADMIN".equals(u.getRole()) && adminCount <= 1) {
                        failed.add(id);
                        continue;
                    }
                    if ("ADMIN".equals(u.getRole())) adminCount--;
                    userRepository.delete(u);
                    deleted++;
                } else {
                    failed.add(id);
                }
            } catch (Exception e) {
                log.warn("[admin] batch-delete user failed for {}: {}", id, e.toString());
                failed.add(id);
            }
        }
        return new BatchDeleteResult(deleted, failed);
    }

    /** 批量删除结果。 */
    public record BatchDeleteResult(int deleted, List<String> failed) {}
}
