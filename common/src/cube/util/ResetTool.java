/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

import java.io.File;

/**
 * 帮助开发人员进行数据重置的工具。
 */
public final class ResetTool {

    private ResetTool() {

    }

    /**
     * 重置联系人存储。
     *
     */
    public static void resetContactStorage() {
        String path = ResetTool.guessPath();
        File file = new File(path, "ContactService.db");
        if (file.exists()) {
            file.delete();
            System.out.println("Delete file \"" + file.getAbsolutePath() + "\"");
        }
        else {
            System.out.println("No file \"" + file.getAbsolutePath() + "\"");
        }
    }

    /**
     * 重置消息存储。
     *
     * <p>清空消息相关表并重建结构，仅用于本地调试，生产环境禁止调用。</p>
     */
    public static void resetMessagingStorage() {
        String path = ResetTool.guessPath();
        File file = new File(path, "MessagingService.db");
        if (file.exists()) {
            file.delete();
            System.out.println("Delete file \"" + file.getAbsolutePath() + "\"");
        }
        else {
            System.out.println("No file \"" + file.getAbsolutePath() + "\"");
        }
    }

    /**
     * 重置存储路径。
     *
     */
    public static void resetStoragePath() {
        ResetTool.resetContactStorage();
        ResetTool.resetMessagingStorage();
    }

    private static String guessPath() {
        String path = "storage/";
        File file = new File(path);
        if (file.isDirectory() && file.exists()) {
            return path;
        }

        path = "service/storage/";
        file = new File(path);
        if (file.isDirectory() && file.exists()) {
            return path;
        }

        return "";
    }

    /**
     * 本地自测入口。
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        ResetTool.resetStoragePath();
    }
}
