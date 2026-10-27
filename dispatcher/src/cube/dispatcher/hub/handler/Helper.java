/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.dispatcher.hub.handler;

import cell.core.talk.dialect.ActionDialect;
import cell.util.log.Logger;
import cube.dispatcher.Performer;
import cube.dispatcher.hub.HubCellet;
import cube.hub.HubAction;
import cube.hub.HubStateCode;
import cube.hub.SignalBuilder;
import cube.hub.data.ChannelCode;
import cube.hub.signal.ChannelCodeSignal;
import cube.hub.signal.Signal;
import org.eclipse.jetty.http.HttpStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 辅助函数。
 */
public class Helper {

    private Helper() {
    }

    /**
     * 校验通道码。
     *
     * <p>码为空或通道不存在时直接写出错误响应并返回 {@code null} ，调用方据此提前返回即可。</p>
     *
     * @param code     通道码。
     * @param response 响应对象，校验失败时用于输出错误。
     * @param performer 执行机。
     * @return 通道码对象；校验失败返回 {@code null} 。
     */
    public static ChannelCode checkChannelCode(String code, HttpServletResponse response,
                                               Performer performer) {
        if (null == code || code.length() == 0) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            return null;
        }

        ChannelCodeSignal channelCodeSignal = new ChannelCodeSignal(code);
        ActionDialect requestAction = new ActionDialect(HubAction.Channel.name);
        requestAction.addParam("signal", channelCodeSignal.toJSON());

        ActionDialect responseAction = performer.syncTransmit(HubCellet.NAME, requestAction);
        if (null == responseAction) {
            response.setStatus(HttpStatus.FORBIDDEN_403);
            return null;
        }

        int stateCode = responseAction.getParamAsInt("code");
        if (HubStateCode.Ok.code != stateCode) {
            response.setStatus(HttpStatus.UNAUTHORIZED_401);
            return null;
        }

        Signal resultSignal = SignalBuilder.build(responseAction.getParamAsJson("signal"));
        if (resultSignal instanceof ChannelCodeSignal) {
            ChannelCode channelCode = ((ChannelCodeSignal)resultSignal).getChannelCode();
            if (null != channelCode) {
                // 校验有效期
                if (System.currentTimeMillis() >= channelCode.expiration) {
                    // 过期
                    Logger.w(Helper.class, "#checkChannelCode - channel code expired : " + channelCode.code);
                    return null;
                }
                else {
                    return channelCode;
                }
            }
            else {
                response.setStatus(HttpStatus.NOT_FOUND_404);
                return null;
            }
        }
        else {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            return null;
        }
    }
}
