package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.CanonicalBlockState;
import java.util.*;

/** CE逻辑表示与真实Bukkit快照表示同时冻结；不依据原生槽位编号猜测模型。 */
final class CeVisualStateAliases {
    private CeVisualStateAliases() {}
    static List<CeCompat.VisualState> freeze(String logicalState,String snapshotState,String material,String id,String vanilla,String visual) {
        CanonicalBlockState logical=CanonicalBlockState.parse(logicalState),snapshot=CanonicalBlockState.parse(snapshotState);
        var actual=new CeCompat.VisualState(snapshot.toString(),material,id,vanilla,visual);
        if(logical.equals(snapshot))return List.of(actual);
        return List.of(actual,new CeCompat.VisualState(logical.toString(),material,id,vanilla,visual));
    }
}
