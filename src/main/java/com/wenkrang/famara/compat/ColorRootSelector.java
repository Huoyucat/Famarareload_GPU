package com.wenkrang.famara.compat;

import com.wenkrang.famara.render.asset.ResourceKey;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** 不展开不存在的候选路径；本代相等ItemModel共享，不限制根的递归依赖。 */
final class ColorRootSelector implements Predicate<String>,AutoCloseable {
    private Map<String,CeCompat.ItemModel> models=new HashMap<>();
    private Iterator<Map.Entry<String,CeCompat.ItemModel>> inputs;
    private Map<CeCompat.ItemModel,CeCompat.ItemModel> shared=new HashMap<>();
    private final Set<String> resources=new HashSet<>();
    private IdentityHashMap<Object,Boolean> rawSeen=new IdentityHashMap<>(),viewSeen=new IdentityHashMap<>();
    private final ColorWorkBudget work;
    private boolean done,closed;
    ColorRootSelector(Map<String,CeCompat.ItemModel> input,ColorWorkBudget work){this.work=work;inputs=input.entrySet().iterator();}
    boolean step(int operations,long deadline,LongSupplier clock,BooleanSupplier current) {
        if(closed || operations<=0)throw new IllegalStateException("旧色根选择器已关闭或步长无效");if(done)return true;
        for(int n=0;n<operations && inputs.hasNext() && clock.getAsLong()<deadline;n++) {
            if(!current.getAsBoolean())throw new CancellationException("旧色物品映射已过期");
            var entry=inputs.next();String id=entry.getKey();var model=Objects.requireNonNull(entry.getValue());
            CeCompat.ItemModel canonical=shared.get(model);
            if(canonical==null){work.retain("model-sharing",128);shared.put(model,model);canonical=model;}
            estimate(viewSeen,"item-model-view",id,canonical);estimate(rawSeen,"original-models",id,model);models.put(id,canonical);
            String normalized=ResourceKey.parse(model.resource()).toString();
            if(!resources.contains(normalized)){work.retain("root-index",128L+normalized.length()*4L);resources.add(normalized);}
        }
        if(!inputs.hasNext() && !done){done=true;inputs=null;rawSeen=null;viewSeen=null;shared=null;models=Collections.unmodifiableMap(models);work.drop("model-sharing");}
        return done;
    }
    private void estimate(IdentityHashMap<Object,Boolean> seen,String domain,String id,CeCompat.ItemModel model) {
        work.retain(domain,domain.equals("original-models")?128:256);object(seen,domain,id,64L+id.length()*4L);object(seen,domain,model,64);String resource=model.resource();object(seen,domain,resource,64L+resource.length()*4L);
        var state=Objects.requireNonNull(model.state());
        if(object(seen,domain,state,128)) {
            list(seen,domain,state.flags());list(seen,domain,state.floats());
            if(list(seen,domain,state.strings()))for(String value:state.strings())object(seen,domain,value,64L+value.length()*4L);
            object(seen,domain,state.displayContext(),64L+state.displayContext().length()*4L);
        }
    }
    private boolean object(IdentityHashMap<Object,Boolean> seen,String domain,Object value,long bytes) {
        if(seen.containsKey(value) || domain.equals("original-models") && viewSeen.containsKey(value))return false;work.retain(domain,bytes);work.retain("model-sharing",96);seen.put(value,Boolean.TRUE);return true;
    }
    private boolean list(IdentityHashMap<Object,Boolean> seen,String domain,List<?> values){return object(seen,domain,values,64L+values.size()*32L);}
    Map<String,CeCompat.ItemModel> models(){if(!done || closed)throw new IllegalStateException("旧色物品映射未封存");return models;}
    /** 调用者替换原输入的最后持有者后才退还原始图费用。 */
    void releaseInput(){if(!done)throw new IllegalStateException("旧色物品映射未封存");work.drop("original-models");}
    int uniqueResources(){return resources.size();}
    public boolean test(String path) {
        if(closed || !done)throw new IllegalStateException("旧色根选择器尚未就绪或已关闭");
        if(!path.startsWith("assets/") || !path.endsWith(".json"))return false;
        int slash=path.indexOf('/',7);if(slash<0)return false;String ns=path.substring(7,slash),body=path.substring(slash+1,path.length()-5);
        if(body.startsWith("blockstates/"))return true;
        if(body.startsWith("items/"))return contains(ns,body.substring(6));
        if(body.startsWith("models/item/")){String item=body.substring(12);return contains(ns,item) || resources.contains(ns+":item/"+item);}
        return false;
    }
    private boolean contains(String namespace,String path){String key=namespace+":"+path;return models.containsKey(key) || namespace.equals("minecraft") && models.containsKey(path) || resources.contains(key) || resources.contains(namespace+":item/"+path);}
    public void close(){if(closed)return;closed=true;resources.clear();models=Map.of();inputs=null;rawSeen=null;viewSeen=null;shared=null;work.drop("root-index");work.drop("model-sharing");}
}
