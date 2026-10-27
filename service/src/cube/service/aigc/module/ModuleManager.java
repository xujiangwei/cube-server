/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.service.aigc.module;

import cube.aigc.Module;

import java.util.ArrayList;
import java.util.List;

/**
 * 模组管理器。
 */
public class ModuleManager {

    private final static ModuleManager instance = new ModuleManager();

    private List<Module> moduleList;

    /**
     * 获取单例。
     *
     * @return 全局唯一实例。
     */
    public final static ModuleManager getInstance() {
        return ModuleManager.instance;
    }

    private ModuleManager() {
        this.moduleList = new ArrayList<>();
    }

    /**
     * 依次启动全部已注册模块。
     *
     * <p>单个模块启动失败不影响其余模块。</p>
     */
    public void start() {
        for (Module module : this.moduleList) {
            module.start();
        }
    }

    /**
     * 依次停止全部已注册模块。
     *
     * <p>单个模块停止失败不影响其余模块。</p>
     */
    public void stop() {
        for (Module module : this.moduleList) {
            module.stop();
        }
    }

    /**
     * 获取已注册模块列表。
     *
     * @return 模块列表；无模块时为空列表。
     */
    public List<Module> getModuleList() {
        return this.moduleList;
    }

    /**
     * 注册模块。
     *
     * <p>同一实例重复注册会被忽略。</p>
     *
     * @param module 待注册模块。
     */
    public void addModule(Module module) {
        this.moduleList.add(module);
    }

    /**
     * 移除模块。
     *
     * @param module 待移除模块。
     */
    public void removeModule(Module module) {
        this.moduleList.remove(module);
    }

    /**
     * 按名称查找模块。
     *
     * @param moduleName 模块名称。
     * @return 匹配的模块；不存在返回 {@code null} 。
     */
    public Module getModule(String moduleName) {
        for (Module module : this.moduleList) {
            if (module.getName().equals(moduleName)) {
                return module;
            }
        }
        return null;
    }

//    public Module matchModule(List<String> words) {
//        Module module = null;
//        for (Module mod : this.moduleList) {
//            List<String> matchingWords = mod.getMatchingWords();
//            for (String word : words) {
//                if (matchingWords.contains(word)) {
//                    module = mod;
//                    break;
//                }
//            }
//
//            if (null != module) {
//                break;
//            }
//        }
//        return module;
//    }
}
