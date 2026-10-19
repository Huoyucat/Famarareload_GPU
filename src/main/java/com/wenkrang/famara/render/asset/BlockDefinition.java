package com.wenkrang.famara.render.asset;

import com.google.gson.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;

/**
 * 编译后的状态选择器。模型错误保留到具体选中状态，不能静默丢掉一个 multipart。
 */
public final class BlockDefinition {
    private record Choice(BakedModel model, AssetException error, int weight) {
    }

    private record Rule(Predicate<CanonicalBlockState> condition, int specificity, List<Choice> choices) {
    }

    record Layer(BlockDefinition definition,ResourcePack.Role role) {}
    private final List<Layer> layers;
    private final List<Rule> variants;
    private final List<Rule> multipart;
    private final String path;
    private final List<AssetException> issues;
    private final Function<CanonicalBlockState,List<BakedModel>> builtin;
    private final List<BakedModel> nativeModels;

    private BlockDefinition(List<Rule> variants, List<Rule> multipart, String path, List<AssetException> issues) {
        this(variants,multipart,path,issues,null,List.of());
    }
    private BlockDefinition(List<Rule> variants,List<Rule> multipart,String path,List<AssetException> issues,
                            Function<CanonicalBlockState,List<BakedModel>> builtin,List<BakedModel> nativeModels) {
        this(variants,multipart,path,issues,builtin,nativeModels,List.of());
    }
    private BlockDefinition(List<Rule> variants,List<Rule> multipart,String path,List<AssetException> issues,
                            Function<CanonicalBlockState,List<BakedModel>> builtin,List<BakedModel> nativeModels,List<Layer> layers) {
        this.layers=List.copyOf(layers);this.builtin=builtin;this.nativeModels=List.copyOf(nativeModels);
        this.variants = List.copyOf(variants);
        this.multipart = List.copyOf(multipart);
        this.path = path;
        this.issues = List.copyOf(issues);
    }

    static BlockDefinition layered(List<Layer> source,String path) {
        List<AssetException> issues=source.stream().flatMap(layer->layer.definition.issues.stream()).toList();
        return new BlockDefinition(List.of(),List.of(),path,issues,null,List.of(),source);
    }
    boolean covers(CanonicalBlockState state) {
        if(!layers.isEmpty())return layers.stream().anyMatch(layer->layer.definition.covers(state));
        return builtin!=null || !multipart.isEmpty() || variants.stream().anyMatch(rule->rule.condition.test(state));
    }
    ResourcePack.Role roleFor(CanonicalBlockState state) {
        for(Layer layer:layers)if(layer.definition.covers(state))return layer.role;
        return ResourcePack.Role.UNKNOWN;
    }
    private BlockDefinition matchingLayer(CanonicalBlockState state) {
        for(Layer layer:layers)if(layer.definition.covers(state))return layer.definition;
        throw new AssetException(AssetException.Code.MISSING,path,"没有匹配的状态模型："+state);
    }
    public List<AssetException> issues() {
        return issues;
    }

    static BlockDefinition builtin(String name,Function<CanonicalBlockState,List<BakedModel>> resolver) {
        return builtin(name,resolver,List.of());
    }

    static BlockDefinition builtin(String name,Function<CanonicalBlockState,List<BakedModel>> resolver,List<BakedModel> models) {
        return new BlockDefinition(List.of(),List.of(),name,List.of(),resolver,models);
    }
    List<BakedModel> possibleModels() {
        if(!layers.isEmpty())return layers.stream().flatMap(layer->layer.definition.possibleModels().stream()).distinct().toList();
        if(builtin!=null)return nativeModels;
        return java.util.stream.Stream.concat(variants.stream(),multipart.stream()).flatMap(rule->rule.choices.stream())
                .filter(choice->choice.error==null).map(Choice::model).distinct().toList();
    }
    /** 只在每个可能随机候选都能保证整格遮挡时用于整状态的内部块省略。 */
    boolean guaranteedOpaque(CanonicalBlockState state,java.util.function.Predicate<List<BakedModel>> opaque) {
        if(!layers.isEmpty())return matchingLayer(state).guaranteedOpaque(state,opaque);
        if(builtin!=null)return false;
        Rule best=null;
        for(Rule rule:variants)if(rule.condition.test(state)) {
            if(best==null || rule.specificity>best.specificity)best=rule;
            else if(rule.specificity==best.specificity)return false;
        }
        if(best!=null && best.choices.stream().allMatch(c->c.error==null && opaque.test(List.of(c.model))))return true;
        for(Rule rule:multipart)if(rule.condition.test(state) && rule.choices.stream().allMatch(c->c.error==null && opaque.test(List.of(c.model))))return true;
        return false;
    }
    boolean hasUsableModel() {
        if(!layers.isEmpty())return layers.stream().anyMatch(layer->layer.definition.hasUsableModel());
        if(builtin!=null)return true;
        return java.util.stream.Stream.concat(variants.stream(), multipart.stream())
                .flatMap(r -> r.choices.stream()).anyMatch(c -> c.error == null);
    }

    public List<BakedModel> select(CanonicalBlockState state, int x, int y, int z, long seed) {
        if(!layers.isEmpty())return matchingLayer(state).select(state,x,y,z,seed);
        if(builtin!=null)return builtin.apply(state);
        List<BakedModel> selected = new ArrayList<>();
        Rule best = null;
        for (Rule rule : variants)
            if (rule.condition.test(state)) {
                if (best == null || rule.specificity > best.specificity) best = rule;
                else if (rule.specificity == best.specificity)
                    throw new AssetException(AssetException.Code.INVALID, path, "存在同等优先级状态规则：" + state);
            }
        long random = mix(seed ^ ((long) x * 3129871) ^ ((long) z * 116129781) ^ y);
        if (best != null) selected.add(choose(best.choices, random));
        for (int i = 0; i < multipart.size(); i++) {
            Rule rule = multipart.get(i);
            if (rule.condition.test(state)) selected.add(choose(rule.choices, mix(random + i + 1)));
        }
        if (best == null && multipart.isEmpty())
            throw new AssetException(AssetException.Code.MISSING, path, "没有匹配的状态模型：" + state);
        return List.copyOf(selected);
    }

    private static BakedModel choose(List<Choice> values, long random) {
        long total = values.stream().mapToLong(Choice::weight).sum();
        long target = Long.remainderUnsigned(random, total);
        for (Choice choice : values) {
            if (target < choice.weight) {
                if (choice.error != null) throw choice.error;
                return choice.model;
            }
            target -= choice.weight;
        }
        throw new AssertionError("权重选择失败");
    }

    private static long mix(long n) {
        n = (n ^ (n >>> 30)) * 0xbf58476d1ce4e5b9L;
        n = (n ^ (n >>> 27)) * 0x94d049bb133111ebL;
        return n ^ (n >>> 31);
    }

    static BlockDefinition compile(JsonObject root, String path, ModelCompiler compiler) {
        Texture.checkFields(root, Set.of("variants", "multipart"), path);
        if (root.has("variants") == root.has("multipart"))
            throw new AssetException(AssetException.Code.INVALID, path, "需要且只能指定variants或multipart");
        List<Rule> variants = new ArrayList<>(), multipart = new ArrayList<>();
        List<AssetException> issues = new ArrayList<>();
        if (root.has("variants")) {
            for (var entry : root.getAsJsonObject("variants").entrySet()) {
                String props = entry.getKey();
                SortedMap<String, String> expected = CanonicalBlockState.parse("minecraft:selector[" + props + "]").properties();
                Predicate<CanonicalBlockState> condition = s -> expected.entrySet().stream()
                        .allMatch(e -> e.getValue().equals(s.properties().get(e.getKey())));
                variants.add(new Rule(condition, expected.size(), choices(entry.getValue(), path, "variants["+props+"]", compiler, issues)));
            }
            if (variants.isEmpty()) throw new AssetException(AssetException.Code.INVALID, path, "variants不能为空");
        } else {
            for (JsonElement e : root.getAsJsonArray("multipart")) {
                JsonObject part = e.getAsJsonObject();
                Texture.checkFields(part, Set.of("when", "apply"), path);
                Predicate<CanonicalBlockState> condition = part.has("when") ? condition(part.getAsJsonObject("when"), path) : s -> true;
                multipart.add(new Rule(condition, 0, choices(part.get("apply"), path, "multipart["+multipart.size()+"]", compiler, issues)));
            }
        }
        return new BlockDefinition(variants, multipart, path, issues);
    }

    private static List<Choice> choices(JsonElement entry, String path, String rule, ModelCompiler compiler, List<AssetException> issues) {
        List<Choice> result = new ArrayList<>();
        List<JsonElement> entries = entry.isJsonArray() ? entry.getAsJsonArray().asList() : List.of(entry);
        if (entries.isEmpty()) throw new AssetException(AssetException.Code.INVALID, path, "模型候选不能为空");
        for (JsonElement e : entries) {
            JsonObject v = e.getAsJsonObject();
            Texture.checkFields(v, Set.of("model", "x", "y", "uvlock", "weight"), path);
            int x = v.has("x") ? JsonValues.integer(v.get("x")) : 0, y = v.has("y") ? JsonValues.integer(v.get("y")) : 0;
            int weight = v.has("weight") ? JsonValues.integer(v.get("weight")) : 1;
            if (x % 90 != 0 || y % 90 != 0 || weight <= 0)
                throw new AssetException(AssetException.Code.INVALID, path, "非法模型旋转或权重："+rule+"，model="+v.get("model")+"，x="+x+"，y="+y+"，weight="+weight+"（旋转须为90度整数倍，权重须为正整数）");
            x=Math.floorMod(x,360);y=Math.floorMod(y,360);
            boolean lock = v.has("uvlock") && v.get("uvlock").getAsBoolean();
            try {
                BakedModel model = compiler.variant(ResourceKey.parse(v.get("model").getAsString()), x, y, lock);
                result.add(new Choice(model, null, weight));
            } catch (AssetException error) {
                if (error.code() == AssetException.Code.LIMIT) throw error;
                issues.add(error);
                result.add(new Choice(null, error, weight));
            }
        }
        return List.copyOf(result);
    }

    private static Predicate<CanonicalBlockState> condition(JsonObject object, String path) {
        List<Predicate<CanonicalBlockState>> conditions = new ArrayList<>();
        for (var entry : object.entrySet()) {
            String key = entry.getKey();
            if (key.equals("OR") || key.equals("AND")) {
                List<Predicate<CanonicalBlockState>> group = new ArrayList<>();
                for (JsonElement e : entry.getValue().getAsJsonArray()) group.add(condition(e.getAsJsonObject(), path));
                if (group.isEmpty()) throw new AssetException(AssetException.Code.INVALID, path, "空条件组");
                conditions.add(key.equals("OR") ? s -> group.stream().anyMatch(p -> p.test(s)) : s -> group.stream().allMatch(p -> p.test(s)));
            } else {
                if (!key.matches("[a-z0-9_]+"))
                    throw new AssetException(AssetException.Code.UNSUPPORTED, path, "未知条件 " + key);
                String value = entry.getValue().getAsString();
                Set<String> alternatives = new HashSet<>(Arrays.asList(value.split("[|]", -1)));
                if (alternatives.contains(""))
                    throw new AssetException(AssetException.Code.INVALID, path, "空属性条件");
                conditions.add(s -> alternatives.contains(s.properties().get(key)));
            }
        }
        return s -> conditions.stream().allMatch(p -> p.test(s));
    }
}
