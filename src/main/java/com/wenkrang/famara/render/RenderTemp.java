package com.wenkrang.famara.render;

import com.wenkrang.famara.render.lib.EntityShape;
import com.wenkrang.famara.render.lib.FaColor;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;

/**
 * 渲染临时数据类
 */
public class RenderTemp {
    public int x;
    public int y;
    public Vector direction;
    public double cos;
    /** 光线是否击中方块或实体 */
    public boolean hit;
    /** 击中方块的坐标 */
    public int hitX;
    public int hitY;
    public int hitZ;
    /** 击中方块的类型 */
    public Material hitType;
    /** 击中的 CE 家具或 CE 物品形状 */
    public EntityShape hitEntity;
    /** 击中的方块面或实体盒面 */
    public BlockFace hitFace;
    public FaColor color;
    public ArrayList<String> failedBlocks;
    public boolean isSkipped = false;

    public RenderTemp() {
        direction = new Vector();
        failedBlocks = new ArrayList<>();
        color = new FaColor(0, 0, 0);
    }
}
