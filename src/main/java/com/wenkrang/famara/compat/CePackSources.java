package com.wenkrang.famara.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 按 CE 实际来源分层读取资源；只在内存读取，不解包、不修改 CE 文件。 */
final class CePackSources implements AutoCloseable {
    private final List<PackColorIndex.PackSource> layers = new ArrayList<>();
    private final List<ZipFile> archives = new ArrayList<>();
    private final List<Path> opened = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();
    private final Set<PackColorIndex.PackSource> unavailable = new HashSet<>();

    static CePackSources open(Collection<Path> paths) {
        CePackSources result = new CePackSources();
        Set<Path> seen = new HashSet<>();
        for (Path path : paths) {
            if (path == null) continue;
            try {
                Path normalized = path.toAbsolutePath().normalize();
                if (!seen.add(normalized)) continue;
                if (!Files.exists(normalized)) {
                    result.issues.add("资源来源不存在：" + normalized);
                    continue;
                }
                if (Files.isDirectory(normalized)) result.addFolder(normalized);
                else result.addZip(normalized);
                result.opened.add(normalized);
            } catch (IOException | RuntimeException e) {
                // CE 可能提供随后关闭的内存生成目录；不能因此放弃已经存在的完整 ZIP。
                result.issues.add("无法读取资源来源 " + path + "：" + e);
            }
        }
        return result;
    }

    private void addFolder(Path folder) throws IOException {
        Path root = folder.toRealPath();
        List<String> names;
        try (Stream<Path> files = Files.walk(root)) {
            names = files.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        layers.add(new PackColorIndex.PackSource() {
            public Collection<String> list() { return names; }
            public byte[] read(String path) throws IOException {
                Path target = root.resolve(path).normalize();
                if (!target.startsWith(root)) throw new IOException("资源路径超出来源目录：" + path);
                if (!Files.isRegularFile(target)) return null;
                if (!target.toRealPath().startsWith(root)) throw new IOException("资源链接超出来源目录：" + path);
                return Files.readAllBytes(target);
            }
        });
    }

    private void addZip(Path path) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        archives.add(zip);
        List<String> names = zip.stream().filter(entry -> !entry.isDirectory()).map(ZipEntry::getName).sorted().toList();
        layers.add(new PackColorIndex.PackSource() {
            public Collection<String> list() { return names; }
            public byte[] read(String name) throws IOException {
                ZipEntry entry = zip.getEntry(name);
                if (entry == null || entry.isDirectory()) return null;
                try (var input = zip.getInputStream(entry)) { return input.readAllBytes(); }
            }
        });
    }

    PackColorIndex.PackSource source() {
        return new PackColorIndex.PackSource() {
            public Collection<String> list() throws IOException {
                Set<String> names = new TreeSet<>();
                for (var layer : layers) names.addAll(layer.list());
                return names;
            }
            public byte[] read(String path) throws IOException {
                for (var layer : layers) {
                    if (unavailable.contains(layer)) continue;
                    try {
                        byte[] bytes = layer.read(path);
                        if (bytes != null) return bytes;
                    } catch (IOException | RuntimeException e) {
                        unavailable.add(layer);
                        issues.add("读取期间来源失效，继续其他来源：" + path + "：" + e);
                    }
                }
                return null;
            }
        };
    }

    List<Path> opened() { return List.copyOf(opened); }
    List<String> issues() { return List.copyOf(issues); }

    @Override public void close() {
        for (ZipFile archive : archives) {
            try { archive.close(); }
            catch (IOException ignored) { }
        }
    }
}
