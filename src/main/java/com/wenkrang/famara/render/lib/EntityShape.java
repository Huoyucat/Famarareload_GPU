package com.wenkrang.famara.render.lib;

import java.util.List;

/**
 * CE 家具或物品的拍照形状，不保存实时实体。
 * @param boxes 世界坐标盒，每个盒为 [minX, minY, minZ, maxX, maxY, maxZ]
 * @param color 代表颜色；null 表示缺色并报告对应 CE id
 * @param id CE 自定义内容标识符
 */
public record EntityShape(List<double[]> boxes, FaColor color, String id) {
    public EntityShape {
        boxes = boxes.stream().map(double[]::clone).toList();
        color = color == null ? null : new FaColor(color.getR(), color.getG(), color.getB());
    }
}
