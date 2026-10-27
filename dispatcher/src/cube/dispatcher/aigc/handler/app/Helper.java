/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.aigc.handler.app;

import cell.util.Utils;
import cube.dispatcher.aigc.handler.AIGCHandler;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 应用端点辅助工具。
 *
 * <p>集中处理应用类端点重复出现的三件事：从请求中提取令牌、按统一外壳封装响应、
 * 把长文本切成可分片下发的短句。</p>
 */
public final class Helper {

    /**
     * 中文字符和中文符号。
     */
    private final static Pattern sChinesePattern = Pattern.compile("[\\u4E00-\\u9FA5|\\\\！|\\\\，|\\\\。|\\\\（|\\\\）|\\\\《|\\\\》|\\\\“|\\\\”|\\\\？|\\\\：|\\\\；|\\\\【|\\\\】]");

    private Helper() {
    }

    /**
     * 从请求中提取令牌。
     *
     * <p>先读 {@code Authorization} 头（支持 {@code Bearer} 前缀），取不到时回退到查询参数。</p>
     *
     * @param request HTTP 请求。
     * @return 令牌；不存在时返回 {@code null} 。
     */
    public static String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (null == authorization) {
            return null;
        }

        return authorization.replace("Baize ", "").trim();
    }

    /**
     * 输出成功响应。
     *
     * <p>统一外壳为 {@code {code, data}} ，并负责结束本次请求。</p>
     *
     * @param handler  发起响应的处理器，用于回收并发计数。
     * @param response 响应对象。
     * @param data     业务数据。
     */
    public static void respondOk(AIGCHandler handler, HttpServletResponse response, JSONObject data) {
        JSONObject payload = new JSONObject();
        payload.put("status", "Success");
        payload.put("message", "");
        payload.put("data", data);
        handler.respondOk(response, payload);
        handler.complete();
    }

    /**
     * 输出失败响应。
     *
     * <p>HTTP 状态码与业务码保持一致，便于客户端统一判断。</p>
     *
     * @param handler  发起响应的处理器。
     * @param response 响应对象。
     * @param status   失败状态码。
     */
    public static void respondFailure(AIGCHandler handler, HttpServletResponse response, int status) {
        JSONObject payload = new JSONObject();
        payload.put("status", "Fail");
        payload.put("message", "");
        handler.respond(response, status);
        handler.complete();
    }

    /**
     * 把长文本切分为短句列表。
     *
     * <p>按标点与长度切分，用于模拟流式下发：切片过长会让首字延迟变明显，
     * 过短则分片数量过多，因此需要按标点优先、长度兜底。</p>
     *
     * @param content 原始文本。
     * @return 切片列表；入参为空时返回空列表。
     */
    public static List<String> splitContent(String content) {
        // 先拆为字符数组
        List<String> list = new LinkedList<>();
        if (content.length() <= 5) {
            list.add(content);
            return list;
        }

        char[] buf = content.toCharArray();
        for (int i = 0; i < buf.length; ++i) {
            String s = String.valueOf(buf[i]);
            Matcher m = sChinesePattern.matcher(s);
            int len = Utils.randomInt(5, 30);
            if (m.matches()) {
                // 中文字符
                StringBuilder word = new StringBuilder(s);
                while (++i < buf.length) {
                    s = String.valueOf(buf[i]);
                    m = sChinesePattern.matcher(s);
                    if (m.matches()) {
                        word.append(s);
                        if (word.length() >= len) {
                            break;
                        }
                    }
                    else {
                        // 非中文，结束
                        --i;
                        break;
                    }
                }
                list.add(word.toString());
            }
            else {
                StringBuilder word = new StringBuilder(s);
                while (++i < buf.length) {
                    s = String.valueOf(buf[i]);
                    m = sChinesePattern.matcher(s);
                    if (m.matches()) {
                        // 中文，结束
                        --i;
                        break;
                    }
                    else {
                        if (s.equals(" ") || s.equals(",") || s.equals(".") || s.equals(":") || s.equals("!") || s.equals("?")) {
                            // 符号，结束
                            word.append(s);
                            if (word.length() > len) {
                                break;
                            }
                        }
                        else {
                            word.append(s);
                        }
                    }
                }
                list.add(word.toString());
            }
        }

        return list;
    }


    /**
     * 本地自测入口。
     *
     * <p>用一段中英混排文本验证切分结果。</p>
     *
     * @param args 未使用。
     */
    public static void main(String[] args) {
        String content = "来自 App 测试：三国志刘备传 : AMny - 这是一串字符串，The sun is setting and the heartbroken are in the sky";
//        String content = "来自人工智能的测试：三国志刘备传 : AMny - 这是一串字符串";

        List<String> list = Helper.splitContent(content);
        for (String text : list) {
            System.out.println(text);
        }
    }
}
