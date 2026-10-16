package com.wenkrang.famara.lib;

import org.bukkit.NamespacedKey;
import com.wenkrang.famara.compat.RuntimeCompat;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** 以自身临时属性控制取景缩放，不再反复施加超长缓慢效果。 */
public final class ViewfinderZoom {
    static final NamespacedKey KEY = new NamespacedKey("famara", "viewfinder_zoom");
    public static final double DEFAULT_STRENGTH = 0.5;
    private ViewfinderZoom() {}

    /** 原版站立、完整 FOV 效果下，将相对正常视野的额外放大量按 strength 缩放。 */
    public static double modifierAmount(double strength) {
        if (!Double.isFinite(strength)) strength = DEFAULT_STRENGTH;
        strength = Math.max(0, Math.min(1, strength));
        double previousMagnification = 2.0 / (2.0 - 0.15 * 5);
        double magnification = 1 + (previousMagnification - 1) * strength;
        return 2.0 / magnification - 2.0;
    }

    public static void update(Player player, boolean viewing, boolean cameraHeld) {
        // 只在仍持有相机时兼容清理旧版本遗留的超长缓慢 V；普通短时缓慢不动。
        if (cameraHeld) {
            PotionEffect previous = player.getPotionEffect(RuntimeCompat.slowness());
            if (previous != null && isLegacyCameraEffect(previous.getAmplifier(), previous.getDuration()))
                player.removePotionEffect(RuntimeCompat.slowness());
        }
        var config = Config.CURRENT.ref();
        double strength = config == null ? DEFAULT_STRENGTH : config.getDouble("viewfinder-zoom-strength", DEFAULT_STRENGTH);
        updateAttribute(player.getAttribute(RuntimeCompat.movementAttributeType()), viewing, strength);
    }

    static boolean isLegacyCameraEffect(int amplifier, int duration) {
        return amplifier == 4 && duration > 1_000_000;
    }

    static void updateAttribute(AttributeInstance attribute, boolean viewing, double strength) {
        if (attribute == null) return;
        AttributeModifier previous = RuntimeCompat.ownModifier(attribute);
        double amount = modifierAmount(strength);
        if (!viewing || amount == 0) {
            if (previous != null) attribute.removeModifier(previous);
            return;
        }
        if (previous != null && previous.getOperation() == AttributeModifier.Operation.MULTIPLY_SCALAR_1
                && Math.abs(previous.getAmount() - amount) < 1.0e-9) return;
        if (previous != null) attribute.removeModifier(previous);
        attribute.addTransientModifier(RuntimeCompat.zoomModifier(amount));
    }

    public static void clear(Player player) {
        updateAttribute(player.getAttribute(RuntimeCompat.movementAttributeType()), false, DEFAULT_STRENGTH);
    }
}
