package com.mz.jarboot.cloud;

import com.mz.jarboot.service.ServerRuntimeService;
import com.mz.jarboot.utils.SettingUtils;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 集群配置
 * @author mazheng
 */
@Component
public class ClusterConfig {
    @Autowired
    private ServerRuntimeService runtimeService;
    /** 集群列表 */
    private Set<String> hosts = new HashSet<>(16);
    /** 主节点 */
    private String masterHost;

    public Set<String> getHosts() {
        return hosts;
    }

    public String getMasterHost() {
        return masterHost;
    }

    private File getClusterConfigFile() {
        return FileUtils.getFile(SettingUtils.getHomePath(), "conf", "cluster.conf");
    }
    @PostConstruct
    public void init() {
        final String notePrefix = "#";
        try {
            List<String> lines = FileUtils.readLines(getClusterConfigFile(), StandardCharsets.UTF_8);
            if (CollectionUtils.isEmpty(lines)) {
                return;
            }
            lines.forEach(line -> {
                line = line.trim();
                if (line.startsWith(notePrefix)) {
                    return;
                }
                // 检查host合法性
                hosts.add(line);
            });
        } catch (Exception e) {
            // ignore
        }
    }
}