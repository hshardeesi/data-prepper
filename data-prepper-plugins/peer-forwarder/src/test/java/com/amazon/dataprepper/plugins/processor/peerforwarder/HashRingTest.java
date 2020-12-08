package com.amazon.dataprepper.plugins.processor.peerforwarder;

import org.junit.Assert;
import org.junit.Test;
import org.powermock.reflect.Whitebox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class HashRingTest {
    public static final byte[] TEST_TRACE_ID_1 = "10".getBytes();
    public static final byte[] TEST_TRACE_ID_2 = "20".getBytes();

    @Test
    public void testGetServerIpSingleVirtualNode() {
        final List<String> testServerIps = Arrays.asList("20", "30", "10");
        final List<String> expServerIps = new ArrayList<>(testServerIps);
        Collections.sort(expServerIps);
        final HashRing hashRing = new HashRing(testServerIps, 1);
        Assert.assertEquals(expServerIps, hashRing.getServerIps());
        final TreeMap<Long, String> virtualNodes = Whitebox.getInternalState(hashRing, "virtualNodes");
        Assert.assertEquals(3, virtualNodes.size());
        Assert.assertTrue(virtualNodes.values().containsAll(expServerIps));
        // Check indeed alternating serverIps for different inputs
        final String serverIp1 = hashRing.getServerIp(TEST_TRACE_ID_1);
        final String serverIp2 = hashRing.getServerIp(TEST_TRACE_ID_2);
        Assert.assertNotEquals(serverIp1, serverIp2);
    }

    @Test
    public void testGetServerIpMultipleVirtualNode() {
        final List<String> testServerIps = Collections.singletonList("127.0.0.1");
        final HashRing hashRing = new HashRing(testServerIps, 3);
        Assert.assertEquals(testServerIps, hashRing.getServerIps());
        final TreeMap<Long, String> virtualNodes = Whitebox.getInternalState(hashRing, "virtualNodes");
        Assert.assertEquals(3, virtualNodes.size());
        final String serverIp = hashRing.getServerIp(TEST_TRACE_ID_1);
        Assert.assertEquals("127.0.0.1", serverIp);
    }
}
