package com.wenkrang.famara.render.gpu;

import com.wenkrang.famara.BootLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GpuHotLoaderTest {
    @TempDir Path directory;
    @Test void currentNestedKernelResourcesOverrideParentAndJniClassesStayInStableParent()throws Exception {
        Files.createDirectories(directory.resolve("opencl"));Files.writeString(directory.resolve("opencl/trace.cl"),"current extracted kernel");var loader=new BootLoader.HotClassLoader(directory.toString());
        try(var resource=loader.getResource("opencl/trace.cl").openStream()){assertEquals("current extracted kernel",new String(resource.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        assertSame(org.jocl.cl_mem.class,loader.loadClass("org.jocl.cl_mem"));assertSame(BootLoader.class.getClassLoader(),loader.loadClass("org.jocl.cl_mem").getClassLoader());
    }
}
