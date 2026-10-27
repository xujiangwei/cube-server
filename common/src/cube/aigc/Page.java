/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc;

import cube.common.JSONable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 页面。
 */
public class Page implements JSONable {

    /** 页面地址。 */
    public String url;

    /** 页面标题。 */
    public String title;

    /** 从页面提取的正文段落。 */
    public List<String> textList;

    /** 从页面提取的图片地址。 */
    public List<String> imageList;

    public Page(JSONObject json) {
        if (json.has("url")) {
            this.url = json.getString("url");
        }
        else if (json.has("currentUrl")) {
            this.url = json.getString("currentUrl");
        }

        if (json.has("title")) {
            this.title = json.getString("title");
        }
        if (json.has("textList")) {
            this.textList = new ArrayList<>();
            JSONArray array = json.getJSONArray("textList");
            for (int i = 0; i < array.length(); ++i) {
                this.textList.add(array.getString(i).trim());
            }
        }
        if (json.has("imgList")) {
            this.imageList = new ArrayList<>();
            JSONArray array = json.getJSONArray("imgList");
            for (int i = 0; i < array.length(); ++i) {
                this.imageList.add(array.getString(i));
            }
        }
    }

    /**
     * 过滤过短的正文段落。
     *
     * <p>剔除导航、版权声明等碎片，只保留可用于问答的正文。</p>
     */
    public void filterShortText() {
        if (null == this.textList) {
            return;
        }

        Iterator<String> iter = this.textList.iterator();
        while (iter.hasNext()) {
            String text = iter.next();
            if (text.length() <= 5) {
                iter.remove();
            }
        }
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = new JSONObject();
        if (null != this.url) {
            json.put("url", this.url);
        }

        if (null != this.title) {
            json.put("title", this.title);
        }

        if (null != this.textList) {
            JSONArray array = new JSONArray();
            for (String text : this.textList) {
                array.put(text);
            }
            json.put("textList", array);
        }

        if (null != this.imageList) {
            JSONArray array = new JSONArray();
            for (String url : this.imageList) {
                array.put(url);
            }
            json.put("imgList", array);
        }

        return json;
    }

    /**
     * 序列化为精简 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toCompactJSON() {
        JSONObject json = new JSONObject();
        if (null != this.url) {
            json.put("url", this.url);
        }

        if (null != this.title) {
            json.put("title", this.title);
        }
        return json;
    }
}
