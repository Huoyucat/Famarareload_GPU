package com.wenkrang.famara.render.platform;

import com.wenkrang.famara.render.output.CameraLedger;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraIdentityTest {
    @TempDir Path directory;
    private ItemMeta meta(Map<NamespacedKey,Object> storage) {
        ItemMeta meta=mock(ItemMeta.class);PersistentDataContainer data=mock(PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(data);when(data.get(any(),any())).thenAnswer(call->storage.get(call.getArgument(0)));
        doAnswer(call->{storage.put(call.getArgument(0),call.getArgument(2));return null;}).when(data).set(any(),any(),any());
        return meta;
    }
    @Test void synchronizationUsesOneLedgerSnapshotAndNeverDoubleChargesNormalizedFilm() throws Exception {
        ItemMeta meta=meta(new HashMap<>());var account=CameraIdentity.assign(meta);UUID job=UUID.randomUUID();
        try(var ledger=new CameraLedger(directory,new CameraLedger.Limits(10,20,128*1024))) {
            ledger.reserve(job,account.camera(),UUID.randomUUID(),16,0);ledger.bindOutput(job,4);ledger.succeed(job);
            var first=CameraIdentity.synchronize(meta,16,ledger.snapshot());assertEquals(15,first.balance().loaded());assertEquals(1,first.account().appliedSpent());
            var second=CameraIdentity.synchronize(meta,15,ledger.snapshot());assertEquals(15,second.balance().loaded());assertEquals(account.camera(),second.account().camera());
        }
    }
    @Test void partialIdentityIsRejectedInsteadOfErasingOldDebt() {
        Map<NamespacedKey,Object> storage=new HashMap<>();storage.put(new NamespacedKey("famara","film_applied_spent"),1L);
        assertThrows(IllegalStateException.class,()->CameraIdentity.assign(meta(storage)));
    }
}
