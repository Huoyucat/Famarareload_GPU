package com.wenkrang.famara.lib;

import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViewfinderZoomTest {
    @Test void extraMagnificationIsExactlyHalfAtDefaultStrength() {
        double old = magnification(ViewfinderZoom.modifierAmount(1));
        double reduced = magnification(ViewfinderZoom.modifierAmount(0.5));
        assertEquals(1.6, old, 1e-10);
        assertEquals(1.3, reduced, 1e-10);
        assertEquals((old - 1) / 2, reduced - 1, 1e-10);
        assertEquals(0, ViewfinderZoom.modifierAmount(0), 1e-10);
    }

    private double magnification(double amount) { return 2 / (2 + amount); }

    @Test void invalidStrengthIsClampedAndNanUsesDefault() {
        assertEquals(ViewfinderZoom.modifierAmount(0), ViewfinderZoom.modifierAmount(-1));
        assertEquals(ViewfinderZoom.modifierAmount(1), ViewfinderZoom.modifierAmount(2));
        assertEquals(ViewfinderZoom.modifierAmount(0.5), ViewfinderZoom.modifierAmount(Double.NaN));
    }

    @Test void repeatedUpdateDoesNotStackAndClosingRemovesOnlyOwnModifier() {
        AttributeInstance attribute = mock(AttributeInstance.class);
        AtomicReference<AttributeModifier> installed = new AtomicReference<>();
        when(attribute.getModifiers()).thenAnswer(call -> installed.get() == null ? java.util.List.of() : java.util.List.of(installed.get()));
        doAnswer(call -> { installed.set(call.getArgument(0)); return null; })
                .when(attribute).addTransientModifier(any());
        doAnswer(call -> { installed.set(null); return null; })
                .when(attribute).removeModifier(any(AttributeModifier.class));
        ViewfinderZoom.updateAttribute(attribute, true, 0.5);
        ViewfinderZoom.updateAttribute(attribute, true, 0.5);
        verify(attribute, times(1)).addTransientModifier(any());
        assertNotNull(installed.get());
        assertEquals(ViewfinderZoom.modifierAmount(0.5), installed.get().getAmount());
        ViewfinderZoom.updateAttribute(attribute, false, 0.5);
        assertNull(installed.get());
        verify(attribute, times(1)).removeModifier(any(AttributeModifier.class));
        verify(attribute, never()).setBaseValue(anyDouble());
    }

    @Test void changedStrengthReplacesInsteadOfStacking() {
        AttributeInstance attribute = mock(AttributeInstance.class);
        var previous = com.wenkrang.famara.compat.RuntimeCompat.zoomModifier(ViewfinderZoom.modifierAmount(1));
        when(attribute.getModifiers()).thenReturn(java.util.List.of(previous));
        ViewfinderZoom.updateAttribute(attribute, true, 0.5);
        verify(attribute).removeModifier(previous);
        verify(attribute).addTransientModifier(argThat(m -> m.getAmount() == ViewfinderZoom.modifierAmount(0.5)));
    }

    @Test void onlyLegacyLongDurationLevelFiveIsEligibleForMigration() {
        assertTrue(ViewfinderZoom.isLegacyCameraEffect(4, 9_999_999));
        assertFalse(ViewfinderZoom.isLegacyCameraEffect(4, 1200));
        assertFalse(ViewfinderZoom.isLegacyCameraEffect(2, 9_999_999));
        assertDoesNotThrow(() -> ViewfinderZoom.updateAttribute(null, true, 0.5));
    }
}
