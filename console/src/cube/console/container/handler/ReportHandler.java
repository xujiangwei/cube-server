/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.console.container.handler;

import cell.util.log.Logger;
import cube.console.Console;
import cube.report.*;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * 报告处理器。
 */
public class ReportHandler extends ContextHandler {

    private Console console;

    public ReportHandler(Console console) {
        super("/report");
        // 接口挂在上下文根路径上，允许直接访问 /report 。
        // 否则 Jetty 会 302 到 /report/ ，而上报方（SubmitThread 用的 Jetty HttpClient 默认不跟随重定向）
        // 会因状态码非 200 而判定上报失败。
        setAllowNullPathInfo(true);
        this.setHandler(new Handler());
        this.console = console;
    }

    protected class Handler extends AbstractHandler {

        public Handler() {
            super();
        }

        @Override
        public void handle(String target, Request request,
                           HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse)
                throws IOException, ServletException {
            request.setHandled(true);
            if (!"POST".equalsIgnoreCase(httpServletRequest.getMethod())) {
                httpServletResponse.setHeader("Allow", "POST");
                httpServletResponse.setStatus(HttpStatus.METHOD_NOT_ALLOWED_405);
                return;
            }
            if (httpServletRequest.getCharacterEncoding() == null) {
                httpServletRequest.setCharacterEncoding("UTF-8");
            }
            BufferedReader reader = httpServletRequest.getReader();

            StringBuilder buf = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                buf.append(line);
            }

            try {
                JSONObject reportJson = new JSONObject(buf.toString());

                // 获取报告名
                String name = Report.extractName(reportJson);
                if (reportJson.getString("reporter").trim().isEmpty()) {
                    throw new JSONException("Empty reporter");
                }
                reportJson.getLong("timestamp");
                if (LogReport.NAME.equals(name)) {
                    // 这些报告的构造器会吞掉 JSON 异常，需先校验再入库。
                    JSONArray lines = reportJson.getJSONArray("lines");
                    for (int i = 0; i < lines.length(); ++i) {
                        JSONObject entry = lines.getJSONObject(i);
                        entry.getInt("level");
                        entry.getString("tag");
                        entry.getString("text");
                        entry.getLong("time");
                    }
                    LogReport report = new LogReport(reportJson);
                    console.appendLogReport(report);
                } else if (JVMReport.NAME.equals(name)) {
                    for (String key : new String[]{"maxMemory", "totalMemory", "freeMemory",
                            "systemStartTime", "systemDuration"}) {
                        reportJson.getLong(key);
                    }
                    JVMReport report = new JVMReport(reportJson);
                    console.appendJVMReport(report);
                } else if (PerformanceReport.NAME.equals(name)) {
                    PerformanceReport report = new PerformanceReport(reportJson);
                    console.appendPerformanceReport(report);
                } else if (UnitReport.NAME.equals(name)) {
                    reportJson.getJSONArray("units");
                    UnitReport report = new UnitReport(reportJson);
                    console.appendUnitReport(report);
                } else {
                    throw new JSONException("Unsupported report name: " + name);
                }

                httpServletResponse.setStatus(HttpStatus.OK_200);
            } catch (JSONException e) {
                Logger.w(this.getClass(), "Reject invalid report: " + e.getMessage());
                httpServletResponse.setStatus(HttpStatus.BAD_REQUEST_400);
            } catch (Exception e) {
                Logger.e(this.getClass(), "Failed to store report", e);
                httpServletResponse.setStatus(HttpStatus.INTERNAL_SERVER_ERROR_500);
            }
        }
    }
}
