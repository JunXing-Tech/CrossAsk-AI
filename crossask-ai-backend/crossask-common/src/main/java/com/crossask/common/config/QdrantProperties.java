package com.crossask.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Qdrant 连接配置（读 crossask.qdrant.* 配置）
 */
@ConfigurationProperties(prefix = "crossask.qdrant")
public class QdrantProperties {

    private String host;
    private int port;
    private String collection;
    private int dimension;
    private String distance;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getDimension() {
        return dimension;
    }

    public void setDimension(int dimension) {
        this.dimension = dimension;
    }

    public String getDistance() {
        return distance;
    }

    public void setDistance(String distance) {
        this.distance = distance;
    }
}
