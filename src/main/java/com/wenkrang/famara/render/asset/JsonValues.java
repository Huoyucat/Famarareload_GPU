package com.wenkrang.famara.render.asset;

import com.google.gson.*;

final class JsonValues {
    static int integer(JsonElement e) {
        return e.getAsBigDecimal().intValueExact();
    }

    static double number(JsonElement e) {
        double n = e.getAsDouble();
        if (!Double.isFinite(n)) throw new IllegalArgumentException("数值必须有限");
        return n;
    }

    static double[] vector(JsonElement e, int size) {
        JsonArray array = e.getAsJsonArray();
        if (array.size() != size) throw new IllegalArgumentException("坐标维数不合法");
        double[] result = new double[size];
        for (int i = 0; i < size; i++) result[i] = number(array.get(i));
        return result;
    }
}
