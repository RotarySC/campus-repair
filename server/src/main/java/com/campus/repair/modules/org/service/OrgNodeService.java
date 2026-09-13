package com.campus.repair.modules.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.org.entity.OrgNode;
import com.campus.repair.modules.org.mapper.OrgNodeMapper;
import com.campus.repair.modules.org.vo.OrgNodeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 单位/组织字典的**只读**查询服务（个人中心的「学院/部门」逐级下钻取数）。
 *
 * <p>刻意只暴露读能力：字典维护不在本轮范围内，避免新增无人使用的写路径
 * （写路径一旦开放就要一并处理 fullPathName 级联、停用校验与引用完整性）。
 *
 * <p>按身份剪枝：{@code audience} 在数据里声明分支归属，服务端据此过滤，而不是靠调用方
 * 或前端去认「教职工」这个字符串——学生只看到年级分支、教工与师傅只看到教职工分支。
 */
@Service
@RequiredArgsConstructor
public class OrgNodeService {

    /** 年级分支 */
    public static final String AUDIENCE_STUDENT = "STUDENT";
    /** 教职工分支 */
    public static final String AUDIENCE_STAFF = "STAFF";

    private static final Set<String> SUPPORTED_AUDIENCES = Set.of(AUDIENCE_STUDENT, AUDIENCE_STAFF);

    private final OrgNodeMapper orgNodeMapper;

    public List<OrgNodeVO> getOrgTree(boolean onlyEnabled, String audience) {
        String scope = normalizeAudience(audience);

        LambdaQueryWrapper<OrgNode> query = new LambdaQueryWrapper<OrgNode>()
                .orderByAsc(OrgNode::getSortOrder)
                .orderByAsc(OrgNode::getId);
        if (onlyEnabled) {
            query.eq(OrgNode::getStatus, 1);
        }

        List<OrgNode> allNodes = orgNodeMapper.selectList(query);
        List<OrgNodeVO> tree = buildTree(allNodes, 0L);
        return scope == null ? tree : pruneByAudience(tree, scope);
    }

    /**
     * 归一化并校验身份范围；空串按「不限制」处理，未知取值直接报参数错误
     * （静默忽略非法取值会让调用方以为过滤生效了，实际拿到全量树）。
     */
    private String normalizeAudience(String audience) {
        if (!StringUtils.hasText(audience)) {
            return null;
        }
        String scope = audience.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_AUDIENCES.contains(scope)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    "未知的单位字典身份范围: " + audience);
        }
        return scope;
    }

    /**
     * 逐层剪枝：任何一层显式声明了不匹配的 audience 都会被剪掉，声明为 null 的节点视为继承/不限。
     * （当前种子只在顶层分支上标注 audience，逐层实现是为了让语义在将来往子节点加标注时也不会悄悄失效。）
     */
    private List<OrgNodeVO> pruneByAudience(List<OrgNodeVO> nodes, String audience) {
        List<OrgNodeVO> result = new ArrayList<>();
        for (OrgNodeVO node : nodes) {
            if (node.getAudience() != null && !audience.equals(node.getAudience())) {
                continue;
            }
            node.setChildren(pruneByAudience(node.getChildren(), audience));
            result.add(node);
        }
        return result;
    }

    private List<OrgNodeVO> buildTree(List<OrgNode> nodes, Long parentId) {
        List<OrgNodeVO> result = new ArrayList<>();
        for (OrgNode node : nodes) {
            if (Objects.equals(node.getParentId(), parentId)) {
                OrgNodeVO vo = new OrgNodeVO();
                BeanUtils.copyProperties(node, vo);
                vo.setChildren(buildTree(nodes, node.getId()));
                result.add(vo);
            }
        }
        return result;
    }
}
