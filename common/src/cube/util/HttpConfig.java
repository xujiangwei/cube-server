/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.util;

/**
 * HTTP 配置信息。
 */
public class HttpConfig {

    public int httpPort = 0;
    public int httpsPort = 0;
    public String keystore = null;
    /** 存储密码。 */
    public String storePassword = null;
    /** 管理器密码。 */
    public String managerPassword = null;

    /** 最大线程。 */
    public int maxThreads = 32;
    /** 最小线程。 */
    public int minThreads = 8;

}
