package com.mnemoscape.asset.service;

import com.mnemoscape.asset.model.StaticResource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class LocalResourceWatcher {
    private static final Logger log = LoggerFactory.getLogger(LocalResourceWatcher.class);

    private final List<StaticResource> cachedResources = new CopyOnWriteArrayList<>();
    private Path resourceDir;
    private Thread watchThread;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        this.resourceDir = resolveResourceDir();
        scanAllResources();
        startWatching();
    }

    @PreDestroy
    public void destroy() {
        this.running = false;
        if (watchThread != null) {
            watchThread.interrupt();
        }
    }

    public List<StaticResource> getResources() {
        return new ArrayList<>(cachedResources);
    }

    public Path getResourceDir() {
        return resourceDir;
    }

    private Path resolveResourceDir() {
        List<String> pathsToCheck = new ArrayList<>();
        String configured = firstNonBlank(
                System.getenv("MNEMOSCAPE_RESOURCE_DIR"),
                System.getProperty("mnemoscape.resource.dir")
        );
        if (configured != null) {
            pathsToCheck.add(configured);
        }
        pathsToCheck.add("../../resource");
        pathsToCheck.add("../resource");
        pathsToCheck.add("resource");

        for (String pathStr : pathsToCheck) {
            try {
                Path p = Paths.get(pathStr).toAbsolutePath().normalize();
                if (Files.exists(p) && Files.isDirectory(p)) {
                    log.info("[LocalResourceWatcher] Successfully resolved local resource directory: {}", p);
                    return p;
                }
            } catch (Exception e) {
                // Ignore and try next
            }
        }

        Path fallback = Paths.get("resource").toAbsolutePath();
        log.warn("[LocalResourceWatcher] Could not find a valid resource folder. Falling back to: {}", fallback);
        return fallback;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private synchronized void scanAllResources() {
        log.info("[LocalResourceWatcher] Scanning local static resources in: {}", resourceDir);
        List<StaticResource> newList = new ArrayList<>();

        // 历史命名：photo / video。新增子目录按需自动纳入（gif / audio / icon / music）。
        // 兼容用户把 GIF/图标/音频放在已有 photo/video 目录内时，按扩展名归类。
        scanFolder(resourceDir.resolve("photo"), "photo", newList);
        scanFolder(resourceDir.resolve("video"), "video", newList);
        scanFolder(resourceDir.resolve("gif"),   "gif",   newList);
        scanFolder(resourceDir.resolve("audio"), "audio", newList);
        scanFolder(resourceDir.resolve("music"), "audio", newList);
        scanFolder(resourceDir.resolve("icon"),  "icon",  newList);
        scanFolder(resourceDir.resolve("icons"), "icon",  newList);

        cachedResources.clear();
        cachedResources.addAll(newList);
        log.info("[LocalResourceWatcher] Scan completed. Scanned {} resources.", cachedResources.size());
    }

    private void scanFolder(Path folder, String defaultType, List<StaticResource> list) {
        if (!Files.exists(folder) || !Files.isDirectory(folder)) {
            log.debug("[LocalResourceWatcher] Folder does not exist: {}", folder);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry)) {
                    String filename = entry.getFileName().toString();
                    long size = Files.size(entry);
                    long lastModified = Files.getLastModifiedTime(entry).toMillis();
                    String type = classify(filename, defaultType);
                    // 注意：路径用目录名（defaultType），保证 controller 仍然能定位文件
                    String webPath = "/api/v1/assets/static/" + defaultType + "/" + filename;

                    list.add(new StaticResource(filename, webPath, type, "local", size, lastModified));
                }
            }
        } catch (IOException e) {
            log.error("[LocalResourceWatcher] Error scanning folder {}: {}", folder, e.getMessage());
        }
    }

    /** 按文件扩展名细化分类；目录给出兜底（例如 photo/ 内放了 .gif，应识别为 gif） */
    private String classify(String filename, String defaultType) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")) return "video";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg") || lower.endsWith(".flac")) return "audio";
        if (lower.endsWith(".svg")) return "icon";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")) return "photo";
        return defaultType;
    }

    private void startWatching() {
        watchThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                String[] subdirs = {"photo", "video", "gif", "audio", "music", "icon", "icons"};
                boolean registered = false;
                for (String sub : subdirs) {
                    Path p = resourceDir.resolve(sub);
                    if (Files.exists(p) && Files.isDirectory(p)) {
                        p.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                        registered = true;
                        log.info("[LocalResourceWatcher] Registered WatchService for: {}", p);
                    }
                }

                if (!registered) {
                    log.warn("[LocalResourceWatcher] No known subfolders found to register WatchService.");
                    return;
                }

                while (running) {
                    WatchKey key = watchService.take(); // Blocks until an event occurs
                    boolean needsRescan = false;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path filename = ev.context();
                        log.info("[LocalResourceWatcher] Detected local static change: {} - File: {}", kind, filename);
                        needsRescan = true;
                    }

                    if (needsRescan) {
                        // Rescan everything and sync cache
                        scanAllResources();
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        log.warn("[LocalResourceWatcher] WatchKey became invalid, attempting to re-register...");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.info("[LocalResourceWatcher] WatchService thread interrupted, stopping.");
            } catch (IOException e) {
                log.error("[LocalResourceWatcher] WatchService encountered an error: {}", e.getMessage());
            }
        }, "local-resource-watcher");

        watchThread.setDaemon(true);
        watchThread.start();
    }
}
