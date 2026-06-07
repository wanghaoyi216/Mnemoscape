package com.mnemoscape.auth.controller;

import com.mnemoscape.common.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin diagnostics endpoint — answers "why is the dashboard showing
 * UPSTREAM_UNAVAILABLE?" with concrete probe results across all downstream
 * dependencies.
 *
 * <p>This complements (but does not replace) the {@code /api/v1/admin/health}
 * gateway endpoint: {@code /admin/health} is read-only and reports cached
 * status, while this endpoint actively re-probes every downstream and
 * returns the timing/error of each call. Useful when an operator hits
 * "重试" three times and still sees the same banner.
 *
 * <p>Path: {@code GET /api/v1/admin/diagnostics/probe}. Gated by the
 * existing {@code /api/v1/admin/**} ADMIN role rule in
 * {@link com.mnemoscape.auth.security.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin/diagnostics")
public class AdminDiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(AdminDiagnosticsController.class);

    /** Logical service names we expect to find in Nacos. */
    private static final String[] EXPECTED_SERVICES = {
            "auth-service", "memory-service", "resonance-service",
            "ai-service", "asset-service", "api-gateway"
    };

    private final DiscoveryClient discoveryClient;

    public AdminDiagnosticsController(DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /**
     * Probe every expected downstream and return a structured result that
     * the dashboard can render directly.
     *
     * <p>For each service:
     * <ol>
     *   <li><b>Discovery</b>: ask Nacos for instances; report 0 / N.</li>
     *   <li><b>TCP</b>: open a 2s socket to the first instance host:port to
     *       distinguish "registered but unreachable" from "not registered".</li>
     *   <li><b>HTTP /actuator/health</b>: full HTTP round-trip, 3s budget.</li>
     * </ol>
     */
    @GetMapping("/probe")
    public ResponseEntity<ApiResponse<Map<String, Object>>> probe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nacosRegisteredServices", discoveryClient.getServices());

        Map<String, Map<String, Object>> serviceProbes = new LinkedHashMap<>();
        for (String svc : EXPECTED_SERVICES) {
            serviceProbes.put(svc, probeService(svc));
        }
        result.put("services", serviceProbes);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** Probe a single service across discovery + TCP + HTTP layers. */
    private Map<String, Object> probeService(String serviceName) {
        Map<String, Object> probe = new LinkedHashMap<>();
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(serviceName);
            probe.put("discoveryInstances", instances.size());
            if (instances.isEmpty()) {
                probe.put("status", "NOT_REGISTERED");
                probe.put("hint", "Service is not registered in Nacos. Start the service or check its bootstrap config.");
                return probe;
            }
            ServiceInstance first = instances.get(0);
            probe.put("host", first.getHost());
            probe.put("port", first.getPort());

            // -- TCP probe --
            long tcpStart = System.currentTimeMillis();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(first.getHost(), first.getPort()), 2000);
                probe.put("tcpMs", System.currentTimeMillis() - tcpStart);
                probe.put("tcp", "OK");
            } catch (Exception e) {
                probe.put("tcpMs", System.currentTimeMillis() - tcpStart);
                probe.put("tcp", "FAIL");
                probe.put("tcpError", e.getClass().getSimpleName() + ": " + e.getMessage());
                probe.put("status", "TCP_REFUSED");
                probe.put("hint", "Service is registered but TCP connect refused — process may have crashed.");
                return probe;
            }

            // -- HTTP /actuator/health --
            long httpStart = System.currentTimeMillis();
            String healthUrl = "http://" + first.getHost() + ":" + first.getPort() + "/actuator/health";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(healthUrl).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int httpCode = conn.getResponseCode();
                probe.put("httpMs", System.currentTimeMillis() - httpStart);
                probe.put("httpCode", httpCode);
                if (httpCode >= 200 && httpCode < 300) {
                    probe.put("status", "UP");
                } else {
                    probe.put("status", "DEGRADED");
                    probe.put("hint", "Health endpoint returned " + httpCode + " — check service logs.");
                }
            } catch (IOException e) {
                probe.put("httpMs", System.currentTimeMillis() - httpStart);
                probe.put("status", "HTTP_FAIL");
                probe.put("httpError", e.getClass().getSimpleName() + ": " + e.getMessage());
                probe.put("hint", "TCP up but /actuator/health unreachable — Spring Boot may still be starting.");
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        } catch (Exception e) {
            log.warn("probeService failed service={} type={}", serviceName, e.getClass().getSimpleName(), e);
            probe.put("status", "PROBE_ERROR");
            probe.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return probe;
    }
}
