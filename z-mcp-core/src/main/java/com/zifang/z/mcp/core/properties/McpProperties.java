package com.zifang.z.mcp.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * z.mcp.* 配置.
 *
 * <p>basePath: 网关路径前缀(默认 "").
 * <p>exposeAdmin: 是否暴露 /mcp/admin/* 控制面(默认 false).
 * <p>healthCheckIntervalSeconds: server 健康检查周期(默认 60, 0 = 禁用).
 */
@ConfigurationProperties(prefix = "z.mcp")
public class McpProperties {

    private String basePath = "";
    private boolean exposeAdmin = false;
    private int healthCheckIntervalSeconds = 60;
    private List<ServerConfig> servers = new ArrayList<ServerConfig>();

    public String getBasePath() { return basePath; }
    public void setBasePath(String basePath) { this.basePath = basePath; }

    public boolean isExposeAdmin() { return exposeAdmin; }
    public void setExposeAdmin(boolean exposeAdmin) { this.exposeAdmin = exposeAdmin; }

    public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
    public void setHealthCheckIntervalSeconds(int s) { this.healthCheckIntervalSeconds = s; }

    public List<ServerConfig> getServers() { return servers; }
    public void setServers(List<ServerConfig> servers) { this.servers = servers; }

    public static class ServerConfig {
        private String name;
        private String endpoint;
        private String transport = "http+sse";

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
    }
}