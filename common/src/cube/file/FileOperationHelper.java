/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.file;

import cube.common.action.FileProcessorAction;
import cube.file.operation.*;
import org.json.JSONObject;

/**
 * 文件操作辅助类。
 */
public final class FileOperationHelper {

    private FileOperationHelper() {
    }

    /**
     * 按 JSON 构造文件操作。
     *
     * <p>读取 {@code operation} 字段后分派到具体子类，未知类型返回 {@code null} 。
     * 新增操作类型时必须在此登记，否则工作流反序列化会丢失该步骤。</p>
     *
     * @param json 操作描述。
     * @return 文件操作；类型未知返回 {@code null} 。
     */
    public static FileOperation parseFileOperation(JSONObject json) {
        String process = json.getString("process");

        if (FileProcessorAction.Image.name.equals(process)) {
            String operation = json.getString("operation");
            if (CropOperation.Operation.equals(operation)) {
                return new CropOperation(json);
            }
            else if (GrayscaleOperation.Operation.equals(operation)) {
                return new GrayscaleOperation(json);
            }
            else if (BrightnessOperation.Operation.equals(operation)) {
                return new BrightnessOperation(json);
            }
            else if (SharpeningOperation.Operation.equals(operation)) {
                return new SharpeningOperation(json);
            }
            else if (ReplaceColorOperation.Operation.equals(operation)) {
                return new ReplaceColorOperation(json);
            }
            else if (EliminateColorOperation.Operation.equals(operation)) {
                return new EliminateColorOperation(json);
            }
            else if (ReverseColorOperation.Operation.equals(operation)) {
                return new ReverseColorOperation(json);
            }
            else if (SteganographyOperation.Operation.equals(operation)) {
                return new SteganographyOperation(json);
            }
            else if (WatermarkOperation.Operation.equals(operation)) {
                return new WatermarkOperation(json);
            }
            else if (DetectObjectsOperation.Operation.equals(operation)) {
                return new DetectObjectsOperation(json);
            }
        }
        else if (FileProcessorAction.OfficeConvertTo.name.equals(process)) {
            return new OfficeConvertToOperation(json);
        }
        else if (FileProcessorAction.Video.name.equals(process)) {
            String operation = json.getString("operation");
            if (SnapshotOperation.Operation.equals(operation)) {
                return new SnapshotOperation(json);
            }
        }
        else if (FileProcessorAction.OCR.name.equals(process)) {
            return new OCROperation(json);
        }

        return null;
    }
}
