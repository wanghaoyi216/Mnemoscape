package com.mnemoscape.asset.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class StaticResource {
    private String name;
    private String path;
    private String type;
    private String source;
    private long size;
    private long lastModified;

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
}
