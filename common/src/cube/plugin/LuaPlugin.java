/*
 * This source file is part of Cube.
 *
 * Copyright (c) 2023-2025 Ambrose Xu.
 */

package cube.plugin;

import org.keplerproject.luajava.LuaState;
import org.keplerproject.luajava.LuaStateFactory;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 基于 Lua 实现的插件。
 */
public abstract class LuaPlugin implements Plugin {

    /**
     * Lua 脚本文件。
     */
    private File luaFile;

    private LuaState luaState;

    public LuaPlugin(String luaFilename) {
        this.luaFile = new File(luaFilename);
    }

    /**
     * 获取脚本文件。
     *
     * @return Lua 脚本文件。
     */
    public File getLuaFile() {
        return this.luaFile;
    }

    /**
     * 执行插件脚本。
     *
     * @param context 插件上下文。
     * @throws FileNotFoundException 脚本文件不存在。
     */
    public void call(PluginContext context) throws FileNotFoundException {
        if (!this.luaFile.exists()) {
            throw new FileNotFoundException("Not found file " + this.luaFile.getAbsolutePath());
        }

        this.prepare();

        // 查找参数
        this.luaState.getField(LuaState.LUA_GLOBALSINDEX, "launch");

        // 参数压栈
        this.luaState.pushJavaObject(context);

        // 调用 launch
        this.luaState.call(1, 0);
    }

    /** 释放插件占用的资源。 */
    public void close() {
        if (null != this.luaState) {
            this.luaState.close();
        }
    }

    private void prepare() {
        if (null != this.luaState) {
            return;
        }

        this.luaState = LuaStateFactory.newLuaState();
        this.luaState.openLibs();

        this.luaState.LdoFile(this.luaFile.getAbsolutePath());
    }
}
