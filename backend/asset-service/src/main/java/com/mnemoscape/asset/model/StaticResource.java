package com.mnemoscape.asset.model;

public class StaticResource {
    private String name;
    private String path;
    private String type;
    private String source;
    private long size;
    private long lastModified;

    public StaticResource() {
    }

    public StaticResource(String name, String path, String type, long size, long lastModified) {
        this(name, path, type, "local", size, lastModified);
    }

    public StaticResource(String name, String path, String type, String source, long size, long lastModified) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.source = source;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
}
