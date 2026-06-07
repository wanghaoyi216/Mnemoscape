package com.mnemoscape.auth.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户 3D 刻画请求 DTO。
 *
 * <p>用户在前端填写自我描述，后端 AI 服务解析并生成角色特征。
 */
public class AvatarProfileRequest {

    @NotBlank(message = "自我描述不能为空")
    @Size(min = 10, max = 1000, message = "自我描述需在 10 到 1000 字之间")
    private String selfDescription;

    /** 是否公开展示（默认 false）。 */
    private Boolean isPublic = false;

    public AvatarProfileRequest() {}

    public AvatarProfileRequest(String selfDescription, Boolean isPublic) {
        this.selfDescription = selfDescription;
        this.isPublic = isPublic;
    }

    public String getSelfDescription() { return selfDescription; }
    public void setSelfDescription(String selfDescription) { this.selfDescription = selfDescription; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }
}
