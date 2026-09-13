package com.campus.repair.modules.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.constant.IdentityCodes;
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
 * <p>按**组织条线**剪枝：{@code audience} 在数据里声明分支归属，服务端据此过滤，
 * 而不是靠调用方或前端去认「所属单位」这个字符串。取值与 {@code sys_user.user_type}
 * 完全一致（`STUDENT`/`FACULTY`/`LOGISTICS`/`ADMIN`），**同一个值、不做二次映射**，
 * 因此前端直接把用户的 userType 当 audience 传进来（口径见仓库根 `CONTEXT.md`）。
 *
 * <p>标注位置：种子把 audience 打在**二级单位节点**上（后勤维修中心=LOGISTICS、
 * 各学院=FACULTY、信息中心=ADMIN），子节点（班组/系）留 null 继承；根节点与年级分支
 * 仍按「顶层分支」标注。逐层实现见 {@link #pruneByAudience}。
 */
@Service
@RequiredArgsConstructor
public class OrgNodeService {

    /** 学生（年级 → 学院）分支 */
    public static final String AUDIENCE_STUDENT = IdentityCodes.BRANCH_STUDENT;
    /** 教职工·教学科研（各学院）分支 */
    public static final String AUDIENCE_FACULTY = IdentityCodes.BRANCH_FACULTY;
    /** 教职工·后勤保障（后勤保障处/后勤维修中心/班组）分支 */
    public static final String AUDIENCE_LOGISTICS = IdentityCodes.BRANCH_LOGISTICS;
    /** 教职工·行政信息化（信息中心等）分支 */
    public static final String AUDIENCE_ADMIN = IdentityCodes.BRANCH_ADMIN;

    private static final Set<String> SUPPORTED_AUDIENCES = Set.of(
            AUDIENCE_STUDENT, AUDIENCE_FACULTY, AUDIENCE_LOGISTICS, AUDIENCE_ADMIN);

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
     *
     * <p><b>空的中间节点一并剪掉</b>：{@code 所属单位} 这个根节点本身不限条线（它的三条线在二级
     * 单位上声明），若只按 audience 判断，它会作为"没有内容的空壳"出现在**每一条**条线的树里
     * （学生视角也会看到「所属单位」）。因此这里额外加一条：原本有子节点、剪枝后一个不剩的
     * 中间节点不再返回——「我这条线下一无所属」时，那个父节点本来就不该出现。
     */
    private List<OrgNodeVO> pruneByAudience(List<OrgNodeVO> nodes, String audience) {
        List<OrgNodeVO> result = new ArrayList<>();
        for (OrgNodeVO node : nodes) {
            if (node.getAudience() != null && !audience.equals(node.getAudience())) {
                continue;
            }
            List<OrgNodeVO> children = node.getChildren();
            List<OrgNodeVO> prunedChildren = pruneByAudience(children, audience);
            if (!children.isEmpty() && prunedChildren.isEmpty()) {
                continue;
            }
            node.setChildren(prunedChildren);
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
