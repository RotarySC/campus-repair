package com.campus.repair.modules.assistant.controller;

import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.assistant.dto.AssistantSuggestRequest;
import com.campus.repair.modules.assistant.service.AssistantService;
import com.campus.repair.modules.assistant.vo.AssistantFaqItemVO;
import com.campus.repair.modules.assistant.vo.AssistantOrderProgressVO;
import com.campus.repair.modules.assistant.vo.AssistantStatusVO;
import com.campus.repair.modules.assistant.vo.AssistantSuggestionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智能助手。
 *
 * <p><b>鉴权按职能分档（口径见 CONTEXT.md）</b>：
 * <ul>
 *   <li>推荐与进度速查是**报修人专属**（{@code hasRole('REPORTER')}）——别的职能不提单，
 *       给它们开口子只会白扩攻击面；</li>
 *   <li>状态与制度问答任何已登录用户都要用（助手抽屉对全角色可见，内容按职能裁剪），
 *       因此不设 {@code @PreAuthorize}，由安全链的 {@code anyRequest().authenticated()} 兜住。</li>
 * </ul>
 */
@Tag(name = "智能助手", description = "故障类别推荐、快捷入口上下文、制度问答与我的工单进度速查")
@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @Operation(summary = "助手运行状态（前端据此决定是否渲染入口；也是运行期回滚开关）")
    @GetMapping("/status")
    public R<AssistantStatusVO> status() {
        return R.success(assistantService.status());
    }

    @Operation(summary = "根据故障描述推荐故障细类（含工种、承诺时效与自助排查提示）")
    @PostMapping("/suggest")
    @PreAuthorize("hasRole('REPORTER')")
    public R<AssistantSuggestionVO> suggest(@Valid @RequestBody AssistantSuggestRequest request) {
        return R.success(assistantService.suggest(request.getDescription()));
    }

    @Operation(summary = "制度问答（时效/加急/防重/超时核销等，数值取自系统参数）")
    @GetMapping("/faq")
    public R<List<AssistantFaqItemVO>> faq() {
        return R.success(assistantService.faq());
    }

    @Operation(summary = "我的工单进度速查（只取本人最近一张单）")
    @GetMapping("/my-progress")
    @PreAuthorize("hasRole('REPORTER')")
    public R<AssistantOrderProgressVO> myProgress(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(assistantService.myProgress(user.getUserId()));
    }
}
