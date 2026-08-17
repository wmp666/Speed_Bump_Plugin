package com.wmp.processing.imageFormat;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ImageConverter {

    /**
     * 图片格式转换通用方法（支持 JPEG, PNG, GIF, BMP, WebP, ICO）
     *
     * @param sourcePath 源文件路径
     * @param targetPath 目标文件路径（建议后缀与格式一致）
     * @param targetFormat 目标格式字符串，如 "jpg", "png", "webp", "ico"
     * @param icoSizes ICO专属：需要的尺寸列表（如 [16, 32, 48]），其他格式传 null 即可
     * @throws IOException 读写异常
     */
    public static void convertImage(String sourcePath, String targetPath,
                                    String targetFormat, List<Integer> icoSizes) throws Exception {

        // 1. 读取源文件
        BufferedImage source = ImageIO.read(new File(sourcePath));
        if (source == null) {
            throw new IllegalArgumentException("无法识别的图片格式或文件损坏: " + sourcePath);
        }

        String format = targetFormat.toLowerCase().trim();
        File output = new File(targetPath);

        // 2. 处理 ICO 格式（多尺寸）
        if ("ico".equals(format)) {
            if (icoSizes == null || icoSizes.isEmpty()) {
                icoSizes = List.of(16, 32, 48, 64, 128, 256);
            }
            List<BufferedImage> images = new ArrayList<>();
            for (int size : icoSizes) {
                images.add(resizeImage(source, size, size));
            }

            // 获取专门处理 ICO 格式的写入器
            ImageWriter writer = ImageIO.getImageWritersByFormatName("ico").next();
            if (writer == null) {
                throw new IOException("未找到 ICO 格式的 ImageWriter，请检查是否引入了 imageio-ico 依赖");
            }

            try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(ios);
                // 开始写入序列（ICO 格式要求多张图片按顺序写入）
                writer.prepareWriteSequence(null);
                for (BufferedImage img : images) {
                    // 将每张图片封装为 IIOImage 并写入序列
                    writer.writeToSequence(new IIOImage(img, null, null), null);
                }
                // 结束序列
                writer.endWriteSequence();
            } finally {
                // 释放资源
                writer.dispose();
            }
            return; // 处理完毕，直接返回
        }

        // 3. 处理 JPG/JPEG（需特殊处理透明背景，否则透明部分会变黑）
        if ("jpg".equals(format) || "jpeg".equals(format)) {
            BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE); // 用白色填充背景
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
            g.dispose();
            ImageIO.write(rgb, "jpeg", output);
            return;
        }

        // 4. 检查不支持的现代格式（AVIF / HEIC）
        if ("avif".equals(format) || "heic".equals(format)) {
            throw new UnsupportedOperationException(
                    "AVIF 和 HEIC 格式在开源 Java 中无法直接处理。\n" +
                            "建议方案：安装 ImageMagick，调用系统命令。例如：\n" +
                            "magick convert " + sourcePath + " " + targetPath
            );
        }

        // 5. 其他格式 (PNG, GIF, BMP, WebP)
        // 注意：TwelveMonkeys 已注入 WebP 读写能力，直接用 ImageIO 即可
        ImageIO.write(source, format, output);
    }

    // 图片缩放工具方法（双三次插值，保证清晰度）
    private static BufferedImage resizeImage(BufferedImage src, int targetWidth, int targetHeight) {
        BufferedImage resized = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g2d.dispose();
        return resized;
    }
}
