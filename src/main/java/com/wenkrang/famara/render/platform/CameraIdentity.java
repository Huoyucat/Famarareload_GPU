package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.output.CameraLedger;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import java.util.*;

/** 只在物品所属entity线程访问PDC。胶卷结算使用同一不可变账本快照，不能分两次读取总数。 */
public final class CameraIdentity {
    private static final NamespacedKey CAMERA=new NamespacedKey("famara","camera_id");
    private static final NamespacedKey APPLIED=new NamespacedKey("famara","film_applied_spent");
    private static final NamespacedKey REQUEST=new NamespacedKey("famara","photo_request_v2");
    public record Account(UUID camera,long appliedSpent) {}
    public record SynchronizedFilm(Account account,CameraLedger.Balance balance) {}
    private CameraIdentity() {}
    public static Optional<Account> account(ItemMeta meta) {
        PersistentDataContainer data=meta.getPersistentDataContainer();String id=data.get(CAMERA,PersistentDataType.STRING);
        Long applied=data.get(APPLIED,PersistentDataType.LONG);
        if(id==null && applied==null)return Optional.empty();
        if(id==null || applied==null || applied<0)throw new IllegalStateException("相机账本标记不完整，拒绝重新生成身份");
        return Optional.of(new Account(UUID.fromString(id),applied));
    }
    public static Account assign(ItemMeta meta) {
        Account account=account(meta).orElseGet(()->new Account(UUID.randomUUID(),0));write(meta,account);return account;
    }
    public static SynchronizedFilm synchronize(ItemMeta meta,int rawFilm,CameraLedger.Snapshot snapshot) {
        Account account=assign(meta);CameraLedger.Balance balance=snapshot.balance(account.camera,rawFilm,account.appliedSpent);
        Account updated=new Account(account.camera,balance.totalSpent());write(meta,updated);return new SynchronizedFilm(updated,balance);
    }
    private static void write(ItemMeta meta,Account account) {
        meta.getPersistentDataContainer().set(CAMERA,PersistentDataType.STRING,account.camera.toString());
        meta.getPersistentDataContainer().set(APPLIED,PersistentDataType.LONG,account.appliedSpent);
    }
    public static Optional<UUID> request(ItemMeta meta) {
        String id=meta.getPersistentDataContainer().get(REQUEST,PersistentDataType.STRING);return id==null?Optional.empty():Optional.of(UUID.fromString(id));
    }
    public static void request(ItemMeta meta,UUID id){meta.getPersistentDataContainer().set(REQUEST,PersistentDataType.STRING,id.toString());}
    public static void clearRequest(ItemMeta meta){meta.getPersistentDataContainer().remove(REQUEST);}
    /** 相机/已拍相机/撕拉片转换时显式复制插件标记，不复制CE物品模型身份。 */
    public static void copy(ItemStack source,ItemStack destination,boolean includeRequest) {
        if(source==null || !source.hasItemMeta() || !destination.hasItemMeta())return;
        ItemMeta from=source.getItemMeta(),to=destination.getItemMeta();
        account(from).ifPresent(account->write(to,account));
        if(includeRequest)request(from).ifPresent(id->request(to,id));else clearRequest(to);
        destination.setItemMeta(to);
    }
}
