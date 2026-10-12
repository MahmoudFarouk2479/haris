package dev.haris.system;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.PowerSource;
import oshi.software.os.OperatingSystem;

/** Read-only hardware telemetry, shared by the HUD panels and the system_status tool. */
@Component
public class SystemStats {

    private static final double GIB = 1024.0 * 1024 * 1024;

    private final SystemInfo info = new SystemInfo();
    private final CentralProcessor cpu = info.getHardware().getProcessor();
    private final GlobalMemory memory = info.getHardware().getMemory();
    private final OperatingSystem os = info.getOperatingSystem();
    private long[] previousTicks = cpu.getSystemCpuLoadTicks();

    public synchronized Map<String, Object> snapshot() {
        double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(previousTicks);
        previousTicks = cpu.getSystemCpuLoadTicks();

        long total = memory.getTotal();
        long used = total - memory.getAvailable();

        long battery = -1;
        boolean charging = false;
        List<PowerSource> power = info.getHardware().getPowerSources();
        if (!power.isEmpty()) {
            PowerSource p = power.getFirst();
            battery = Math.round(p.getRemainingCapacityPercent() * 100);
            charging = p.isCharging() || p.isPowerOnLine();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu", Math.round(cpuLoad * 1000) / 10.0);
        m.put("cores", cpu.getLogicalProcessorCount());
        m.put("cpuName", cpu.getProcessorIdentifier().getName().strip());
        m.put("memUsedGb", round1(used / GIB));
        m.put("memTotalGb", round1(total / GIB));
        m.put("uptimeSeconds", os.getSystemUptime());
        m.put("os", os.toString());
        m.put("processes", os.getProcessCount());
        m.put("battery", battery);
        m.put("charging", charging);
        return m;
    }

    public List<Map<String, Object>> disks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (File root : File.listRoots()) {
            if (root.getTotalSpace() > 0) {
                out.add(Map.of("drive", root.getPath(),
                        "freeGb", round1(root.getUsableSpace() / GIB),
                        "totalGb", round1(root.getTotalSpace() / GIB)));
            }
        }
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
