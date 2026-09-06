package com.campus.repair.modules.spatial.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.spatial.entity.SysConfig;
import com.campus.repair.modules.spatial.mapper.SysConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final SysConfigMapper configMapper;

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
        SysConfig config = configMapper.selectOne(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key));
        if (config != null) {
            config.setConfigValue(value);
            configMapper.updateById(config);
        } else {
            SysConfig newConfig = new SysConfig();
            newConfig.setConfigKey(key);
            newConfig.setConfigValue(value);
            configMapper.insert(newConfig);
        }
    }
}
