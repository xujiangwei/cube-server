/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.psychology.material;

import cube.aigc.psychology.material.person.*;
import cube.util.Gender;
import cube.vision.BoundingBox;
import cube.vision.Box;
import cube.vision.Point;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 人。
 */
public class Person extends Thing {

    private boolean sideFace = false;

    /** 性别。 */
    protected Gender gender = Gender.Unknown;

    private Head head;

    private List<Braid> braidList;

    private List<Hair> hairList;

    private Cap cap;

    private List<Eye> eyeList;

    private List<Eyebrow> eyebrowList;

    private Nose nose;

    private List<Ear> earList;

    private Mouth mouth;

    private Body body;

    private List<Arm> armList;

    private List<Palm> palmList;

    private List<Leg> legList;

    private List<Foot> footList;

    private Skirt skirt;

    private Mask mask;

    private List<HairAccessory> hairAccessoryList;

    private List<Item> itemList;

    private Eyeglass eyeglass;

    public Person(JSONObject json) {
        super(json);
    }

    public Person(BoundingBox boundingBox, Box box) {
        super(Label.Person.name, boundingBox, box);
        this.backwardReasoning = true;
    }

    /**
     * 获取性别。
     *
     * @return 性别。
     */
    public Gender getGender() {
        return this.gender;
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
        this.area = Math.round(this.boundingBox.calculateArea() * 0.76f);
    }

    /** 刷新面积。 */
    public void refreshArea() {
        this.area = Math.round(this.boundingBox.calculateArea() * 0.76f);
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
            case PersonBraid:
                if (null != this.braidList) {
                    things = new ArrayList<>(this.braidList);
                }
                break;
            case PersonHead:
                if (null != this.head) {
                    things = new ArrayList<>();
                    things.add(this.head);
                }
                break;
            case PersonHair:
                if (null != this.hairList) {
                    things = new ArrayList<>(this.hairList);
                }
                break;
            case PersonCap:
                if (null != this.cap) {
                    things = new ArrayList<>();
                    things.add(this.cap);
                }
                break;
            case PersonEye:
                if (null != this.eyeList) {
                    things = new ArrayList<>(this.eyeList);
                }
                break;
            case PersonEyebrow:
                if (null != this.eyebrowList) {
                    things = new ArrayList<>(this.eyebrowList);
                }
                break;
            case PersonNose:
                if (null != this.nose) {
                    things = new ArrayList<>();
                    things.add(this.nose);
                }
                break;
            case PersonEar:
                if (null != this.earList) {
                    things = new ArrayList<>(this.earList);
                }
                break;
            case PersonMouth:
                if (null != this.mouth) {
                    things = new ArrayList<>();
                    things.add(this.mouth);
                }
                break;
            case PersonBody:
                if (null != this.body) {
                    things = new ArrayList<>();
                    things.add(this.body);
                }
                break;
            case PersonArm:
                if (null != this.armList) {
                    things = new ArrayList<>(this.armList);
                }
                break;
            case PersonPalm:
                if (null != this.palmList) {
                    things = new ArrayList<>(this.palmList);
                }
                break;
            case PersonLeg:
                if (null != this.legList) {
                    things = new ArrayList<>(this.legList);
                }
                break;
            case PersonFoot:
                if (null != this.footList) {
                    things = new ArrayList<>(this.footList);
                }
                break;
            case PersonMask:
                if (null != this.mask) {
                    things = new ArrayList<>();
                    things.add(this.mask);
                }
                break;
            case PersonHairAccessories:
                if (null != this.hairAccessoryList) {
                    things = new ArrayList<>(this.hairAccessoryList);
                }
                break;
            case PersonSkirt:
                if (null != this.skirt) {
                    things = new ArrayList<>();
                    things.add(this.skirt);
                }
                break;
            case PersonItem:
                if (null != this.itemList) {
                    things = new ArrayList<>(this.itemList);
                }
                break;
            case PersonGlasses:
                if (null != this.eyeglass) {
                    things = new ArrayList<>();
                    things.add(this.eyeglass);
                }
                break;
            default:
                break;
        }
        return things;
    }

    /**
     * 设置头。
     *
     * @param head 头。
     */
    public void setHead(Head head) {
        this.head = head;
    }

    /**
     * 获取头。
     *
     * @return 头。
     */
    public Head getHead() {
        return this.head;
    }

    /**
     * 判断是否头。
     *
     * @return 头。
     */
    public boolean hasHead() {
        return (null != this.head);
    }

    /**
     * 获取头高比例。
     *
     * @return 头高比例。
     */
    public double getHeadHeightRatio() {
        if (null == this.head) {
            return 0;
        }

        return ((double) this.head.getHeight() / (double) this.getHeight());
    }

    /**
     * 设置侧脸。
     *
     * @param head 侧脸。
     */
    public void setSideFace(Head head) {
        this.sideFace = true;
        this.head = head;
    }

    /**
     * 返回脸部元素数量。
     * @return 计算结果。
     */
    public int numFaceComponents() {
        int num = 0;
        if (null != this.eyeList) {
            num += this.eyeList.size();
        }
        if (null != this.nose) {
            num += 1;
        }
        if (null != this.mouth) {
            num += 1;
        }
        if (null != this.earList) {
            num += this.earList.size();
        }
        if (null != this.hairList) {
            num += this.hairList.size();
        }
        return num;
    }

    /**
     * 添加辫子。
     *
     * @param braid 辫子。
     */
    public void addBraid(Braid braid) {
        if (null == this.braidList) {
            this.braidList = new ArrayList<>();
        }
        this.braidList.add(braid);

        this.gender = Gender.Female;
    }

    /**
     * 获取辫子。
     *
     * @return 辫子。
     */
    public List<Braid> getBraids() {
        return this.braidList;
    }

    /**
     * 添加头发。
     *
     * @param hair 头发。
     */
    public void addHair(Hair hair) {
        if (null == this.hairList) {
            this.hairList = new ArrayList<>();
        }
        this.hairList.add(hair);
    }

    /**
     * 获取头发。
     *
     * @return 头发。
     */
    public List<Hair> getHairs() {
        return this.hairList;
    }

    /**
     * 判断是否头发。
     *
     * @return 头发。
     */
    public boolean hasHair() {
        return (null != this.hairList);
    }

    /**
     * 判断是否直发。
     *
     * @return 直发。
     */
    public boolean hasStraightHair() {
        if (null == this.hairList) {
            return false;
        }

        for (Hair hair : this.hairList) {
            if (hair instanceof StraightHair) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否短发。
     *
     * @return 短发。
     */
    public boolean hasShortHair() {
        if (null == this.hairList) {
            return false;
        }

        for (Hair hair : this.hairList) {
            if (hair instanceof ShortHair) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否卷发。
     *
     * @return 卷发。
     */
    public boolean hasCurlyHair() {
        if (null == this.hairList) {
            return false;
        }

        for (Hair hair : this.hairList) {
            if (hair instanceof CurlyHair) {
                return true;
            }
        }

        return false;
    }

    /**
     * 判断是否竖发。
     *
     * @return 竖发。
     */
    public boolean hasStandingHair() {
        if (null == this.hairList) {
            return false;
        }

        for (Hair hair : this.hairList) {
            if (hair instanceof StandingHair) {
                return true;
            }
        }

        return false;
    }

    /**
     * 设置帽子。
     *
     * @param cap 帽子。
     */
    public void setCap(Cap cap) {
        this.cap = cap;
    }

    /**
     * 获取帽子。
     *
     * @return 帽子。
     */
    public Cap getCap() {
        return this.cap;
    }

    /**
     * 判断是否帽子。
     *
     * @return 帽子。
     */
    public boolean hasCap() {
        return (null != this.cap);
    }

    /**
     * 添加眼睛。
     *
     * @param eye 眼睛。
     */
    public void addEye(Eye eye) {
        if (null == this.eyeList) {
            this.eyeList = new ArrayList<>();
        }
        this.eyeList.add(eye);
    }

    /**
     * 获取眼睛。
     *
     * @return 眼睛。
     */
    public List<Eye> getEyes() {
        return this.eyeList;
    }

    /**
     * 判断是否眼睛。
     *
     * @return 眼睛。
     */
    public boolean hasEye() {
        return (null != this.eyeList);
    }

    /**
     * 判断是否打开眼睛。
     *
     * @return 打开眼睛。
     */
    public boolean hasOpenEye() {
        if (null == this.eyeList) {
            return false;
        }

        for (Eye eye : this.eyeList) {
            if (eye.isOpen()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取最大眼睛面积比例。
     *
     * @return 最大眼睛面积比例。
     */
    public double getMaxEyeAreaRatio() {
        if (null == this.eyeList || null == this.head) {
            return 0;
        }

        Eye eye = this.getMaxAreaThing(this.eyeList);
        if (null == eye) {
            return 0;
        }
        return ((double) eye.area)
                / ((double) this.head.area);
    }

    /**
     * 添加眉毛。
     *
     * @param eyebrow 眉毛。
     */
    public void addEyebrow(Eyebrow eyebrow) {
        if (null == this.eyebrowList) {
            this.eyebrowList = new ArrayList<>();
        }
        this.eyebrowList.add(eyebrow);
    }

    /**
     * 获取眉毛。
     *
     * @return 眉毛。
     */
    public List<Eyebrow> getEyebrows() {
        return this.eyebrowList;
    }

    /**
     * 判断是否眉毛。
     *
     * @return 眉毛。
     */
    public boolean hasEyebrow() {
        return (null != this.eyebrowList);
    }

    /**
     * 设置鼻子。
     *
     * @param nose 鼻子。
     */
    public void setNose(Nose nose) {
        this.nose = nose;
    }

    /**
     * 获取鼻子。
     *
     * @return 鼻子。
     */
    public Nose getNose() {
        return this.nose;
    }

    /**
     * 判断是否鼻子。
     *
     * @return 鼻子。
     */
    public boolean hasNose() {
        return (null != this.nose);
    }

    /**
     * 添加耳朵。
     *
     * @param ear 耳朵。
     */
    public void addEar(Ear ear) {
        if (null == this.earList) {
            this.earList = new ArrayList<>();
        }
        this.earList.add(ear);
    }

    /**
     * 获取耳朵。
     *
     * @return 耳朵。
     */
    public List<Ear> getEars() {
        return this.earList;
    }

    /**
     * 判断是否耳朵。
     *
     * @return 耳朵。
     */
    public boolean hasEar() {
        return (null != this.earList);
    }

    /**
     * 设置嘴。
     *
     * @param mouth 嘴。
     */
    public void setMouth(Mouth mouth) {
        this.mouth = mouth;
    }

    /**
     * 获取嘴。
     *
     * @return 嘴。
     */
    public Mouth getMouth() {
        return this.mouth;
    }

    /**
     * 判断是否嘴。
     *
     * @return 嘴。
     */
    public boolean hasMouth() {
        return (null != this.mouth);
    }

    /**
     * 设置身体。
     *
     * @param body 身体。
     */
    public void setBody(Body body) {
        this.body = body;
    }

    /**
     * 获取身体。
     *
     * @return 身体。
     */
    public Body getBody() {
        return this.body;
    }

    /**
     * 判断是否身体。
     *
     * @return 身体。
     */
    public boolean hasBody() {
        return (null != this.body);
    }

    /**
     * 添加手臂。
     *
     * @param arm 手臂。
     */
    public void addArm(Arm arm) {
        if (null == this.armList) {
            this.armList = new ArrayList<>();
        }
        this.armList.add(arm);
    }

    /**
     * 获取手臂。
     *
     * @return 手臂。
     */
    public List<Arm> getArms() {
        return this.armList;
    }

    /**
     * 判断是否手臂。
     *
     * @return 手臂。
     */
    public boolean hasArm() {
        return (null != this.armList);
    }

    /**
     * 判断是否两手臂。
     *
     * @return 两手臂。
     */
    public boolean hasTwoArms() {
        return (null != this.armList && this.armList.size() >= 2);
    }

    /**
     * 计算手臂间距。
     *
     * @return 手臂间距。
     */
    public double calcArmsDistance() {
        if (!this.hasTwoArms()) {
            return 0;
        }

        Point c1 = this.armList.get(0).box.getCenterPoint();
        Point c2 = this.armList.get(1).box.getCenterPoint();
        return Math.abs(c1.x - c2.x);
    }

    /**
     * 添加手掌。
     *
     * @param palm 手掌。
     */
    public void addPalm(Palm palm) {
        if (null == this.palmList) {
            this.palmList = new ArrayList<>();
        }
        this.palmList.add(palm);
    }

    /**
     * 获取手掌。
     *
     * @return 手掌。
     */
    public List<Palm> getPalms() {
        return this.palmList;
    }

    /**
     * 判断是否手掌。
     *
     * @return 手掌。
     */
    public boolean hasPalm() {
        return (null != palmList);
    }

    /**
     * 添加腿。
     *
     * @param leg 腿。
     */
    public void addLeg(Leg leg) {
        if (null == this.legList) {
            this.legList = new ArrayList<>();
        }
        this.legList.add(leg);
    }

    /**
     * 获取腿。
     *
     * @return 腿。
     */
    public List<Leg> getLegs() {
        return this.legList;
    }

    /**
     * 判断是否腿。
     *
     * @return 腿。
     */
    public boolean hasLeg() {
        return (null != this.legList);
    }

    /**
     * 判断是否两腿。
     *
     * @return 两腿。
     */
    public boolean hasTwoLegs() {
        return (null != this.legList && this.legList.size() >= 2);
    }

    /**
     * 计算腿部间距。
     *
     * @return 腿部间距。
     */
    public double calcLegsDistance() {
        if (!this.hasTwoLegs()) {
            return 0;
        }

        Point c1 = this.legList.get(0).box.getCenterPoint();
        Point c2 = this.legList.get(1).box.getCenterPoint();
        return Math.abs(c1.x - c2.x);
    }

    /**
     * 获取最细腿。
     *
     * @return 最细腿。
     */
    public Leg getThinnestLeg() {
        if (null == this.legList) {
            return null;
        }

        Leg leg = null;
        int w = Integer.MAX_VALUE;
        for (Leg cur : this.legList) {
            if ((int) cur.getWidth() < w) {
                w = (int) cur.getWidth();
                leg = cur;
            }
        }
        return leg;
    }

    /**
     * 添加脚。
     *
     * @param foot 脚。
     */
    public void addFoot(Foot foot) {
        if (null == this.footList) {
            this.footList = new ArrayList<>();
        }
        this.footList.add(foot);
    }

    /**
     * 获取脚。
     *
     * @return 脚。
     */
    public List<Foot> getFoot() {
        return this.footList;
    }

    /**
     * 判断是否脚。
     *
     * @return 脚。
     */
    public boolean hasFoot() {
        return (null != this.footList);
    }

    /**
     * 设置裙子。
     *
     * @param skirt 裙子。
     */
    public void setSkirt(Skirt skirt) {
        this.skirt = skirt;
        if (null != this.skirt) {
            this.gender = Gender.Female;
        }
    }

    /**
     * 获取裙子。
     *
     * @return 裙子。
     */
    public Skirt getSkirt() {
        return this.skirt;
    }

    /**
     * 设置掩码。
     *
     * @param mask 掩码。
     */
    public void setMask(Mask mask) {
        this.mask = mask;
    }

    /**
     * 获取掩码。
     *
     * @return 掩码。
     */
    public Mask getMask() {
        return this.mask;
    }

    /**
     * 添加发饰。
     *
     * @param hairAccessory 发饰。
     */
    public void addHairAccessory(HairAccessory hairAccessory) {
        if (null == this.hairAccessoryList) {
            this.hairAccessoryList = new ArrayList<>();
        }
        this.hairAccessoryList.add(hairAccessory);
    }

    /**
     * 获取全部发饰。
     *
     * @return 发饰列表；无发饰时为空列表。
     */
    public List<HairAccessory> getHairAccessories() {
        return this.hairAccessoryList;
    }

    /**
     * 判断是否佩戴发饰。
     *
     * @return 佩戴返回 {@code true} 。
     */
    public boolean hasHairAccessory() {
        return (null != this.hairAccessoryList);
    }

    /**
     * 添加条目。
     *
     * @param item 条目。
     */
    public void addItem(Item item) {
        if (null == this.itemList) {
            this.itemList = new ArrayList<>();
        }
        this.itemList.add(item);
    }

    /**
     * 获取条目列表。
     *
     * @return 条目列表。
     */
    public List<Item> getItems() {
        return this.itemList;
    }

    /**
     * 设置眼镜。
     *
     * @param eyeglass 眼镜。
     */
    public void setGlasses(Eyeglass eyeglass) {
        this.eyeglass = eyeglass;
    }

    /**
     * 获取眼镜。
     *
     * @return 眼镜。
     */
    public Eyeglass getGlasses() {
        return this.eyeglass;
    }

    /**
     * 统计组件列表。
     *
     * @return 数量。
     */
    @Override
    public int numComponents() {
        int num = 0;
        if (null != this.head) {
            num += 1;
        }
        if (null != braidList) {
            num += braidList.size();
        }
        if (null != hairList) {
            num += 1;
        }
        if (null != cap) {
            num += 1;
        }
        if (null != eyeList) {
            num += eyeList.size();
        }
        if (null != eyebrowList) {
            num += eyebrowList.size();
        }
        if (null != nose) {
            num += 1;
        }
        if (null != earList) {
            num += earList.size();
        }
        if (null != mouth) {
            num += 1;
        }
        if (null != body) {
            num += 1;
        }
        if (null != armList) {
            num += armList.size();
        }
        if (null != palmList) {
            num += palmList.size();
        }
        if (null != legList) {
            num += legList.size();
        }
        if (null != footList) {
            num += footList.size();
        }
        if (null != skirt) {
            num += 1;
        }
        if (null != mask) {
            num += 1;
        }
        if (null != hairAccessoryList) {
            num += 1;
        }
        if (null != itemList) {
            num += 1;
        }
        if (null != eyeglass) {
            num += 1;
        }
        return num;
    }
}
