package com.mnemoscape.common;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * Mnemoscape 自动环境加载器 — 根除本地 IntelliJ debug/run 未加载 .env.workpc 的隐患。
 * 启动时在上溯 3 层级目录内搜索并解析 .env.workpc，将未设置的系统属性与变量强行补齐。
 */
public class EnvLoader {
    public static void load() {
        File dir = new File(System.getProperty("user.dir"));
        File envFile = null;
        for (int i = 0; i < 4; i++) {
            File f = new File(dir, ".env.workpc");
            if (f.exists()) {
                envFile = f;
                break;
            }
            f = new File(dir, "backend/.env.workpc");
            if (f.exists()) {
                envFile = f;
                break;
            }
            dir = dir.getParentFile();
            if (dir == null) {
                break;
            }
        }

        if (envFile != null) {
            System.out.println("[EnvLoader] Loading environment variables from: " + envFile.getAbsolutePath());
            try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int eqIdx = line.indexOf('=');
                    if (eqIdx != -1) {
                        String key = line.substring(0, eqIdx).trim();
                        String val = line.substring(eqIdx + 1).trim();
                        // 去除双引号/单引号
                        if (val.startsWith("\"") && val.endsWith("\"")) {
                            val = val.substring(1, val.length() - 1);
                        } else if (val.startsWith("'") && val.endsWith("'")) {
                            val = val.substring(1, val.length() - 1);
                        }
                        
                        // 仅在当前环境无此变量时注入，保障容器/外部 shell 的 env 优先级更高
                        if (System.getenv(key) == null && System.getProperty(key) == null) {
                            System.setProperty(key, val);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[EnvLoader] Failed to read .env.workpc: " + e.getMessage());
            }
        } else {
            System.out.println("[EnvLoader] Warning: .env.workpc file not found in hierarchy of " + System.getProperty("user.dir"));
        }
    }
}
