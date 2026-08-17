package com.wmp.processing.imageFormat.test;

import javax.imageio.ImageIO;
import java.util.*;

public class GetSupportFormat {
    static void main() {

        // 1. 获取所有读写后缀（数组）
        String[] readerSuffixes = ImageIO.getReaderFileSuffixes();
        String[] writerSuffixes = ImageIO.getWriterFileSuffixes();

        System.out.println("支持的读取后缀: " + Arrays.toString(readerSuffixes));
        System.out.println("支持的写入后缀: " + Arrays.toString(writerSuffixes));

        // 2. 转换为 Set 以便求交集
        Set<String> readerSet = new HashSet<>(Arrays.asList(readerSuffixes));
        Set<String> writerSet = new HashSet<>(Arrays.asList(writerSuffixes));

        // 3. 保留同时支持读写的后缀（交集）
        readerSet.retainAll(writerSet);

        // 4. 添加到目标列表
        List<String> fileSuffixes = new ArrayList<>(readerSet);

        // 5. 打印结果（通常已按字母序排列）
        System.out.println("同时支持读写的后缀: " + fileSuffixes);
    }
}
