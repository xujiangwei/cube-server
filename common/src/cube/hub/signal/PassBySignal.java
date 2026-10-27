/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.hub.signal;

import cube.hub.SignalBuilder;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 透传信令。
 */
public class PassBySignal extends Signal {

    /** 名称。 */
    public final static String NAME = "PassBy";

    private boolean broadcast = false;

    private List<Long> destinations;

    private List<Signal> signals;

    public PassBySignal() {
        super(NAME);
    }

    public PassBySignal(boolean broadcast) {
        super(NAME);
        this.broadcast = broadcast;
    }

    public PassBySignal(JSONObject json) {
        super(json);
        this.broadcast = json.getBoolean("broadcast");

        if (json.has("signals")) {
            this.signals = new ArrayList<>();

            JSONArray array = json.getJSONArray("signals");
            for (int i = 0; i < array.length(); ++i) {
                Signal signal = SignalBuilder.build(array.getJSONObject(i));
                if (null != signal) {
                    this.signals.add(signal);
                }
            }
        }

        if (json.has("destinations")) {
            this.destinations = new ArrayList<>();

            JSONArray array = json.getJSONArray("destinations");
            for (int i = 0; i < array.length(); ++i) {
                this.destinations.add(array.getLong(i));
            }
        }
    }

    /**
     * 判断是否广播。
     *
     * @return 广播返回 {@code true} 。
     */
    public boolean isBroadcast() {
        return this.broadcast;
    }

    /**
     * 添加目的地。
     *
     * @param pretenderId 目的地。
     */
    public void addDestination(Long pretenderId) {
        if (null == this.destinations) {
            this.destinations = new ArrayList<>();
            this.destinations.add(pretenderId);
        }
        else {
            if (!this.destinations.contains(pretenderId)) {
                this.destinations.add(pretenderId);
            }
        }

        this.broadcast = false;
    }

    /**
     * 获取目的地。
     *
     * @return 目的地。
     */
    public List<Long> getDestinations() {
        return this.destinations;
    }

    /**
     * 添加子信号。
     *
     * @param signal 信号对象。
     */
    public void addSignal(Signal signal) {
        if (null == this.signals) {
            this.signals = new ArrayList<>();
        }

        this.signals.add(signal);
    }

    /**
     * 移除子信号。
     *
     * @param signal 信号对象。
     */
    public void removeSignal(Signal signal) {
        if (null != this.signals) {
            this.signals.remove(signal);
        }
    }

    /**
     * 获取全部子信号。
     *
     * @return 信号列表；无子信号时为空列表。
     */
    public List<Signal> getSignals() {
        return this.signals;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        json.put("broadcast", this.broadcast);

        if (null != this.destinations) {
            JSONArray array = new JSONArray();
            for (Long id : this.destinations) {
                array.put(id.longValue());
            }
            json.put("destinations", array);
        }

        if (null != this.signals) {
            JSONArray array = new JSONArray();
            for (Signal signal : this.signals) {
                array.put(signal.toJSON());
            }
            json.put("signals", array);
        }

        return json;
    }
}
