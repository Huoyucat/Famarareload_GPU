package com.wenkrang.famara.render.output;

import com.google.gson.Gson;
import com.wenkrang.famara.render.asset.*;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * 后台存储端口。先写pending，再独占创建PNG，最后发布存储收据；不覆盖历史照片。
 */
public final class DiskPhotoStore {
    public enum Checkpoint {AFTER_ENCODE, AFTER_PNG, BEFORE_RECEIPT}

    @FunctionalInterface
    public interface FaultInjector {
        void at(Checkpoint checkpoint) throws IOException;
    }

    public record Stored(int mapId, UUID transaction, Path png, Path receipt) {
    }

    private record Receipt(int mapId, UUID transaction, String pngHash, String mapHash, String mapFile,
                           Map<String, String> metadata) {
    }

    private final Path root, metadata, transactions;
    private final FaultInjector faults;
    private final Gson gson = new Gson();
    private static final AssetLimits RECEIPT_LIMITS = new AssetLimits(1, 1024 * 1024, 2 * 1024 * 1024, 1, 1, 1, 1, 32, 10000, 1);

    public DiskPhotoStore(Path root) throws IOException {
        this(root, point -> {
        });
    }

    public DiskPhotoStore(Path root, FaultInjector faults) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.faults = Objects.requireNonNull(faults);
        metadata = this.root.resolve("metadata-v2");
        transactions = this.root.resolve("transactions-v2");
        Files.createDirectories(this.root);
        Files.createDirectories(metadata);
        Files.createDirectories(transactions);
    }

    public Stored commit(int mapId, ImmutablePhoto photo) throws IOException {
        validateId(mapId);
        Objects.requireNonNull(photo);
        Path target = root.resolve(mapId + ".png"), receipt = metadata.resolve(mapId + ".json"), pending = transactions.resolve(mapId + ".pending");
        UUID transaction = UUID.randomUUID();
        Files.writeString(pending, transaction.toString(), StandardOpenOption.CREATE_NEW);
        if (Files.exists(target) || Files.exists(receipt)) {
            Files.deleteIfExists(pending);
            throw new FileAlreadyExistsException(target.toString());
        }
        Path pngTemp = transactions.resolve(mapId + "-" + transaction + ".png.tmp");
        Path mapTemp = metadata.resolve(mapId + "-" + transaction + ".map");
        Path receiptTemp = transactions.resolve(mapId + "-" + transaction + ".json.tmp");
        boolean committed = false;
        try {
            BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 128, 128, photo.copyRgb(), 0, 128);
            try (OutputStream out = Files.newOutputStream(pngTemp, StandardOpenOption.CREATE_NEW)) {
                if (!ImageIO.write(image, "png", out)) throw new IOException("没有可用PNG编码器");
            }
            faults.at(Checkpoint.AFTER_ENCODE);
            byte[] map = photo.copyMap();
            Files.write(mapTemp, map, StandardOpenOption.CREATE_NEW);
            Receipt record = new Receipt(mapId, transaction, hash(Files.readAllBytes(pngTemp)), hash(map), mapTemp.getFileName().toString(), photo.metadata());
            // CREATE_NEW复制不覆盖历史文件。pending/收据约定使不支持原子建链的平台也能识别未完成结果。
            Files.copy(pngTemp, target);
            faults.at(Checkpoint.AFTER_PNG);
            Files.writeString(receiptTemp, gson.toJson(record), StandardOpenOption.CREATE_NEW);
            faults.at(Checkpoint.BEFORE_RECEIPT);
            try {
                Files.move(receiptTemp, receipt, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(receiptTemp, receipt);
            }
            committed = true;
            Files.deleteIfExists(pending);
            return new Stored(mapId, transaction, target, receipt);
        } finally {
            Files.deleteIfExists(pngTemp);
            Files.deleteIfExists(receiptTemp);
            if (!committed && !Files.exists(target)) {
                Files.deleteIfExists(mapTemp);
                Files.deleteIfExists(pending);
            }
            // 已创建PNG但收据未发布时，保留pending用于恢复/核对，绝不误删可能存在的旧照片。
        }
    }

    public Optional<ImmutablePhoto> loadCommitted(int mapId) throws IOException {
        validateId(mapId);
        Path path = metadata.resolve(mapId + ".json");
        if (isPending(mapId) || !Files.isRegularFile(path)) return Optional.empty();
        if (Files.size(path) > 1024 * 1024) throw new IOException("照片收据过大");
        Receipt record;
        try {
            ResourcePack one = ResourcePack.memory("receipt", Map.of("receipt.json", Files.readAllBytes(path)), RECEIPT_LIMITS);
            record = gson.fromJson(BoundedJson.read(one.require("receipt.json"), "receipt.json", RECEIPT_LIMITS), Receipt.class);
        } catch (RuntimeException e) {
            throw new IOException("照片收据不合法", e);
        }
        if (record == null || record.mapId != mapId || record.transaction == null || record.metadata == null)
            throw new IOException("照片收据身份不匹配");
        String expected = mapId + "-" + record.transaction + ".map";
        if (!expected.equals(record.mapFile)) throw new IOException("照片收据路径不合法");
        Path png = root.resolve(mapId + ".png"), map = metadata.resolve(expected);
        if (Files.size(png) > 2 * 1024 * 1024 || Files.size(map) != 16384) throw new IOException("照片文件大小不合法");
        byte[] pngBytes = Files.readAllBytes(png), mapBytes = Files.readAllBytes(map);
        if (!hash(pngBytes).equals(record.pngHash) || !hash(mapBytes).equals(record.mapHash))
            throw new IOException("照片文件校验失败");
        BufferedImage image;
        try (var stream = new ByteArrayInputStream(pngBytes); ImageInputStream input = ImageIO.createImageInputStream(stream)) {
            if (input == null) throw new IOException("照片PNG不合法");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("照片PNG无法识别");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase("png") || reader.getWidth(0) != 128 || reader.getHeight(0) != 128)
                    throw new IOException("照片像素不合法");
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        }
        if (image == null || image.getWidth() != 128 || image.getHeight() != 128)
            throw new IOException("照片像素不合法");
        int[] rgb = image.getRGB(0, 0, 128, 128, null, 0, 128);
        for (int i = 0; i < rgb.length; i++) rgb[i] &= 0xffffff;
        return Optional.of(new ImmutablePhoto(rgb, mapBytes, record.metadata));
    }

    public boolean isPending(int mapId) {
        validateId(mapId);
        return Files.exists(transactions.resolve(mapId + ".pending"));
    }

    public List<Integer> committedIds() throws IOException {
        try (var paths = Files.list(metadata)) {
            return paths.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(s -> s.matches("[0-9]+[.]json"))
                    .map(s -> Integer.parseInt(s.substring(0, s.length() - 5))).sorted().toList();
        }
    }

    private static void validateId(int id) {
        if (id < 0) throw new IllegalArgumentException("地图ID不能为负");
    }

    private static String hash(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
