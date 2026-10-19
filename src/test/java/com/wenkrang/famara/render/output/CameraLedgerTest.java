package com.wenkrang.famara.render.output;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CameraLedgerTest {
    @TempDir Path directory;
    private final CameraLedger.Limits limits=new CameraLedger.Limits(20,40,256*1024);
    @Test void failureReleasesReservationWithoutConsumption() throws Exception {
        UUID camera=UUID.randomUUID(),owner=UUID.randomUUID(),request=UUID.randomUUID();
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            ledger.reserve(request,camera,owner,1,0);
            assertEquals(0,ledger.snapshot().balance(camera,1,0).available());
            assertEquals(0,ledger.snapshot().spent(camera));
            assertThrows(IllegalStateException.class,()->ledger.reserve(UUID.randomUUID(),camera,owner,1,0));
            ledger.fail(request,"贴图缺失");
            assertEquals(1,ledger.snapshot().balance(camera,1,0).available());
            assertEquals(0,ledger.snapshot().spent(camera));
            assertThrows(IllegalStateException.class,()->ledger.succeed(request));
        }
    }
    @Test void successfulPublicationIsChargedOnceEvenAfterCameraChangesOwner() throws Exception {
        UUID camera=UUID.randomUUID(),request=UUID.randomUUID(),owner=UUID.randomUUID();
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            ledger.reserve(request,camera,owner,16,0);ledger.bindOutput(request,42);
            ledger.succeed(request);ledger.succeed(request);ledger.fail(request,"迟到失败");
            assertEquals(1,ledger.snapshot().spent(camera));
            var balance=ledger.snapshot().balance(camera,16,0);
            assertEquals(15,balance.loaded());assertEquals(15,balance.available());
            assertEquals(15,ledger.snapshot().balance(camera,15,1).loaded());
            ledger.reserve(UUID.randomUUID(),camera,UUID.randomUUID(),15,1);
        }
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            assertEquals(1,ledger.snapshot().spent(camera));
            assertEquals(15,ledger.snapshot().balance(camera,16,0).available());
            assertEquals(CameraLedger.Status.SUCCEEDED,ledger.snapshot().job(request).orElseThrow().status());
        }
    }
    @Test void crashRecoveryReleasesOnlyUnsettledJobsAndPreventsCounterRollback() throws Exception {
        UUID camera=UUID.randomUUID(),owner=UUID.randomUUID(),request=UUID.randomUUID();
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            ledger.reserve(request,camera,owner,1,0);ledger.bindOutput(request,7);
            assertThrows(java.io.IOException.class,()->new CameraLedger(directory,limits));
        } catch(java.nio.channels.OverlappingFileLockException expected) {
            fail("文件锁冲突应该转换成受控打开失败");
        }
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            assertEquals(CameraLedger.Status.FAILED,ledger.snapshot().job(request).orElseThrow().status());
            assertEquals(0,ledger.snapshot().spent(camera));
            assertEquals(1,ledger.snapshot().balance(camera,1,0).available());
            assertThrows(IllegalStateException.class,()->ledger.snapshot().balance(camera,1,1));
        }
    }
    @Test void failedAtomicWriteDoesNotChangeInMemoryConsumption() throws Exception {
        UUID request=UUID.randomUUID(),camera=UUID.randomUUID();
        try(CameraLedger ledger=new CameraLedger(directory,limits)) {
            ledger.reserve(request,camera,UUID.randomUUID(),1,0);ledger.bindOutput(request,2);
            Path original=directory.resolve("ledger.json"),backup=directory.resolve("saved.json");
            Files.move(original,backup);Files.createDirectory(original);Files.writeString(original.resolve("blocking"),"keep");
            assertThrows(java.io.IOException.class,()->ledger.succeed(request));
            assertEquals(0,ledger.snapshot().spent(camera));
            assertEquals(CameraLedger.Status.RESERVED,ledger.snapshot().job(request).orElseThrow().status());
            Files.delete(original.resolve("blocking"));Files.delete(original);Files.move(backup,original);
            ledger.succeed(request);assertEquals(1,ledger.snapshot().spent(camera));
        }
    }
    @Test void corruptCounterNeverGetsResetToZero() throws Exception {
        Files.writeString(directory.resolve("ledger.json"),"{\"version\":1,\"cameras\":{\""+UUID.randomUUID()+"\":1},\"jobs\":[]}");
        assertThrows(java.io.IOException.class,()->new CameraLedger(directory,limits));
    }

    @Test void deletedLedgerCannotResetAnAlreadyUsedCameraBalance() throws Exception {
        try(var ledger=new CameraLedger(directory,limits)) {assertTrue(Files.exists(directory.resolve("ledger.json")));}
        Files.delete(directory.resolve("ledger.json"));
        assertThrows(java.io.IOException.class,()->new CameraLedger(directory,limits));
    }
    @Test void failedReservationIsReleasedInMemoryEvenWhenDiskCannotRecordFailure() throws Exception {
        UUID request=UUID.randomUUID(),camera=UUID.randomUUID();
        try(var ledger=new CameraLedger(directory,limits)) {
            ledger.reserve(request,camera,UUID.randomUUID(),1,0);Path file=directory.resolve("ledger.json"),saved=directory.resolve("saved.json");
            Files.move(file,saved);Files.createDirectory(file);Files.writeString(file.resolve("blocking"),"keep");
            assertThrows(java.io.IOException.class,()->ledger.fail(request,"渲染失败"));
            assertEquals(CameraLedger.Status.FAILED,ledger.snapshot().job(request).orElseThrow().status());
            assertEquals(1,ledger.snapshot().balance(camera,1,0).available());assertEquals(0,ledger.snapshot().spent(camera));
            Files.delete(file.resolve("blocking"));Files.delete(file);Files.move(saved,file);
        }
        try(var recovered=new CameraLedger(directory,limits)) {
            assertEquals(1,recovered.snapshot().balance(camera,1,0).available());assertEquals(CameraLedger.Status.FAILED,recovered.snapshot().job(request).orElseThrow().status());
        }
    }
}
