/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material;

import cube.aigc.psychology.material.house.*;
import cube.vision.BoundingBox;
import cube.vision.Box;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 房。
 */
public class House extends Thing {

    private List<Sidewall> sidewallList;

    private List<Roof> roofList;

    private List<RoofSkylight> roofSkylightList;

    private List<Chimney> chimneyList;

    private List<Window> windowList;

    private List<Door> doorList;

    private List<Curtain> curtainList;

    private List<WindowRailing> windowRailingList;

    private List<Smoke> smokeList;

    private List<Fence> fenceList;

    private List<Path> pathList;

    public House(JSONObject json) {
        super(json);
    }

    public House(BoundingBox boundingBox, Box box) {
        super(Label.House.name, boundingBox, box);
        this.backwardReasoning = true;
    }

    /**
     * 刷新检测框。
     *
     * @param boundingBox 检测框。
     * @param box         包围盒。
     */
    public void refreshBox(BoundingBox boundingBox, Box box) {
        this.boundingBox.x = Math.min(this.boundingBox.x, boundingBox.x);
        this.boundingBox.y = Math.min(this.boundingBox.y, boundingBox.y);
        int pX = Math.max(this.boundingBox.getX2(), boundingBox.getX2());
        int pY = Math.max(this.boundingBox.getY2(), boundingBox.getY2());

        this.boundingBox.width = pX - this.boundingBox.x;
        this.boundingBox.height = pY - this.boundingBox.y;

        this.box.refresh(box);
        this.area = Math.round(this.boundingBox.calculateArea() * 0.86f);
    }

    /** 刷新面积。 */
    public void refreshArea() {
        this.area = Math.round(this.boundingBox.calculateArea() * 0.86f);
    }

    /**
     * 获取子物件。
     *
     * @param label 子物件。
     * @return 子物件。
     */
    @Override
    public List<Thing> getSubThings(Label label) {
        List<Thing> things = null;
        switch (label) {
            case HouseSidewall:
                if (null != this.sidewallList) {
                    things = new ArrayList<>(this.sidewallList);
                }
                break;
            case HouseRoof:
                if (null != this.roofList) {
                    things = new ArrayList<>(this.roofList);
                }
                break;
            case HouseRoofSkylight:
                if (null != this.roofSkylightList) {
                    things = new ArrayList<>(this.roofSkylightList);
                }
                break;
            case HouseChimney:
                if (null != this.chimneyList) {
                    things = new ArrayList<>(this.chimneyList);
                }
                break;
            case HouseWindow:
                if (null != this.windowList) {
                    things = new ArrayList<>(this.windowList);
                }
                break;
            case HouseDoor:
                if (null != this.doorList) {
                    things = new ArrayList<>(this.doorList);
                }
                break;
            case HouseCurtain:
                if (null != this.curtainList) {
                    things = new ArrayList<>(this.curtainList);
                }
                break;
            case HouseWindowRailing:
                if (null != this.windowRailingList) {
                    things = new ArrayList<>(this.windowRailingList);
                }
                break;
            case HouseSmoke:
                if (null != this.smokeList) {
                    things = new ArrayList<>(this.smokeList);
                }
                break;
            case HouseFence:
                if (null != this.fenceList) {
                    things = new ArrayList<>(this.fenceList);
                }
                break;
            case HousePath:
                if (null != this.pathList) {
                    things = new ArrayList<>(this.pathList);
                }
                break;
            default:
                break;
        }
        return things;
    }

    /**
     * 判断是否涂鸦。
     *
     * @return 涂鸦。
     */
    @Override
    public boolean isDoodle() {
        // 涂鸦特征：轮廓密度高，层密度低
        if (this.texture.max > 0 && this.texture.hierarchy > 0) {
            // 判断最大值
            if (this.texture.max >= 1.0 && this.texture.density > 0.2) {
                // 判断标准差和层密度
                if (this.texture.standardDeviation >= 0.4 && this.texture.hierarchy <= 0.03) {
                    return true;
                }
                else if (this.texture.standardDeviation >= 2 && this.texture.hierarchy > 1.0) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 判断是否深度涂鸦。
     *
     * @return 深度涂鸦。
     */
    public boolean isDeepnessDoodle() {
        if (this.texture.max >= 5.0 && this.texture.avg > 3.0) {
            return true;
        }
        return false;
    }

    /**
     * 添加侧墙。
     *
     * @param sidewall 侧墙。
     */
    public void addSidewall(Sidewall sidewall) {
        if (null == this.sidewallList) {
            this.sidewallList = new ArrayList<>();
        }
        this.sidewallList.add(sidewall);
    }

    /**
     * 获取侧墙。
     *
     * @return 侧墙。
     */
    public List<Sidewall> getSidewalls() {
        return this.sidewallList;
    }

    /**
     * 判断是否侧墙。
     *
     * @return 侧墙。
     */
    public boolean hasSidewall() {
        return (null != this.sidewallList);
    }

    /**
     * 添加屋顶。
     *
     * @param roof 屋顶。
     */
    public void addRoof(Roof roof) {
        if (null == this.roofList) {
            this.roofList = new ArrayList<>();
        }
        this.roofList.add(roof);
    }

    /**
     * 获取屋顶。
     *
     * @return 屋顶。
     */
    public Roof getRoof() {
        if (null == this.roofList) {
            return null;
        }

        return this.roofList.get(0);
    }

    /**
     * 判断是否屋顶。
     *
     * @return 屋顶。
     */
    public boolean hasRoof() {
        return (null != this.roofList);
    }

    /**
     * 获取房顶相对整个房子的面积比例。
     * 例如：房顶面积30，房整体面积100，则比例为0.3
     * @return 计算结果。
     */
    public double getRoofAreaRatio() {
        if (null == this.roofList) {
            return 0;
        }

        return ((double) this.roofList.get(0).area)
                / ((double) this.area);
    }

    /**
     * 获取房顶相对整个房子的高度比例。
     * 例如：房顶高20，房子高100，则比例为0.2
     * @return 计算结果。
     */
    public double getRoofHeightRatio() {
        if (null == this.roofList) {
            return 0;
        }

        return ((double) this.roofList.get(0).box.height)
                / ((double) this.box.height);
    }

    /**
     * 添加屋顶天窗。
     *
     * @param roofSkylight 屋顶天窗。
     */
    public void addRoofSkylight(RoofSkylight roofSkylight) {
        if (null == this.roofSkylightList) {
            this.roofSkylightList = new ArrayList<>();
        }
        this.roofSkylightList.add(roofSkylight);
    }

    /**
     * 获取屋顶天窗。
     *
     * @return 屋顶天窗。
     */
    public List<RoofSkylight> getRoofSkylights() {
        return this.roofSkylightList;
    }

    /**
     * 判断是否屋顶天窗。
     *
     * @return 屋顶天窗。
     */
    public boolean hasRoofSkylight() {
        return (null != this.roofSkylightList);
    }

    /**
     * 添加烟囱。
     *
     * @param chimney 烟囱。
     */
    public void addChimney(Chimney chimney) {
        if (null == this.chimneyList) {
            this.chimneyList = new ArrayList<>();
        }
        this.chimneyList.add(chimney);
    }

    /**
     * 获取烟囱。
     *
     * @return 烟囱。
     */
    public List<Chimney> getChimneys() {
        return this.chimneyList;
    }

    /**
     * 判断是否烟囱。
     *
     * @return 烟囱。
     */
    public boolean hasChimney() {
        return (null != this.chimneyList);
    }

    /**
     * 添加窗。
     *
     * @param window 窗。
     */
    public void addWindow(Window window) {
        if (null == this.windowList) {
            this.windowList = new ArrayList<>();
        }
        this.windowList.add(window);
    }

    /**
     * 获取窗。
     *
     * @return 窗。
     */
    public List<Window> getWindows() {
        return this.windowList;
    }

    /**
     * 判断是否窗。
     *
     * @return 窗。
     */
    public boolean hasWindow() {
        return (null != this.windowList);
    }

    /**
     * 获取最大窗面积比例。
     *
     * @return 最大窗面积比例。
     */
    public double getMaxWindowAreaRatio() {
        double maxRatio = 0;
        for (Window window : this.windowList) {
            double ratio = ((double) window.area)
                    / ((double) this.area);
            if (ratio > maxRatio) {
                maxRatio = ratio;
            }
        }
        return maxRatio;
    }

    /**
     * 添加门。
     *
     * @param door 门。
     */
    public void addDoor(Door door) {
        if (null == this.doorList) {
            this.doorList = new ArrayList<>();
        }
        this.doorList.add(door);
    }

    /**
     * 获取门。
     *
     * @return 门。
     */
    public List<Door> getDoors() {
        return this.doorList;
    }

    /**
     * 判断是否门。
     *
     * @return 门。
     */
    public boolean hasDoor() {
        return (null != this.doorList);
    }

    /**
     * 判断是否打开门。
     *
     * @return 打开门。
     */
    public boolean hasOpenDoor() {
        if (null == this.doorList) {
            return false;
        }

        for (Door door : this.doorList) {
            if (door.isOpen()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取最大门面积比例。
     *
     * @return 最大门面积比例。
     */
    public double getMaxDoorAreaRatio() {
        double maxRatio = 0;
        for (Door door : this.doorList) {
            double ratio = ((double) door.area)
                    / ((double) this.area * 0.95);
            if (ratio > maxRatio) {
                maxRatio = ratio;
            }
        }
        return maxRatio;
    }

    /**
     * 所有门和窗总面积比例。
     * @return 计算结果。
     */
    public double getAllDoorsAndWindowsAreaRatio() {
        double totalArea = 0;
        if (null != this.windowList) {
            for (Window window : this.windowList) {
                totalArea += window.area;
            }
        }
        if (null != this.doorList) {
            for (Door door : this.doorList) {
                totalArea += door.area;
            }
        }
        return totalArea / this.area;
    }

    /**
     * 添加窗帘。
     *
     * @param curtain 窗帘。
     */
    public void addCurtain(Curtain curtain) {
        if (null == this.curtainList) {
            this.curtainList = new ArrayList<>();
        }
        this.curtainList.add(curtain);
    }

    /**
     * 获取窗帘。
     *
     * @return 窗帘。
     */
    public List<Curtain> getCurtains() {
        return this.curtainList;
    }

    /**
     * 判断是否窗帘。
     *
     * @return 窗帘。
     */
    public boolean hasCurtain() {
        return (null != this.curtainList);
    }

    /**
     * 判断是否打开窗帘。
     *
     * @return 打开窗帘。
     */
    public boolean hasOpenCurtain() {
        if (null == this.curtainList) {
            return false;
        }

        for (Curtain curtain : this.curtainList) {
            if (curtain.isOpen()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 添加窗栏。
     *
     * @param windowRailing 窗栏。
     */
    public void addWindowRailing(WindowRailing windowRailing) {
        if (null == this.windowRailingList) {
            this.windowRailingList = new ArrayList<>();
        }
        this.windowRailingList.add(windowRailing);
    }

    /**
     * 获取窗栏。
     *
     * @return 窗栏。
     */
    public List<WindowRailing> getWindowRailings() {
        return this.windowRailingList;
    }

    /**
     * 添加烟。
     *
     * @param smoke 烟。
     */
    public void addSmoke(Smoke smoke) {
        if (null == this.smokeList) {
            this.smokeList = new ArrayList<>();
        }
        this.smokeList.add(smoke);
    }

    /**
     * 获取烟。
     *
     * @return 烟。
     */
    public List<Smoke> getSmoke() {
        return this.smokeList;
    }

    /**
     * 添加栅栏。
     *
     * @param fence 栅栏。
     */
    public void addFence(Fence fence) {
        if (null == this.fenceList) {
            this.fenceList = new ArrayList<>();
        }
        this.fenceList.add(fence);
    }

    /**
     * 获取栅栏。
     *
     * @return 栅栏。
     */
    public List<Fence> getFences() {
        return this.fenceList;
    }

    /**
     * 判断是否栅栏。
     *
     * @return 栅栏。
     */
    public boolean hasFence() {
        return (null != this.fenceList);
    }

    /**
     * 添加路径。
     *
     * @param path 路径。
     */
    public void addPath(Path path) {
        if (null == this.pathList) {
            this.pathList = new ArrayList<>();
        }
        this.pathList.add(path);
    }

    /**
     * 获取路径列表。
     *
     * @return 路径列表。
     */
    public List<Path> getPaths() {
        return this.pathList;
    }

    /**
     * 判断是否路径。
     *
     * @return 路径。
     */
    public boolean hasPath() {
        return (null != this.pathList);
    }

    /**
     * 判断是否弯曲小路。
     *
     * @return 弯曲小路。
     */
    public boolean hasCurvePath() {
        if (null == this.pathList) {
            return false;
        }

        for (Path path : this.pathList) {
            if (path.isCurve()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否石板路。
     *
     * @return 石板路。
     */
    public boolean hasCobbledPath() {
        if (null == this.pathList) {
            return false;
        }

        for (Path path : this.pathList) {
            if (path.isCobbled()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 统计组件列表。
     *
     * @return 数量。
     */
    @Override
    public int numComponents() {
        int num = 0;
        if (null != roofList) {
            num += roofList.size();
        }
        if (null != roofSkylightList) {
            num += roofSkylightList.size();
        }
        if (null != chimneyList) {
            num += chimneyList.size();
        }
        if (null != windowList) {
            num += windowList.size();
        }
        if (null != doorList) {
            num += doorList.size();
        }
        if (null != curtainList) {
            num += curtainList.size();
        }
        if (null != windowRailingList) {
            num += windowRailingList.size();
        }
        if (null != smokeList) {
            num += smokeList.size();
        }
        return num;
    }

    /**
     * 序列化为 JSON 。
     *
     * @return JSON 表示。
     */
    @Override
    public JSONObject toJSON() {
        JSONObject json = super.toJSON();
        return json;
    }
}
