/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2026 Ambrose Xu.
 */

package cube.util;

import cell.util.Utils;
import cell.util.log.Logger;

import javax.net.ssl.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件下载器。
 *
 * <p><b>证书策略（刻意为之，勿"修复"）</b>：外部站点大量存在自签证书、证书链不完整、
 * 域名与证书不匹配的情况，本类的目标是尽可能把内容下载下来，因此默认信任任意证书与主机名。
 * 约束：</p>
 * <ol>
 *     <li>只在本连接上 {@code setSSLSocketFactory} / {@code setHostnameVerifier}，
 *     <b>禁止</b>修改 {@code HttpsURLConnection} 的全局默认值；</li>
 *     <li>仅用于外部内容抓取，不得用于调用内部服务或业务 API；</li>
 *     <li>首次使用 https 时打一条 WARN，便于审计定位。</li>
 * </ol>
 *
 * <p><b>失败处理</b>：下载先写 {@code xxx.part}，成功后再改名；任何失败（超限、IO 错误、
 * 监听器异常）都会删除临时文件，避免 {@code storage/downloads/} 里堆积半截文件。</p>
 */
public class FileDownloader {

    /**
     * 建立连接超时（毫秒）。
     */
    private final static int CONNECT_TIMEOUT = 5000;

    /**
     * 读数据超时（毫秒）。没有它，对端接受连接后不吐数据会让调用线程永久阻塞。
     */
    private final static int READ_TIMEOUT = 30 * 1000;

    /**
     * 最大重定向跳数。
     */
    private final static int MAX_REDIRECTS = 3;

    private final static AtomicBoolean TRUST_ALL_WARNED = new AtomicBoolean(false);

    private String workPath = "storage/downloads/";

    private int sizeLimit = 10 * 1024 * 1024;

    /**
     * 信任任意证书的 SSL 上下文工厂，构造失败时为 {@code null}（退化为系统默认校验）。
     */
    private SSLSocketFactory trustAllSocketFactory;

    public FileDownloader() {
        File path = new File(this.workPath);
        if (!path.exists() && !path.mkdirs()) {
            Logger.w(FileDownloader.class, "#FileDownloader - Failed to create work path: "
                    + path.getAbsolutePath());
        }

        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] tm = new TrustManager[]{ new DefaultX509TrustManager() };
            sslContext.init(null, tm, null);
            this.trustAllSocketFactory = new TLS12SSLSocketFactory(sslContext.getSocketFactory());
        } catch (Exception e) {
            Logger.e(FileDownloader.class, "#FileDownloader - Failed to initialize trust-all SSL context, "
                    + "fallback to system default", e);
        }
    }

    public FileDownloader(int sizeLimit) {
        this();
        if (sizeLimit > 0) {
            this.sizeLimit = sizeLimit;
        }
    }

    /**
     * 下载文件。
     *
     * @param fileUrl 文件地址，支持 http 与 https。
     * @param listener 进度监听器，允许为 {@code null} 。
     * @return 下载完成的文件；失败返回 {@code null} 。
     */
    public File downloadFile(String fileUrl, FileDownloaderListener listener) {
        final FileDownloaderListener target = (null == listener) ? new SilentListener() : listener;

        int contentLength = 0;
        String contentType = null;

        InputStream is = null;
        FileOutputStream fos = null;
        HttpURLConnection conn = null;
        File partFile = null;
        File file = null;

        // 本地文件名
        String filename = System.currentTimeMillis() + "_" + Utils.randomString(8);

        try {
            conn = this.openConnection(fileUrl);

            int responseCode = conn.getResponseCode();
            if (HttpURLConnection.HTTP_OK != responseCode) {
                Logger.e(this.getClass(), "Can NOT connect: " + fileUrl + " - HTTP " + responseCode);
                this.notifyError(target, "Unable to connect to the server: HTTP " + responseCode);
                return null;
            }

            contentLength = conn.getContentLength();
            if (contentLength > this.sizeLimit) {
                Logger.e(this.getClass(), "The content length exceeds the limit: " + fileUrl +
                        " - " + contentLength + "/" + this.sizeLimit);
                this.notifyError(target, "The content length exceeds the limit: " + fileUrl +
                        " - " + contentLength + "/" + this.sizeLimit);
                return null;
            }

            contentType = conn.getContentType();

            // 解析文件类型
            FileType fileType = FileType.matchMimeType(contentType);
            filename = filename + "." + fileType.getPreferredExtension();

            file = new File(this.workPath, filename);
            partFile = new File(this.workPath, filename + ".part");

            is = conn.getInputStream();
            fos = new FileOutputStream(partFile);

            byte[] bytes = new byte[10240];
            int length;
            int downloadLength = 0;

            while ((length = is.read(bytes)) > 0) {
                downloadLength += length;

                // 先判上限再落盘：避免把超限数据写进文件后才发现
                if (downloadLength > this.sizeLimit) {
                    Logger.e(this.getClass(), "The download length exceeds the limit: " + fileUrl +
                            " - " + downloadLength + "/" + this.sizeLimit);
                    this.notifyError(target, "The download length exceeds the limit: " + fileUrl +
                            " - " + downloadLength + "/" + this.sizeLimit);
                    return null;
                }

                fos.write(bytes, 0, length);
                this.notifyProgress(target, filename, downloadLength, contentLength);
            }

            fos.flush();
            fos.close();
            fos = null;

            // 原子改名：下载完成之前，目标文件不存在
            Files.move(partFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            partFile = null;

            this.notifyCompleted(target, file);

            return file;
        } catch (Exception e) {
            Logger.e(this.getClass(), "#downloadFile", e);
            this.notifyError(target, e.getMessage());
            return null;
        } finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(FileDownloader.class, "#downloadFile - Failed to close 'is': " + e.getMessage());
                }
            }

            if (null != fos) {
                try {
                    fos.close();
                } catch (IOException e) {
                    // 释放失败不影响主流程，仅记录
                    Logger.d(FileDownloader.class, "#downloadFile - Failed to close 'fos': " + e.getMessage());
                }
            }

            if (null != conn) {
                conn.disconnect();
            }

            // 失败路径：删除半截文件
            if (null != partFile && partFile.exists() && !partFile.delete()) {
                Logger.w(FileDownloader.class, "#downloadFile - Failed to delete partial file: "
                        + partFile.getAbsolutePath());
            }
        }
    }

    /**
     * 建立连接，并跟随最多 {@link #MAX_REDIRECTS} 跳重定向。
     *
     * <p>{@link HttpURLConnection} 默认不跟随 https → http 的跨协议重定向，这里显式处理。</p>
     *
     * @param fileUrl 原始地址。
     * @return 已就绪的连接。
     * @throws IOException 建立失败或重定向过多时抛出。
     */
    private HttpURLConnection openConnection(String fileUrl) throws IOException {
        String current = fileUrl;

        for (int hop = 0; hop <= MAX_REDIRECTS; ++hop) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            this.configure(conn, url);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();

                if (null == location || location.isEmpty()) {
                    throw new IOException("Redirect without Location: " + current);
                }

                current = new URL(url, location).toString();
                Logger.d(this.getClass(), "#openConnection - Redirect to: " + current);
                continue;
            }

            return conn;
        }

        throw new IOException("Too many redirects: " + fileUrl);
    }

    private void configure(HttpURLConnection conn, URL url) {
        conn.setDoOutput(false);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(false);

        try {
            conn.setRequestMethod("GET");
        } catch (Exception e) {
            // 某些协议实现不支持设置方法，保留默认值
            Logger.d(this.getClass(), "#configure - " + e.getMessage());
        }

        conn.setRequestProperty("User-Agent", "Baize/3.0");

        if ("https".equalsIgnoreCase(url.getProtocol())) {
            if (null != this.trustAllSocketFactory) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(this.trustAllSocketFactory);
                ((HttpsURLConnection) conn).setHostnameVerifier(new TrustAnyHostnameVerifier());

                if (TRUST_ALL_WARNED.compareAndSet(false, true)) {
                    Logger.w(this.getClass(), "Certificate verification is DISABLED for external downloads");
                }
            }
        }
    }

    private void notifyProgress(FileDownloaderListener listener, String filename, int progress, int total) {
        try {
            listener.onProgress(filename, progress, total);
        } catch (Exception e) {
            Logger.w(this.getClass(), "#notifyProgress - Listener failed: " + e.getMessage());
        }
    }

    private void notifyCompleted(FileDownloaderListener listener, File file) {
        try {
            listener.onCompleted(file);
        } catch (Exception e) {
            Logger.w(this.getClass(), "#notifyCompleted - Listener failed: " + e.getMessage());
        }
    }

    private void notifyError(FileDownloaderListener listener, String error) {
        try {
            listener.onError(error);
        } catch (Exception e) {
            Logger.w(this.getClass(), "#notifyError - Listener failed: " + e.getMessage());
        }
    }

    /**
     * 空监听器：调用方不关心进度时使用，避免到处判空。
     */
    private static final class SilentListener implements FileDownloaderListener {

        @Override
        public void onProgress(String fileName, int progressLength, int totalLength) {
            // Nothing
        }

        @Override
        public void onCompleted(File file) {
            // Nothing
        }

        @Override
        public void onError(String error) {
            // Nothing
        }
    }

    protected class TrustAnyHostnameVerifier implements HostnameVerifier {
        @Override
        public boolean verify(String hostname, SSLSession sslSession) {
            // 信任所有主机
            return true;
        }
    }

    protected class DefaultX509TrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    protected class TLS12SSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        public TLS12SSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;}

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            SSLSocket socket = (SSLSocket) delegate.createSocket(s, host, port, autoClose);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"}); // 强制使用TLSv1.2
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            SSLSocket socket = (SSLSocket) delegate.createSocket(host, port);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            SSLSocket socket = (SSLSocket) delegate.createSocket(host, port, localHost, localPort);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            SSLSocket socket = (SSLSocket) delegate.createSocket(host, port);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            SSLSocket socket = (SSLSocket) delegate.createSocket(address, port, localAddress, localPort);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            return socket;
        }
    }

    public static void main(String[] args) {
        String fileUrl = "https://cdn.mos.cms.futurecdn.net/vChK6pTy3vN3KbYZ7UU7k3-320-80.jpg";
        FileDownloader downloader = new FileDownloader();
        File file = downloader.downloadFile(fileUrl, new FileDownloaderListener() {
            @Override
            public void onProgress(String fileName, int progressLength, int totalLength) {
                System.out.println("progress (" + fileName + ") - " + progressLength + " / " + totalLength);
            }

            @Override
            public void onCompleted(File file) {
                System.out.println("onCompleted: " + file.getAbsolutePath());
            }

            @Override
            public void onError(String error) {
                System.out.println("Error: " + error);
            }
        });
        if (null != file) {
            System.out.println("Download: " + file.getName() + " - " + file.length());
        }
        else {
            System.out.println("Download failed");
        }
    }
}
