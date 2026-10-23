package io.canvasmc.canvas.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionizedBarColourTestSuite {

    private static final int GREEN = 0x55FF55;
    private static final int YELLOW = 0xFFFF55;
    private static final int ORANGE = 0xFFA500;
    private static final int RED = 0xFF5555;

    @Test
    void cpuCoreLoadColourThresholds() {
        assertEquals(GREEN, RegionizedCpuBar.coreLoadColour(0.0D).value());
        assertEquals(GREEN, RegionizedCpuBar.coreLoadColour(39.9D).value());
        assertEquals(YELLOW, RegionizedCpuBar.coreLoadColour(40.0D).value());
        assertEquals(YELLOW, RegionizedCpuBar.coreLoadColour(59.9D).value());
        assertEquals(ORANGE, RegionizedCpuBar.coreLoadColour(60.0D).value());
        assertEquals(ORANGE, RegionizedCpuBar.coreLoadColour(79.9D).value());
        assertEquals(RED, RegionizedCpuBar.coreLoadColour(80.0D).value());
        assertEquals(RED, RegionizedCpuBar.coreLoadColour(100.0D).value());
    }

    @Test
    void networkPacketColourThresholds() {
        assertEquals(GREEN, RegionizedNetworkBar.packetColour(0.0D).value());
        assertEquals(GREEN, RegionizedNetworkBar.packetColour(49.9D).value());
        assertEquals(YELLOW, RegionizedNetworkBar.packetColour(50.0D).value());
        assertEquals(YELLOW, RegionizedNetworkBar.packetColour(199.9D).value());
        assertEquals(ORANGE, RegionizedNetworkBar.packetColour(200.0D).value());
        assertEquals(ORANGE, RegionizedNetworkBar.packetColour(999.9D).value());
        assertEquals(RED, RegionizedNetworkBar.packetColour(1000.0D).value());
    }

    @Test
    void networkPingColourThresholds() {
        assertEquals(GREEN, RegionizedNetworkBar.pingColour(0.0D).value());
        assertEquals(GREEN, RegionizedNetworkBar.pingColour(79.9D).value());
        assertEquals(YELLOW, RegionizedNetworkBar.pingColour(80.0D).value());
        assertEquals(YELLOW, RegionizedNetworkBar.pingColour(149.9D).value());
        assertEquals(ORANGE, RegionizedNetworkBar.pingColour(150.0D).value());
        assertEquals(ORANGE, RegionizedNetworkBar.pingColour(249.9D).value());
        assertEquals(RED, RegionizedNetworkBar.pingColour(250.0D).value());
    }

    @Test
    void networkBarNormalizesLegacyTokensAndExposesKeys() {
        final RegionizedNetworkBar bar = new RegionizedNetworkBar();

        assertEquals("<rx> <tx> <ping> <players>", bar.normalize("%rx% %tx% %ping% %players%"));
        assertArrayEquals(new String[]{"rx", "tx", "ping", "players"}, bar.getKeys());
    }
}
