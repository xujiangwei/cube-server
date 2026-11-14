"""Authoritative Unit operation definitions. Standard library only; never imports models.

The generated JSON is consumed by Java and standalone Python deployments. Bindings
are private metadata; public tool parameters contain only business inputs.
"""
from copy import deepcopy


def text(limit=4096, minimum=1, choices=None):
    schema = dict(type="string", minLength=minimum, maxLength=limit)
    if choices is not None:
        schema["enum"] = list(choices)
    return schema


def number(low, high, integer=False):
    return dict(type="integer" if integer else "number", minimum=low, maximum=high)


def array(items, maximum=32, minimum=0):
    return dict(type="array", items=items, minItems=minimum, maxItems=maximum)


def obj(properties, required=()):
    return dict(type="object", properties=properties, required=list(required), additionalProperties=False)


BOOL = dict(type="boolean")
REF = text(128)
FILE = obj(dict(kind=text(16, choices=("input", "artifact", "media")), ref=REF), ("kind", "ref"))
FILES = array(FILE, 8, 1)
GENERATION = dict(temperature=number(0, 2), topP=number(0, 1),
                  maxNewTokens=number(1, 32768, True), repetitionPenalty=number(0.1, 10))
RESULT = obj(dict(operationId=text(128), executionRef=REF,
                  state=text(16, choices=("succeeded", "running", "stopping", "failed", "unknown")),
                  answer=text(4096, 0), dataJson=text(8192, 0),
                  artifactRefs=array(REF, 32), warnings=array(text(128), 16)),
             ("operationId", "executionRef", "state", "answer", "dataJson", "artifactRefs", "warnings"))


def build_catalog():
    units, operations = [], []

    def unit(key, name, slug, module, class_name, attribute, service="AIGC", plugin=None):
        row = dict(key=key, name=name, skill="units/" + slug, service=service,
                   source=dict(module=module, className=class_name, attribute=attribute))
        if plugin:
            row["plugin"] = plugin
        units.append(row)
        return row

    def operation(u, name, description, properties, required, action, handler,
                  effect="read-only", mode="sync", method=None, media=(), dependencies=(),
                  usage="request", approval="policy", fixed=None, output=RESULT):
        slug = u["skill"].split("/")[1]
        operation_id = slug + "." + name + ".v1"
        binding = dict(service=u["service"], action=action, handler=handler)
        if method:
            binding["method"] = method
        if fixed:
            binding["fixed"] = fixed
        operations.append(dict(operationId=operation_id, version="1", unitKey=u["key"],
                               unitName=u["name"], skill=u["skill"],
                               toolName="units." + slug + "." + name,
                               brokerOperation="units." + operation_id,
                               permission="unit." + slug + "." + name,
                               description=description, adapterId=u["service"].lower(),
                               executionMode=mode, effect=effect, approval=approval,
                               inputSchema=obj(deepcopy(properties), required),
                               outputSchema=deepcopy(output), inputMediaTypes=list(media),
                               outputMediaTypes=["application/json"], dependencies=list(dependencies),
                               usageKind=usage, limits=dict(timeoutMillis=240000, maxResultBytes=12288),
                               binding=binding))

    for key, name, module, cls, attr in (
        ("unit.baize", "Baize", "cube.unit_baize", "BaizeUnit", "baizeUnit"),
        ("unit.baize2", "Baize2", "cube.unit_baize_2", "Baize2Unit", "baize2Unit"),
    ):
        u = unit(key, name, name.lower(), module, cls, attr)
        operation(u, "generate", "生成文本或结构化回答", dict(content=text(16384), **GENERATION),
                  ("content",), "chatCompletion", "completions.start", method="execute", usage="tokens")

    u = unit("unit.multimodal", "OmniMultimodal", "multimodal", "cube.unit_multimodal", "MultimodalUnit", "multimodalUnit")
    operation(u, "analyze", "理解文本及获准的图像、音频或视频输入", dict(content=text(4096, 0), files=FILES),
              ("content",), "multimodal", "executeMultimodal", method="execute",
              media=("image/jpeg", "image/png", "audio/*", "video/*"), usage="tokens")

    for key, name, slug, module, cls, attr, tasks, update in (
        ("unit.omni_perception", "OmniPerception", "omni-perception", "cube.unit_omni_perception", "OmniPerceptionUnit", "omniPerceptionUnit",
         ("detection", "grounding_single", "counting", "reasoning_detection", "brief_region_caption", "region_ocr", "vqa"), True),
        ("unit.omni_ground", "OmniGround", "omni-ground", "cube.unit_omni_ground", "OmniGroundUnit", "omniGroundUnit",
         ("detection", "grounding_single", "grounding_multi", "text_grounding", "text_detection", "gui_grounding", "point"), False),
        ("unit.omni_vl", "OmniVL", "omni-vl", "cube.unit_omni_vl", "OmniVLUnit", "omniVLUnit", None, True),
    ):
        u = unit(key, name, slug, module, cls, attr)
        props = dict(content=text(4096, 0), files=array(FILE, 1, 1))
        req = ["content", "files"]
        if tasks:
            props["task"] = text(64, choices=tasks)
            if name == "OmniGround":
                props.update(categories=array(text(128), 64, 1),
                             mode=text(16, choices=("fast", "slow", "hybrid")),
                             outputType=text(16, choices=("box", "point")))
            else:
                props.update(lang=text(2, choices=("zh", "en")),
                             bboxList=array(array(number(0, 100000), 4, 4), 100),
                             targetRegionIndexes=array(number(0, 99, True), 100, 1))
            req.append("task")
        operation(u, "analyze", "分析单个获准媒体输入", props, req, "multimodal", "executeMultimodal",
                  method="execute", media=("video/*",) if tasks is None else ("image/jpeg", "image/png"),
                  mode="job" if tasks is None else "sync", usage="tokens", fixed=dict(operation="analyze"))
        start_props = dict(content=text(4096, 0))
        if tasks:
            start_props["task"] = text(64, choices=("detection",) if name == "OmniGround" else
                                            ("detection", "grounding_single", "counting", "reasoning_detection"))
        start_props.update(sourceRef=REF, maxDurationSeconds=number(1, 86400, True))
        operation(u, "start", "开始有时长限制的媒体分析会话", start_props,
                  [k for k in req if k != "files"] + ["sourceRef", "maxDurationSeconds"],
                  "multimodal", "executeMultimodal", method="execute", effect="session-control", mode="session",
                  media=("video/rtsp",), usage="video-seconds", fixed=dict(operation="start"))
        for control in (("status", "查询"), ("update", "更新"), ("stop", "停止")):
            if control[0] == "update" and not update:
                continue
            properties = dict(executionRef=REF)
            required = ["executionRef"]
            if control[0] == "update":
                properties["content"] = text(4096)
                required.append("content")
            operation(u, control[0], control[1] + "媒体分析会话", properties, required,
                      "multimodal", "executeMultimodal", method="execute",
                      effect="read-only" if control[0] == "status" else "session-control",
                      fixed=dict(operation=control[0]))

    u = unit("unit.specialized_detection", "SpecializedDetection", "detection", "cube.unit_specialized_detection", "SpecializedDetectionUnit", "specializedDetectionUnit")
    opts = dict(profile=text(64), classes=array(text(128), 128, 1), confidence=number(0, 1), sliced=BOOL)
    operation(u, "detect", "按真实检测档案和类别检测图片", dict(files=array(FILE, 1, 1), **opts),
              ("files", "profile"), "specializedDetection", "executeSpecializedDetection", method="execute",
              media=("image/jpeg", "image/png"), usage="images", fixed=dict(operation="detect"))
    operation(u, "start", "开始有时长限制的视频检测会话", dict(sourceRef=REF, maxDurationSeconds=number(1, 86400, True), **opts),
              ("sourceRef", "profile", "maxDurationSeconds"), "specializedDetection", "executeSpecializedDetection",
              method="execute", effect="session-control", mode="session", media=("video/rtsp",),
              usage="video-seconds", fixed=dict(operation="start"))
    for name in ("status", "stop"):
        operation(u, name, "查询检测会话" if name == "status" else "停止检测会话", dict(executionRef=REF),
                  ("executionRef",), "specializedDetection", "executeSpecializedDetection", method="execute",
                  effect="read-only" if name == "status" else "session-control", fixed=dict(operation=name))

    u = unit("unit.embedding", "Embedding", "embedding", "cube.unit_embedding", "EmbeddingUnit", "embeddingUnit")
    operation(u, "encode", "编码一组文本为向量产物", dict(texts=array(text(8192), 128, 1)), ("texts",),
              "", "", method="embeddings", usage="input-tokens")
    operation(u, "search", "检索已授权数据集中的相似文本", dict(query=text(8192), datasetRef=REF), ("query", "datasetRef"),
              "semanticSearch", "executeSemanticSearch", method="semanticSearch")
    operation(u, "rerank", "对已授权数据集或文件执行检索重排", dict(queries=array(text(8192), 32, 1), datasetRef=REF, files=FILES),
              ("queries",), "retrieveReRank", "executeRetrieveReRank", method="retrieveReRank")

    u = unit("unit.speech", "Speech", "speech", "cube.unit_speech", "SpeechUnit", "speechUnit")
    operation(u, "recognize", "识别音频中的文本", dict(file=FILE), ("file",), "automaticSpeechRecognition", "executeAutomaticSpeechRecognition",
              method="recognize", media=("audio/*",), usage="audio-seconds")
    u = unit("unit.audio", "Audio", "audio", "cube.unit_audio", "AudioUnit", "audioUnit")
    operation(u, "diarize", "按说话人分割音频并返回分析产物", dict(file=FILE, preprocess=BOOL), ("file",), "speechDiarization", "executeSpeakerDiarization",
              method="performSpeakerDiarization", mode="job", media=("audio/*",), dependencies=("speech.recognize.v1",), usage="audio-seconds")
    u = unit("unit.facial_expression", "FacialExpression", "facial-expression", "cube.unit_facial_expression", "FacialExpressionUnit", "facialExpressionUnit")
    operation(u, "recognize", "识别表情并可生成可视化产物", dict(file=FILE, visualize=BOOL), ("file",), "facialExpressionRecognition", "recognizeFacialExpression",
              method="recognize", media=("image/jpeg", "image/png"), usage="images")

    u = unit("unit.knowledge", "KnowledgeBase", "knowledge", "knowledge.unit_knowledge", "KnowledgeBaseUnit", "knowledgeUnit")
    operation(u, "generate", "从已授权知识库生成检索内容", dict(knowledgeRef=REF, query=text(8192), threshold=number(0, 1), topK=number(1, 100, True)),
              ("knowledgeRef", "query"), "generateKnowledge", "generateKnowledge", method="generateKnowledge", dependencies=("embedding.encode.v1",))
    knowledge_ops = (
        ("list-articles", "listKnowledgeArticles", "listKnowledgeArticles", "listKnowledgeArticleIds", "read-only", ()),
        ("activate-document", "activateKnowledgeDoc", "activateKnowledgeDoc", "activateKnowledgeDoc", "write", ("documentRef",)),
        ("deactivate-document", "deactivateKnowledgeDoc", "deactivateKnowledgeDoc", "deactivateKnowledgeDoc", "write", ("documentRef",)),
        ("activate-documents", "batchActivateKnowledgeDocs", "batchActivateKnowledgeDocs", "batchActivateKnowledgeDocuments", "write", ("documentRefs",)),
        ("deactivate-documents", "batchDeactivateKnowledgeDocs", "batchDeactivateKnowledgeDocs", "batchDeactivateKnowledgeDocuments", "write", ("documentRefs",)),
        ("activate-article", "activateKnowledgeArticle", "activateKnowledgeArticle", "activateKnowledgeArticle", "write", ("articleRef",)),
        ("deactivate-article", "deactivateKnowledgeArticle", "deactivateKnowledgeArticle", "deactivateKnowledgeArticle", "write", ("articleRef",)),
        ("delete-store", "deleteKnowledgeStore", "deleteKnowledgeStore", "deleteKnowledgeStore", "write", ()),
        ("backup-store", "backupKnowledgeStore", "backupKnowledgeStore", "backupKnowledgeStore", "write", ()),
        ("list-backups", "getBackupKnowledgeStores", "getBackupKnowledgeStores", "getBackupKnowledgeStores", "read-only", ()),
    )
    for name, action, handler, method, effect, extra in knowledge_ops:
        props = dict(knowledgeRef=REF)
        for field in extra:
            props[field] = array(REF, 128, 1) if field.endswith("Refs") else REF
        operation(u, name, "对获准知识资源执行 " + name, props, ("knowledgeRef",) + extra, action, handler,
                  method=method, effect=effect, mode="job" if effect == "write" else "sync",
                  approval="required" if name == "delete-store" else "policy")

    u = unit("unit.spider", "Spider", "spider", "cube.unit_spider", "SpiderUnit", "spiderUnit")
    operation(u, "extract", "提取获准来源中的正文", dict(sourceRef=REF, selector=text(1024)), ("sourceRef", "selector"),
              "extractURLContent", "extractURLContent", method="execute")
    u = unit("unit.psychology", "Psychology", "psychology", "cube.unit_psychology", "PsychologyUnit", "psychologyUnit")
    for name, action, handler, method, props, req, deps in (
        ("check-painting", "checkPsychologyPainting", "checkPsychologyPainting", "checkPainting", dict(file=FILE), ("file",), ("cv.detect.v1",)),
        ("predict-painting", "predictPsychologyPainting", "predictPsychologyPainting", "detectPainting", dict(files=FILES, adjust=BOOL), ("files",), ("cv.detect.v1",)),
        ("predict-factors", "predictPsychologyFactors", "predictPsychologyFactors", "predictFactor", dict(attributesJson=text(8192), indicatorsJson=text(8192)), ("attributesJson", "indicatorsJson"), ()),
        ("speech-emotion", "speechEmotionRecognition", "recognizeSpeechEmotion", "recognizeSpeechEmotion", dict(file=FILE), ("file",), ()),
    ):
        operation(u, name, "心理分析：" + name, props, req, action, handler, method=method,
                  media=("audio/*",) if name == "speech-emotion" else ("image/jpeg", "image/png") if "painting" in name else (), dependencies=deps)

    u = unit("service.cv", "CV", "cv", "cube.cv_service", "CVService", "cvService", service="CV")
    for name, action, handler in (
        ("detect-barcode", "detectBarCode", "detectBarCode"),
        ("detect", "objectDetection", "detectObjectWith"),
        ("estimate-pose", "poseEstimation", "estimatePoseWith"),
        ("estimate-hand", "handEstimation", "estimateHandWith"),
        ("clip-paper", "clipPaper", "clipPaper"),
    ):
        operation(u, name, "图像处理：" + name, dict(files=FILES, visualize=BOOL), ("files",), action, handler,
                  media=("image/jpeg", "image/png"), usage="images")
    barcode = obj(dict(data=text(1024), header=text(256, 0), footer=text(256, 0)), ("data",))
    operation(u, "combine-barcodes", "生成条码文档产物", dict(barcodes=array(barcode, 128, 1)), ("barcodes",), "combineBarcodes", "combineBarcodes")
    operation(u, "match-similarity", "比较图片与获准模板", dict(file=FILE, templateRefs=array(REF, 32, 1)), ("file", "templateRefs"),
              "matchSimilarity", "matchSimilarity", media=("image/jpeg", "image/png"), usage="images")

    u = unit("plugin.omniforest.OmniForest", "OmniForest", "omniforest", "plugins.omniforest.unit", "OmniForestUnit", "", plugin="omniforest")
    operation(u, "detect", "检测图片中的树冠", dict(files=array(FILE, 1, 1), scoreThreshold=number(0, 1)), ("files",),
              "pluginInvoke", "pluginRouter.invoke", method="execute", media=("image/jpeg", "image/png"), usage="images", fixed=dict(action="detection"))

    return dict(schemaVersion=1, units=units, operations=operations)


# Every registered Action must be represented above or have an explicit private
# lifecycle/transport/unsupported disposition. A newly registered business Action
# must fail coverage until its operation contract is supplied.
ACTION_DISPOSITIONS = {
    "submitSegments": "internal callback", "setup": "lifecycle registration",
    "specializedDetectionStatus": "internal readiness report",
    "pluginStatus": "internal plugin readiness report",
    "cancelChatCompletion": "internal generation cancellation",
    "chat": "legacy alias of chatCompletion", "textToText": "legacy alias of chatCompletion",
    "textToImage": "unsupported: executeTextToImage has no implementation",
}

# Public Python methods which are not separate service operations. This is an
# explicit inventory: adding a public method requires an operation or a reason.
METHOD_DISPOSITIONS = {
    "*": {name: "unit lifecycle" for name in ("config", "initialize", "stop", "warmup")},
    "Baize": {"generationExecutor": "runtime property", "baseUrl": "runtime property",
              "apiKey": "runtime secret", "timeoutSeconds": "runtime property",
              "execute_chat_completion": "generate implementation"},
    "Baize2": {"execute_chat_completion": "generate implementation"},
    "OmniGround": {"parse_boxes": "result parsing helper", "parse_points": "result parsing helper"},
    "SpecializedDetection": {"start_status_delivery": "readiness reporting"},
    "Embedding": {"embedding": "single item encode helper", "semanticSearchInDB": "search implementation",
                  "retrieveReRankWithFiles": "rerank file implementation"},
    "Speech": {"recognizeSamples": "recognition implementation", "preprocess": "recognition preprocessing"},
    "Audio": {"preprocess": "diarization preprocessing"},
    "FacialExpression": {"recognizeBy": "recognition implementation"},
    "Psychology": {name: "prediction implementation" for name in
                   ("resetModel", "reset", "preprocess", "predictPainting", "deduce", "calc",
                    "drawBox", "convert2xywh", "makeMaterial")},
    "OmniForest": {"check_resources": "startup resource validation"},
}
