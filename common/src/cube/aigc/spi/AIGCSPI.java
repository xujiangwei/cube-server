/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.aigc.spi;

/**
 * AIGC 业务模块 SPI 全局常量。
 *
 * <p>SPI 一旦发布到 {@code deploy/libs/} 即成为业务模块的二进制接口，
 * 任何签名变更都必须递增 {@link #VERSION}。</p>
 */
public final class AIGCSPI {

    /**
     * SPI 契约版本。
     *
     * <p>模块各自编译期固化此值；运行期由
     * {@link ModuleDescriptor#spiVersion} 经 {@link #isCompatible(int)} 校验，
     * 不支持的版本拒绝加载。</p>
     *
     * <p><b>v26</b> 仅新增默认方法，显式兼容 v25 的已有插件；未来版本不能自动视为兼容。
     * v26 森林插件需在 v26 宿主上运行。</p>
     *
     * <p><b>⚠️ 改本常量必须重编所有插件</b>：{@code final static int} 会被 javac
     * <b>内联</b>进每个引用它的插件字节码。内联是必需的——若改为普通字段或方法，
     * 旧插件在运行期读到的是宿主的当前值，"兼容性校验"将恒真而失效。
     * 代价是未重编的插件会永久带着旧版本号，宿主只在启动时回一行
     * {@code requires SPI version N ... rejected}，模块整体不装载。
     * 因此两个插件的构建脚本都挂了 {@code check-spi} 目标（见
     * {@code cube.test.SpiVersionCheck}）：产物内联值与源码不一致即构建失败。
     * 修改本常量后依次重建 common → dispatcher → 各插件，不要只重建 common。</p>
     *
     * <p><b>宿主服务能力</b>：{@link AIGCHost#getContact}、
     * {@link AIGCHost#getMembership}、{@link AIGCHost#getRemainingUsages}、
     * {@link AIGCHost#allowPredictPainting} 供模块读取联系人档案、会员资格、
     * 剩余用量与报告可见权限。这四项的宿主实现都是单例服务（联系人服务、
     * 会员中心），模块无法自行访问；其中会员系统本身位于宿主，故 SPI 只暴露
     * 「按域名 + 联系人 ID 取资格」这一结果，不把会员系统作为返回类型。</p>
     *
     * <p>{@link AIGCHost#downloadFile}：按外部链接下载文件并登记为文件标签。
     * 供「模板文章」等动作使用——调用方只拿到第三方给出的图片地址，
     * 下载动作由文件存储服务完成，模块不直接发起网络请求。</p>
     *
     * <p>{@link AIGCHost#matchSimilarity}：以模板素材做图像相似度匹配。
     * 绘画解读需要在物体检测之外补充素材判断（如雨中人绘画里的伞），
     * 检索由计算机视觉服务承担，模块不持有其模型；
     * 其回调监听器 {@code MatchSimilarityListener} 亦定义在
     * <code>common</code>，回调参数（文件标签、素材列表、状态码）不依赖宿主。</p>
     *
     * <p><b>v25</b>：{@link AIGCHost#performSpeakerDiarization} 增加
     * {@code sentiment} 参数——内容语料的正负面 / 中性指标分析由「无条件执行」
     * 改为「按需开启」（每段一次文本生成调用，成本较高）；
     * 新增 {@link AIGCHost#registerSpeechListener} /
     * {@link AIGCHost#unregisterSpeechListener}——说话人分离是平台级能力，
     * 分离结果上的角色映射等收尾操作经模块级监听器
     * {@code SpeechModuleListener} 由各业务模块自行订阅，宿主不再内置任何
     * 业务角色分类。</p>
     */
    public final static int VERSION = 26;

    private AIGCSPI() {
    }

    /**
     * 判断模块声明的 SPI 版本是否受当前宿主支持。
     *
     * @param spiVersion 模块声明的 SPI 版本。
     * @return 当前支持 v25 或 v26 时返回 <code>true</code>。
     */
    public static boolean isCompatible(int spiVersion) {
        return VERSION == spiVersion || spiVersion == 25;
    }
}
