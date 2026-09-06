package com.campus.repair.modules.spatial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
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
        return node;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateNode(SpatialNode node) {
        SpatialNode existing = spatialNodeMapper.selectById(node.getId());
        if (existing == null) {
            throw new BusinessException("空间节点不存在");
        }

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
        SpatialNode node = spatialNodeMapper.selectById(id);
        if (node == null) throw new BusinessException("空间节点不存在");
        node.setStatus(status);
        spatialNodeMapper.updateById(node);
    }

    public void deleteNode(Long id) {
        Long childCount = spatialNodeMapper.selectCount(new LambdaQueryWrapper<SpatialNode>()
                .eq(SpatialNode::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException("当前节点下存在子节点，无法直接删除");
        }
        spatialNodeMapper.deleteById(id);
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
