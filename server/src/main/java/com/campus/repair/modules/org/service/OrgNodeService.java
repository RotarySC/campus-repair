package com.campus.repair.modules.org.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.org.entity.OrgNode;
import com.campus.repair.modules.org.mapper.OrgNodeMapper;
import com.campus.repair.modules.org.vo.OrgNodeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 单位/组织字典的**只读**查询服务（个人中心的「学院/部门」逐级下钻取数）。
 *
 * <p>刻意只暴露读能力：字典维护不在本轮范围内，避免新增无人使用的写路径
 * （写路径一旦开放就要一并处理 fullPathName 级联、停用校验与引用完整性）。
 */
@Service
@RequiredArgsConstructor
public class OrgNodeService {

    private final OrgNodeMapper orgNodeMapper;

    public List<OrgNodeVO> getOrgTree(boolean onlyEnabled) {
        LambdaQueryWrapper<OrgNode> query = new LambdaQueryWrapper<OrgNode>()
                .orderByAsc(OrgNode::getSortOrder)
                .orderByAsc(OrgNode::getId);
        if (onlyEnabled) {
            query.eq(OrgNode::getStatus, 1);
        }

        List<OrgNode> allNodes = orgNodeMapper.selectList(query);
        return buildTree(allNodes, 0L);
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
