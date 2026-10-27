/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material;

import cube.aigc.psychology.material.tree.*;
import cube.vision.BoundingBox;
import cube.vision.Box;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 树。
 */
public class Tree extends Thing {

    private List<Trunk> trunkList;

    private List<Branch> branchList;

    private List<Canopy> canopyList;

    private List<Root> rootList;

    private List<Fruit> fruitList;

    private List<Hole> holeList;

    private List<DroopingLeaves> droopingLeavesList;

    public Tree(JSONObject json) {
        super(json);
    }

    public Tree(BoundingBox boundingBox, Box box) {
        super(Label.Tree.name, boundingBox, box);
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
        this.area = Math.round(this.boundingBox.calculateArea() * 0.72f);
    }

    /** 刷新面积。 */
    public void refreshArea() {
        this.area = Math.round(this.boundingBox.calculateArea() * 0.72f);
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
            case TreeTrunk:
                if (null != this.trunkList) {
                    things = new ArrayList<>(this.trunkList);
                }
                break;
            case TreeBranch:
                if (null != this.branchList) {
                    things = new ArrayList<>(this.branchList);
                }
                break;
            case TreeCanopy:
                if (null != this.canopyList) {
                    things = new ArrayList<>(this.canopyList);
                }
                break;
            case TreeRoot:
                if (null != this.rootList) {
                    things = new ArrayList<>(this.rootList);
                }
                break;
            case TreeFruit:
                if (null != this.fruitList) {
                    things = new ArrayList<>(this.fruitList);
                }
                break;
            case TreeHole:
                if (null != this.holeList) {
                    things = new ArrayList<>(this.holeList);
                }
                break;
            case TreeDrooping:
                if (null != this.droopingLeavesList) {
                    things = new ArrayList<>(this.droopingLeavesList);
                }
                break;
            default:
                break;
        }
        return things;
    }

    /**
     * 添加树干。
     *
     * @param trunk 树干。
     */
    public void addTrunk(Trunk trunk) {
        if (null == this.trunkList) {
            this.trunkList = new ArrayList<>();
        }
        this.trunkList.add(trunk);
    }

    /**
     * 获取树干。
     *
     * @return 树干。
     */
    public List<Trunk> getTrunks() {
        return this.trunkList;
    }

    /**
     * 判断是否树干。
     *
     * @return 树干。
     */
    public boolean hasTrunk() {
        return (null != this.trunkList);
    }

    /**
     * 获取树干相较于树的宽度比例。
     * 例如：树干宽30，树整体宽度100，则返回0.3
     * @return 计算结果。
     */
    public double getTrunkWidthRatio() {
        if (null == this.trunkList) {
            return 0;
        }

        return ((double) this.trunkList.get(0).box.width * 0.7f)
                / ((double) this.box.width);
    }

    /**
     * 获取树干检测框。
     *
     * @return 树干检测框。
     */
    public BoundingBox getTrunkBoundingBox() {
        if (null == this.trunkList) {
            return new BoundingBox((int) Math.round(this.boundingBox.x * 0.3), this.boundingBox.y,
                    (int) Math.round(this.boundingBox.width * 0.8), this.boundingBox.height);
        }

        return this.trunkList.get(0).boundingBox;
    }

    /**
     * 添加树枝。
     *
     * @param branch 树枝。
     */
    public void addBranch(Branch branch) {
        if (null == this.branchList) {
            this.branchList = new ArrayList<>();
        }
        this.branchList.add(branch);
    }

    /**
     * 获取树枝。
     *
     * @return 树枝。
     */
    public List<Branch> getBranches() {
        return this.branchList;
    }

    /**
     * 添加树冠。
     *
     * @param canopy 树冠。
     */
    public void addCanopy(Canopy canopy) {
        if (null == this.canopyList) {
            this.canopyList = new ArrayList<>();
        }
        this.canopyList.add(canopy);
    }

    /**
     * 获取树冠。
     *
     * @return 树冠。
     */
    public List<Canopy> getCanopies() {
        return this.canopyList;
    }

    /**
     * 判断是否树冠。
     *
     * @return 树冠。
     */
    public boolean hasCanopy() {
        return (null != this.canopyList);
    }

    /**
     * 计算树冠面积比例。
     * @return 计算结果。
     */
    public double getCanopyAreaRatio() {
        if (null == this.canopyList) {
            return 0;
        }

        Canopy canopy = this.getMaxAreaThing(this.canopyList);
        if (null == canopy) {
            return 0;
        }

        return ((double) canopy.area) / ((double) this.area);
    }

    /**
     * 计算树冠高度比例。
     * @return 计算结果。
     */
    public double getCanopyHeightRatio() {
        if (null == this.canopyList) {
            return 0;
        }

        double height = 0;
        for (Canopy canopy : this.canopyList) {
            if (canopy.getHeight() > height) {
                height = canopy.getHeight();
            }
        }

        return height / this.getHeight();
    }

    /**
     * 树冠涂鸦
     * @return 满足条件返回 {@code true} 。
     */
    public boolean isDoodleCanopy() {
        if (null == this.canopyList) {
            return false;
        }

        Canopy canopy = this.getMaxAreaThing(this.canopyList);
        if (null == canopy) {
            return false;
        }

        if (canopy.texture.max > 4 && canopy.texture.avg > 2 && canopy.texture.hierarchy < 0.7) {
            return true;
        }
        else {
            return false;
        }
    }

    /**
     * 添加树根。
     *
     * @param root 树根。
     */
    public void addRoot(Root root) {
        if (null == this.rootList) {
            this.rootList = new ArrayList<>();
        }
        this.rootList.add(root);
    }

    /**
     * 获取树根。
     *
     * @return 树根。
     */
    public List<Root> getRoots() {
        return this.rootList;
    }

    /**
     * 判断是否树根。
     *
     * @return 树根。
     */
    public boolean hasRoot() {
        return (null != this.rootList);
    }

    /**
     * 添加果实。
     *
     * @param fruit 果实。
     */
    public void addFruit(Fruit fruit) {
        if (null == this.fruitList) {
            this.fruitList = new ArrayList<>();
        }
        this.fruitList.add(fruit);
    }

    /**
     * 获取果实。
     *
     * @return 果实。
     */
    public List<Fruit> getFruits() {
        return this.fruitList;
    }

    /**
     * 判断是否果实。
     *
     * @return 果实。
     */
    public boolean hasFruit() {
        return (null != this.fruitList);
    }

    /**
     * 统计果实。
     *
     * @return 数量。
     */
    public int numFruits() {
        return (null != this.fruitList) ? this.fruitList.size() : 0;
    }

    /**
     * 获取果实面积比例。
     *
     * @return 果实面积比例。
     */
    public double[] getFruitAreaRatios() {
        if (null == this.fruitList || null == this.canopyList) {
            return null;
        }

        Canopy canopy = this.getMaxAreaThing(this.canopyList);
        double totalArea = canopy.area;

        double[] result = new double[this.fruitList.size()];
        int index = 0;
        for (Fruit fruit : this.fruitList) {
            double area = fruit.area;
            double ratio = area / totalArea;
            result[index] = ratio;
            ++index;
        }
        return result;
    }



    /**
     * 添加洞。
     *
     * @param hole 洞。
     */
    public void addHole(Hole hole) {
        if (null == this.holeList) {
            this.holeList = new ArrayList<>();
        }
        this.holeList.add(hole);
    }

    /**
     * 获取洞。
     *
     * @return 洞。
     */
    public List<Hole> getHoles() {
        return this.holeList;
    }

    /**
     * 判断是否洞。
     *
     * @return 洞。
     */
    public boolean hasHole() {
        return (null != this.holeList);
    }

    /**
     * 添加下垂。
     *
     * @param droopingLeaves 下垂。
     */
    public void addDrooping(DroopingLeaves droopingLeaves) {
        if (null == this.droopingLeavesList) {
            this.droopingLeavesList = new ArrayList<>();
        }
        this.droopingLeavesList.add(droopingLeaves);
    }

    /**
     * 获取下垂叶子。
     *
     * @return 下垂叶子。
     */
    public List<DroopingLeaves> getDroopingLeaves() {
        return this.droopingLeavesList;
    }

    /**
     * 判断是否涂鸦。
     *
     * @return 涂鸦。
     */
    @Override
    public boolean isDoodle() {
        return super.isDoodle();
    }

    /**
     * 统计组件列表。
     *
     * @return 数量。
     */
    @Override
    public int numComponents() {
        return ((null != this.trunkList) ? this.trunkList.size() : 0) +
                ((null != this.branchList) ? this.branchList.size() : 0) +
                ((null != this.canopyList) ? this.canopyList.size() : 0) +
                ((null != this.rootList) ? this.rootList.size() : 0) +
                ((null != this.fruitList) ? this.fruitList.size() : 0) +
                ((null != this.holeList) ? this.holeList.size() : 0) +
                ((null != this.droopingLeavesList) ? this.droopingLeavesList.size() : 0);
    }
}
