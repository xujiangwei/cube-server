/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.module;

import cell.util.log.Logger;
import cube.aigc.Flowable;
import cube.aigc.Module;
import cube.auth.AuthToken;
import cube.common.entity.QuestionAnswer;
import cube.common.state.AIGCStateCode;
import cube.service.aigc.AIGCService;
import cube.aigc.SemanticSearchListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用模块管理器。
 *
 * <p>把用户提问匹配到内置的问答知识，匹配成功时返回可直接执行的处理流程。</p>
 */
public class AppManager implements Module {

    private AIGCService service;

    public AppManager(AIGCService service) {
        this.service = service;
    }

    /**
     * 获取模块名称。
     *
     * @return 固定返回 {@code AppManager} 。
     */
    @Override
    public String getName() {
        return "AppManager";
    }

    /**
     * 启动模块。
     *
     * <p>加载内置问答数据，重复调用幂等。</p>
     */
    @Override
    public void start() {

    }

    /** 停止模块并释放资源。 */
    @Override
    public void stop() {

    }

    /**
     * 为提问匹配处理流程。
     *
     * <p>命中内置问答时返回对应流程，未命中返回 {@code null} ，由上层继续尝试其他模块。</p>
     *
     * @param token 访问令牌。
     * @param query 用户提问。
     * @return 可执行的流程；未命中返回 {@code null} 。
     */
    public Flowable match(AuthToken token, String query) {
        final List<QuestionAnswer> questionAnswerList = new ArrayList<>();
        boolean success = this.service.semanticSearch(query, new SemanticSearchListener() {
            @Override
            public void onCompleted(String query, List<QuestionAnswer> questionAnswers) {
                questionAnswerList.addAll(questionAnswers);
                synchronized (questionAnswerList) {
                    questionAnswerList.notify();
                }
            }

            @Override
            public void onFailed(String query, AIGCStateCode stateCode) {
                synchronized (questionAnswerList) {
                    questionAnswerList.notify();
                }
            }
        });

        if (!success) {
            Logger.w(this.getClass(), "#match - search flow task failed");
            return null;
        }

        synchronized (questionAnswerList) {
            try {
                questionAnswerList.wait(60 * 1000);
            } catch (InterruptedException e) {
                Logger.w(this.getClass(), "#match - search flow task failed", e);
            }
        }

        for (QuestionAnswer qa : questionAnswerList) {
            if (qa.getScore() < 0.85) {
                continue;
            }

//            if (qa.getAnswers().get(0).equalsIgnoreCase(QueryUser.TASK_NAME)) {
//                return new QueryUser(this.service, token, query);
//            }
        }

        return null;
    }
}
