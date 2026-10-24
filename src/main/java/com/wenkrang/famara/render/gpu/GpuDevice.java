package com.wenkrang.famara.render.gpu;

/** A copied device description, never a native handle. */
public record GpuDevice(String platform,String vendor,String name,String version,String driverVersion,
                        long globalMemoryBytes,long maximumAllocationBytes,int addressBits,
                        long floatCapabilities,long doubleCapabilities) {}
