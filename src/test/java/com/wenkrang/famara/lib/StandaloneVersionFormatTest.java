package com.wenkrang.famara.lib;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneVersionFormatTest {
    @Test void knownTwentySixMaintenanceVersionsDoNotFallBackToAncientPackFormat() {
        for(String version:new String[]{"26.1","26.1.1","26.1.2"}) {Object value=StandalonePack.formatForMinecraftVersion(version);assertNotNull(value);assertEquals("Format[major=84, minor=0]",value.toString());}
        Object second=StandalonePack.formatForMinecraftVersion("26.2");assertEquals("Format[major=88, minor=0]",second.toString());Object third=StandalonePack.formatForMinecraftVersion("26.3");assertEquals("Format[major=97, minor=1]",third.toString());
        assertNull(StandalonePack.formatForMinecraftVersion("26.4"));assertNull(StandalonePack.formatForMinecraftVersion("26.1.99"));
    }
}
