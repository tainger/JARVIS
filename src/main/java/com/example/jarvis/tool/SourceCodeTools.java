package com.example.jarvis.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 源码分析工具集：让 ReAct Agent 自主读取 repo/ 目录下已拉取的外部项目源码。
 * 沙箱基目录为 ${user.dir}/repo/，Agent 无法访问 JARVIS 自身源码或系统文件。
 */
@Component
public class SourceCodeTools {

    private static final Logger log = LoggerFactory.getLogger(SourceCodeTools.class);

    private static final Path REPO_DIR = Paths.get(System.getProperty("user.dir"), "repo").toAbsolutePath().normalize();

    private static final int MAX_LINES = 200;
    private static final int MAX_BYTES = 50 * 1024;
    private static final int MAX_GREP_RESULTS = 30;
    private static final int MAX_LIST_ITEMS = 200;

    private static final List<String> SKIP_DIRS = List.of(
            ".git", "target", "node_modules", "build", "dist", ".gradle", ".idea", ".vscode"
    );

    private static final List<String> TEXT_EXTENSIONS = List.of(
            ".java", ".jsx", ".js", ".ts", ".tsx", ".xml", ".properties",
            ".md", ".sh", ".yml", ".yaml", ".json", ".sql", ".html", ".css", ".scss"
    );

    private static final List<String> SENSITIVE_FILES = List.of(
            ".env", "application-prod.properties", "application-prod.yml",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"
    );

    static {
        log.info("SourceCodeTools initialized, sandbox base dir: {}", REPO_DIR);
    }

    // ==================== Tool Methods ====================

    @Tool(description = "Read the content of a source code file. The path is relative to the repo/ directory (e.g. 'nacos/naming/src/main/java/.../Foo.java'). Supports pagination via startLine and maxLines parameters.")
    public String readFile(
            @ToolParam(name = "path", description = "File path relative to repo/ directory, e.g. 'nacos/naming/src/main/java/com/alibaba/nacos/naming/controllers/NamingController.java'") String path,
            @ToolParam(name = "startLine", description = "Starting line number (1-based), default 1. Use for pagination on large files.") Integer startLine,
            @ToolParam(name = "maxLines", description = "Maximum lines to return, default 200") Integer maxLines) {

        Path resolved = resolveSafePath(path);
        if (resolved == null) {
            return "Error: path traversal denied. Only files under repo/ are accessible.";
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return "Error: not a regular file: " + path;
        }
        if (isSensitiveFile(resolved)) {
            return "Error: access to sensitive file denied: " + path;
        }

        int start = startLine != null && startLine > 0 ? startLine : 1;
        int max = maxLines != null && maxLines > 0 ? Math.min(maxLines, MAX_LINES) : MAX_LINES;

        try {
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int totalLines = lines.size();
            int fromIndex = start - 1;
            if (fromIndex >= totalLines) {
                return "Error: startLine " + start + " exceeds total lines " + totalLines;
            }
            int toIndex = Math.min(fromIndex + max, totalLines);
            List<String> result = lines.subList(fromIndex, toIndex);

            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(path).append(" (lines ").append(start).append("-").append(start + result.size() - 1).append(" of ").append(totalLines).append(")\n\n");
            for (int i = 0; i < result.size(); i++) {
                sb.append(String.format("%4d| %s%n", start + i, result.get(i)));
            }

            // 字节数检查
            String content = sb.toString();
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                content = content.substring(0, Math.min(content.length(), MAX_BYTES / 3));
                content += "\n... [content truncated at 50KB limit]\n";
            }

            if (toIndex < totalLines) {
                content += "\n[Truncated: " + totalLines + " total lines, returned " + (toIndex - fromIndex) + " lines from line " + start + ". Use startLine=" + (toIndex + 1) + " to read next page.]";
            }

            return content;
        } catch (IOException e) {
            log.warn("readFile error for {}: {}", path, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(description = "List files and subdirectories in a directory under repo/. The dir path is relative to repo/ (e.g. 'nacos/' or 'nacos/naming/src/main/java/'). Returns file names with [DIR] or [FILE] prefix.")
    public String listFiles(
            @ToolParam(name = "dir", description = "Directory path relative to repo/, e.g. 'nacos/naming/src/main/java/'") String dir) {

        Path resolved = resolveSafePath(dir);
        if (resolved == null) {
            return "Error: path traversal denied. Only directories under repo/ are accessible.";
        }
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return "Error: not a directory: " + dir;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Directory: ").append(dir).append("\n\n");

        try (Stream<Path> stream = Files.list(resolved)) {
            List<Path> entries = new ArrayList<>();
            stream.forEach(entries::add);
            entries.sort((a, b) -> {
                boolean aDir = Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS);
                boolean bDir = Files.isDirectory(b, LinkOption.NOFOLLOW_LINKS);
                if (aDir != bDir) return aDir ? -1 : 1;
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            });

            int count = 0;
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (SKIP_DIRS.stream().anyMatch(name::equals)) continue;
                boolean isDir = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                sb.append(isDir ? "[DIR]  " : "[FILE] ").append(name).append("\n");
                count++;
                if (count >= MAX_LIST_ITEMS) {
                    sb.append("\n[Truncated: more than ").append(MAX_LIST_ITEMS).append(" entries, use grepCode to search specific files.]");
                    break;
                }
            }
            if (count == 0) {
                sb.append("(empty directory or all entries skipped)\n");
            }
        } catch (IOException e) {
            log.warn("listFiles error for {}: {}", dir, e.getMessage());
            return "Error listing directory: " + e.getMessage();
        }

        return sb.toString();
    }

    @Tool(description = "Search code by regex pattern in files under a directory within repo/. Returns matching file paths, line numbers, and line content. Searches .java/.jsx/.js/.ts/.tsx/.xml/.properties/.md/.yml/.yaml/.json files.")
    public String grepCode(
            @ToolParam(name = "pattern", description = "Java regex pattern to search, e.g. 'class DistroProtocol' or 'registerInstance'") String pattern,
            @ToolParam(name = "dir", description = "Directory to search in, relative to repo/ (e.g. 'nacos/core/' or 'nacos/')") String dir) {

        Path resolved = resolveSafePath(dir);
        if (resolved == null) {
            return "Error: path traversal denied. Only directories under repo/ are accessible.";
        }
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return "Error: not a directory: " + dir;
        }

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (Exception e) {
            return "Error: invalid regex pattern: " + e.getMessage();
        }

        List<String> results = new ArrayList<>();
        int[] totalHits = {0};

        try (Stream<Path> stream = Files.walk(resolved)) {
            stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !isSkippedPath(p))
                    .filter(p -> hasTextExtension(p))
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                            for (int i = 0; i < lines.size(); i++) {
                                Matcher m = regex.matcher(lines.get(i));
                                if (m.find()) {
                                    totalHits[0]++;
                                    if (results.size() < MAX_GREP_RESULTS) {
                                        String relPath = REPO_DIR.relativize(p).toString().replace('\\', '/');
                                        results.add(String.format("%s:%d: %s", relPath, i + 1, lines.get(i).strip()));
                                    }
                                }
                            }
                        } catch (IOException ignored) {
                            // binary or unreadable file, skip
                        }
                    });
        } catch (IOException e) {
            log.warn("grepCode error for {}: {}", dir, e.getMessage());
            return "Error searching: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No matches found for pattern '" + pattern + "' in " + dir;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Pattern: ").append(pattern).append(" | Dir: ").append(dir).append("\n\n");
        for (String r : results) {
            sb.append(r).append("\n");
        }
        if (totalHits[0] > MAX_GREP_RESULTS) {
            sb.append("\n[Truncated: ").append(totalHits[0]).append(" total hits, returned first ").append(MAX_GREP_RESULTS).append(". Refine your pattern or search in a narrower directory.]");
        }

        return sb.toString();
    }

    // ==================== Security ====================

    /**
     * Resolve a relative path against repo/ and verify it stays within the sandbox.
     * Returns null if the path escapes repo/ or is absolute.
     */
    private Path resolveSafePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        // Reject absolute paths
        if (relativePath.startsWith("/") || relativePath.matches("^[A-Za-z]:[/\\\\].*")) {
            return null;
        }
        try {
            Path resolved = Paths.get(REPO_DIR.toString(), relativePath).toAbsolutePath().normalize();
            if (!resolved.startsWith(REPO_DIR)) {
                return null;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSensitiveFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        for (String sensitive : SENSITIVE_FILES) {
            if (name.equals(sensitive) || name.endsWith(sensitive)) {
                return true;
            }
            // wildcard match for *.pem, *.key
            if (sensitive.startsWith(".") && name.endsWith(sensitive)) {
                return true;
            }
        }
        // Check for .pem, .key extensions
        return name.endsWith(".pem") || name.endsWith(".key");
    }

    private boolean isSkippedPath(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            if (SKIP_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTextExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

}
