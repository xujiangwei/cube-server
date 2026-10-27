/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.app;

import cube.auth.AuthToken;
import cube.common.entity.Point;

public class PointTable {

    /** 评估。 */
    public final static String Evaluation = "Evaluation";
    /** 评估点列表。 */
    public final static int EvaluationPoints = 100;

    public PointTable() {
    }

    /**
     * 创建评估点。
     *
     * @param authToken 评估点。
     * @return 评估点。
     */
    public static Point createEvaluationPoint(AuthToken authToken) {
        return new Point(authToken.getDomain(), System.currentTimeMillis(),
                authToken.getContactId(), EvaluationPoints, Evaluation, "Psychology");
    }
}
