/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.util;

import cell.util.collection.FlexibleByteBuffer;
import cell.util.log.Logger;
import cube.common.entity.FileLabel;
import cube.util.CrossDomainHandler;
import cube.util.FileType;
import cube.util.HttpClientFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpStatus;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.URLEncoder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 文件标签句柄。
 */
public class FileLabelHandler extends CrossDomainHandler {

    private int bufferSize = 20 * 1024 * 1024;

    public FileLabelHandler() {
        super();
    }

    /**
     * 获取转发缓冲区大小。
     *
     * @return 缓冲区字节数。
     */
    protected int getBufferSize() {
        return this.bufferSize;
    }

    /**
     * 阻塞式转发文件。
     *
     * <p>先把整个文件拉取到内存再一次性写出，实现简单但会占用与文件同等的内存；
     * 大文件应改用非阻塞方式。</p>
     *
     * @param request   HTTP 请求。
     * @param response  响应对象。
     * @param fileLabel 文件标签。
     * @param type      期望的响应类型。
     * @param file       本地文件，可能为 {@code null} 。
     * @throws IOException      读取或写出失败。
     * @throws ServletException 容器处理异常。
     */
    protected void processByBlocking(HttpServletRequest request, HttpServletResponse response,
                                   FileLabel fileLabel, FileType type, File file)
            throws IOException, ServletException {
        final Object mutex = new Object();

        final FlexibleByteBuffer buf = new FlexibleByteBuffer((int)fileLabel.getFileSize());

        HttpClient httpClient = HttpClientFactory.getInstance().borrowHttpClient();
        httpClient.newRequest(fileLabel.getDirectURL())
                .send(new BufferingResponseListener(this.bufferSize) {
                    @Override
                    public void onComplete(Result result) {
                        if (!result.isFailed()) {
                            byte[] responseContent = getContent();
                            buf.put(responseContent);
                        }

                        synchronized (mutex) {
                            mutex.notify();
                        }
                    }
                });

        synchronized (mutex) {
            try {
                mutex.wait(10 * 60 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        buf.flip();

        if (null != file) {
            // 写入文件
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                fos.write(buf.array(), 0, buf.limit());
            } catch (IOException e) {
                Logger.e(this.getClass(), "#processByBlocking", e);
            } finally {
                if (null != fos) {
                    try {
                        fos.close();
                    } catch (Exception e) {
                        // Nothing
                    }
                }
            }
        }

        // 填写头信息
        this.fillHeaders(response, fileLabel, buf.limit(), type);

        ServletOutputStream outputStream = response.getOutputStream();
        outputStream.write(buf.array(), 0, buf.limit());

        buf.clear();

        response.setStatus(HttpStatus.OK_200);

        HttpClientFactory.getInstance().returnHttpClient(httpClient);

        this.complete();
    }

    /**
     * 非阻塞式转发文件。
     *
     * <p>以流的方式边拉边写，内存占用与文件大小无关；
     * 连接与读取分别设 5 秒与 10 秒超时，避免慢节点长时间占用线程。</p>
     *
     * @param request   HTTP 请求。
     * @param response  响应对象。
     * @param fileLabel 文件标签。
     * @param type      期望的响应类型。
     * @param file       本地文件，可能为 {@code null} 。
     * @throws IOException      读取或写出失败。
     * @throws ServletException 容器处理异常。
     */
    protected void processByNonBlocking(HttpServletRequest request, HttpServletResponse response,
                                      FileLabel fileLabel, FileType type, File file)
            throws IOException, ServletException {
        InputStreamResponseListener listener = new InputStreamResponseListener();

        HttpClient httpClient = HttpClientFactory.getInstance().borrowHttpClient();
        httpClient.newRequest(fileLabel.getDirectURL())
                .timeout(10, TimeUnit.SECONDS)
                .send(listener);

        Response clientResponse = null;
        try {
            clientResponse = listener.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        if (null != clientResponse && clientResponse.getStatus() == HttpStatus.OK_200) {
            InputStream content = listener.getInputStream();

            // 填充 Header
            fillHeaders(response, fileLabel, fileLabel.getFileSize(), type);

            // Async output
            AsyncContext async = request.startAsync();
            ServletOutputStream output = response.getOutputStream();
            StandardDataStream dataStream = new StandardDataStream(content, async, output, file);
            async.addListener(new AsyncListener() {
                @Override
                public void onStartAsync(AsyncEvent asyncEvent) throws IOException {
                    Logger.d(this.getClass(), "onStartAsync");
                }

                @Override
                public void onComplete(AsyncEvent asyncEvent) throws IOException {
                    Logger.d(this.getClass(), "onComplete");
                    HttpClientFactory.getInstance().returnHttpClient(httpClient);
                    complete();
                }

                @Override
                public void onTimeout(AsyncEvent asyncEvent) throws IOException {
                    Logger.d(this.getClass(), "onTimeout");
                    HttpClientFactory.getInstance().returnHttpClient(httpClient);
                    complete();
                }

                @Override
                public void onError(AsyncEvent asyncEvent) throws IOException {
                    Logger.d(this.getClass(), "onError");
                    HttpClientFactory.getInstance().returnHttpClient(httpClient);
                    complete();
                }
            });

            // 设置数据写入监听器
            output.setWriteListener(dataStream);

            response.setStatus(HttpStatus.OK_200);
        }
        else {
            this.respond(response, HttpStatus.BAD_REQUEST_400, fileLabel.toCompactJSON());
            HttpClientFactory.getInstance().returnHttpClient(httpClient);
        }
    }

    /**
     * 按文件类型填充响应头。
     *
     * <p>下载场景写入 {@code Content-Disposition} 并编码文件名，避免中文名乱码；
     * 预览场景只设置 {@code Content-Type} 。</p>
     *
     * @param response  响应对象。
     * @param fileLabel 文件标签。
     * @param length    内容长度，单位字节。
     * @param type      响应类型。
     */
    protected void fillHeaders(HttpServletResponse response, FileLabel fileLabel, long length, FileType type) {
        if (FileType.FILE == type) {
            try {
                StringBuilder buf = new StringBuilder("attachment;");
                buf.append("filename=").append(URLEncoder.encode(fileLabel.getFileName(), "UTF-8"));
                response.setHeader("Content-Disposition", buf.toString());
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            response.setContentType(type.getMimeType());
        }
        else if (FileType.UNKNOWN == type) {
            response.setContentType(fileLabel.getFileType().getMimeType());
        }
        else if (null != type) {
            response.setContentType(type.getMimeType());
        }
        else {
            response.setContentType(fileLabel.getFileType().getMimeType());
        }

        if (length > 0) {
            response.setContentLengthLong(length);
        }
    }

    /**
     * 标准数据流输出。
     */
    private final class StandardDataStream implements WriteListener {

        private final InputStream content;
        private final AsyncContext async;
        private final ServletOutputStream output;
        private FileOutputStream fileOutputStream;

        protected long contentLength = 0;

        private StandardDataStream(InputStream content, AsyncContext async,
                                   ServletOutputStream output, File file) {
            this.content = content;
            this.async = async;
            this.output = output;

            if (null != file) {
                try {
                    this.fileOutputStream = new FileOutputStream(file);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onWritePossible() throws IOException {
            byte[] buffer = new byte[4096];

            // 输出流是否就绪
            while (this.output.isReady()) {
                int len = this.content.read(buffer);
                if (len < 0) {
                    this.async.complete();

                    try {
                        this.content.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    if (null != this.fileOutputStream) {
                        try {
                            this.fileOutputStream.close();
                        } catch (IOException e) {
                            // Nothing
                        }
                    }

                    return;
                }

                // 将数据写入输出流
                this.output.write(buffer, 0, len);
                this.contentLength += len;

                if (null != this.fileOutputStream) {
                    try {
                        this.fileOutputStream.write(buffer, 0, len);
                    } catch (IOException e) {
                        Logger.w(this.getClass(), "#onWritePossible", e);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            Logger.w(this.getClass(), "Async Error", throwable);
            this.async.complete();

            try {
                this.content.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (null != this.fileOutputStream) {
                try {
                    this.fileOutputStream.close();
                } catch (IOException e) {
                    // Nothing
                }
            }
        }
    }
}
