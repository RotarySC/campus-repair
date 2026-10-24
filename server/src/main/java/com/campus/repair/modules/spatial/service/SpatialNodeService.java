package com.campus.repair.modules.spatial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.modules.audit.constant.AuditConstants;
import com.campus.repair.modules.audit.service.OperationActor;
import com.campus.repair.modules.audit.service.OperationLogService;
import com.campus.repair.modules.audit.service.OperationSnapshotMaps;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.mapper.SpatialNodeMapper;
import com.campus.repair.modules.spatial.vo.SpatialNodeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SpatialNodeService {

    private final SpatialNodeMapper spatialNodeMapper;
    private final OperationLogService operationLogService;

    public List<SpatialNodeVO> getSpatialTree(boolean onlyEnabled) {
        LambdaQueryWrapper<SpatialNode> query = new LambdaQueryWrapper<SpatialNode>()
                .orderByAsc(SpatialNode::getSortOrder)
                .orderByAsc(SpatialNode::getId);
        if (onlyEnabled) {
            query.eq(SpatialNode::getStatus, 1);
        }

        List<SpatialNode> allNodes = spatialNodeMapper.selectList(query);
        return buildTree(allNodes, 0L);
    }

    private List<SpatialNodeVO> buildTree(List<SpatialNode> nodes, Long parentId) {
        List<SpatialNodeVO> result = new ArrayList<>();
        for (SpatialNode node : nodes) {
            if (Objects.equals(node.getParentId(), parentId)) {
                SpatialNodeVO vo = new SpatialNodeVO();
                BeanUtils.copyProperties(node, vo);
                vo.setChildren(buildTree(nodes, node.getId()));
                result.add(vo);
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public SpatialNode createNode(SpatialNode node) {
        return createNode(node, null);
    }

    /**
     * 创建空间节点，并在**同一事务**里写入审计行（无操作人上下文时不落审计）。
     *
     * @param actor 当前操作人；由控制器从 {@code @AuthenticationPrincipal} 生成
     */
    @Transactional(rollbackFor = Exception.class)
    public SpatialNode createNode(SpatialNode node, OperationActor actor) {
        if (!StringUtils.hasText(node.getName())) {
            throw new BusinessException("空间节点名称不能为空");
        }

        String parentPath = "";
        if (node.getParentId() != null && node.getParentId() > 0) {
            SpatialNode parent = spatialNodeMapper.selectById(node.getParentId());
            if (parent == null) {
                throw new BusinessException("上级空间节点不存在");
            }
            parentPath = parent.getFullPathName() + "/";
            node.setLevelType(parent.getLevelType() + 1);
        } else {
            node.setParentId(0L);
            node.setLevelType(1);
        }

        node.setFullPathName(parentPath + node.getName().trim());
        if (node.getStatus() == null) node.setStatus(1);
        if (node.getSortOrder() == null) node.setSortOrder(0);

        spatialNodeMapper.insert(node);
        operationLogService.record(AuditConstants.MODULE_SPATIAL, AuditConstants.ACTION_CREATE,
                AuditConstants.TARGET_SPATIAL_NODE, node.getId(), node.getName(),
                null, OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, node), actor, null);
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateNode(SpatialNode node) {
        updateNode(node, null);
    }

    /**
     * 更新空间节点。审计的前值在**字段被覆盖之前**取快照，后值在覆盖之后取。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateNode(SpatialNode node, OperationActor actor) {
        SpatialNode existing = spatialNodeMapper.selectById(node.getId());
        if (existing == null) {
            throw new BusinessException("空间节点不存在");
        }
        java.util.Map<String, Object> before =
                OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, existing);

        String oldFullPath = existing.getFullPathName();
        String parentPath = "";
        if (existing.getParentId() > 0) {
            SpatialNode parent = spatialNodeMapper.selectById(existing.getParentId());
            if (parent != null) {
                parentPath = parent.getFullPathName() + "/";
            }
        }
        String newFullPath = parentPath + node.getName().trim();

        existing.setName(node.getName().trim());
        existing.setFullPathName(newFullPath);
        existing.setBuildingNature(node.getBuildingNature());
        existing.setLeaderUserId(node.getLeaderUserId());
        if (node.getSortOrder() != null) existing.setSortOrder(node.getSortOrder());
        if (node.getStatus() != null) existing.setStatus(node.getStatus());

        spatialNodeMapper.updateById(existing);

        // 若名称变更，级联更新其所有下级子孙节点的 fullPathName
        if (!oldFullPath.equals(newFullPath)) {
            updateChildrenFullPath(existing.getId(), newFullPath);
        }

        operationLogService.record(AuditConstants.MODULE_SPATIAL, AuditConstants.ACTION_UPDATE,
                AuditConstants.TARGET_SPATIAL_NODE, existing.getId(), existing.getName(),
                before, OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, existing), actor, null);
    }

    private void updateChildrenFullPath(Long parentId, String parentPath) {
        List<SpatialNode> children = spatialNodeMapper.selectList(new LambdaQueryWrapper<SpatialNode>()
                .eq(SpatialNode::getParentId, parentId));
        for (SpatialNode child : children) {
            String childNewPath = parentPath + "/" + child.getName();
            child.setFullPathName(childNewPath);
            spatialNodeMapper.updateById(child);
            updateChildrenFullPath(child.getId(), childNewPath);
        }
    }

    public void updateNodeStatus(Long id, Integer status) {
        updateNodeStatus(id, status, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateNodeStatus(Long id, Integer status, OperationActor actor) {
        SpatialNode node = spatialNodeMapper.selectById(id);
        if (node == null) throw new BusinessException("空间节点不存在");
        java.util.Map<String, Object> before =
                OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, node);
        Integer oldStatus = node.getStatus();
        node.setStatus(status);
        spatialNodeMapper.updateById(node);
        operationLogService.record(AuditConstants.MODULE_SPATIAL, AuditConstants.ACTION_STATUS,
                AuditConstants.TARGET_SPATIAL_NODE, node.getId(), node.getName(),
                before, OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, node), actor,
                "启用状态 " + oldStatus + " → " + status);
    }

    public void deleteNode(Long id) {
        deleteNode(id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long id, OperationActor actor) {
        Long childCount = spatialNodeMapper.selectCount(new LambdaQueryWrapper<SpatialNode>()
                .eq(SpatialNode::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException("当前节点下存在子节点，无法直接删除");
        }
        // 删除前取快照：软删之后 before 里的名称仍可读，正好补上 target_name
        SpatialNode existing = spatialNodeMapper.selectById(id);
        spatialNodeMapper.deleteById(id);
        if (existing != null) {
            operationLogService.record(AuditConstants.MODULE_SPATIAL, AuditConstants.ACTION_DELETE,
                    AuditConstants.TARGET_SPATIAL_NODE, existing.getId(), existing.getName(),
                    OperationSnapshotMaps.of(AuditConstants.TARGET_SPATIAL_NODE, existing), null, actor, null);
        }
    }

    public SpatialNode getNodeById(Long id) {
        return spatialNodeMapper.selectById(id);
    }

    public Long resolveRootCampusId(Long spaceId) {
        SpatialNode current = spatialNodeMapper.selectById(spaceId);
        if (current == null) return null;
        while (current.getParentId() != null && current.getParentId() > 0) {
            SpatialNode parent = spatialNodeMapper.selectById(current.getParentId());
            if (parent == null) break;
            current = parent;
        }
        return current.getId();
    }
}
