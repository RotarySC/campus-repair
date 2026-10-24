package com.campus.repair.modules.spatial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.audit.constant.AuditConstants;
import com.campus.repair.modules.audit.service.OperationActor;
import com.campus.repair.modules.audit.service.OperationLogService;
import com.campus.repair.modules.audit.service.OperationSnapshotMaps;
import com.campus.repair.modules.spatial.entity.SysConfig;
import com.campus.repair.modules.spatial.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper configMapper;
    private final OperationLogService operationLogService;

    public Map<String, String> getAllConfigs() {
        return configMapper.selectList(null).stream()
                .collect(Collectors.toMap(SysConfig::getConfigKey, SysConfig::getConfigValue));
    }

    public List<SysConfig> listConfigs() {
        return configMapper.selectList(null);
    }

    public String getValue(String key, String defaultValue) {
        SysConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        return config != null ? config.getConfigValue() : defaultValue;
    }

    public int getIntValue(String key, int defaultValue) {
        String val = getValue(key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public void updateConfig(String key, String value) {
        updateConfig(key, value, null);
    }

    /**
     * 写入/更新系统参数，并在同一事务里写审计。
     *
     * <p>仅平台管理可调用（控制器注解 `hasRole('PLATFORM_ADMIN')`），所以操作人职能
     * 由控制器显式传 {@code PLATFORM_ADMIN}，不按「持有职能」猜测。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(String key, String value, OperationActor actor) {
        SysConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        java.util.Map<String, Object> before = null;
        String action;
        if (config != null) {
            before = OperationSnapshotMaps.of(AuditConstants.TARGET_SYS_CONFIG, config);
            config.setConfigValue(value);
            configMapper.updateById(config);
            action = AuditConstants.ACTION_UPDATE;
        } else {
            SysConfig newConfig = new SysConfig();
            newConfig.setConfigKey(key);
            newConfig.setConfigValue(value);
            configMapper.insert(newConfig);
            config = newConfig;
            action = AuditConstants.ACTION_CREATE;
        }
        operationLogService.record(AuditConstants.MODULE_CONFIG, action,
                AuditConstants.TARGET_SYS_CONFIG, config.getId(), config.getConfigKey(),
                before, OperationSnapshotMaps.of(AuditConstants.TARGET_SYS_CONFIG, config), actor, null);
    }
}
