package querygate.filter;

import querygate.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import jakarta.annotation.PostConstruct;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * HTTP filter that enforces network-based access control for API endpoints.
 * Only allows requests from configured allowed networks (CIDR notation supported).
 */
@Filter("/api/**")
@Requires(property = "gateway.security.enabled", value = "true", defaultValue = "true")
public class NetworkFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkFilter.class);

    private final GatewayProperties.SecurityConfig securityConfig;
    private final MeterRegistry meterRegistry;
    private final List<NetworkRange> allowedRanges;

    public NetworkFilter(GatewayProperties properties, MeterRegistry meterRegistry) {
        this.securityConfig = properties.getSecurity();
        this.meterRegistry = meterRegistry;
        this.allowedRanges = parseNetworks(securityConfig.getAllowedNetworks());

        LOG.info("Network filter initialized with {} allowed network(s)", allowedRanges.size());
    }

    @PostConstruct
    void registerMetrics() {
        meterRegistry.gauge("gateway.network.allowed_ranges", allowedRanges, List::size);
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // If no networks are configured, allow all (backwards compatibility)
        if (allowedRanges.isEmpty()) {
            return chain.proceed(request);
        }

        String clientIp = getClientIp(request);

        if (clientIp == null || !isAllowed(clientIp)) {
            LOG.warn("Request blocked from unauthorized network: {} for {} {}",
                    clientIp, request.getMethod(), request.getPath());
            meterRegistry.counter("gateway.network.blocked",
                    "path", request.getPath()).increment();
            return Mono.just(forbidden("Access denied from this network"));
        }

        LOG.trace("Request allowed from network: {}", clientIp);
        return chain.proceed(request);
    }

    /**
     * Extracts client IP from request, considering X-Forwarded-For header.
     */
    private String getClientIp(HttpRequest<?> request) {
        // Check X-Forwarded-For first (for reverse proxy setups)
        String forwardedFor = request.getHeaders().get("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            // Take the first IP in the chain (original client)
            String[] ips = forwardedFor.split(",");
            return ips[0].trim();
        }

        // Fall back to remote address
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress != null && remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }

        return null;
    }

    /**
     * Checks if the client IP is within any of the allowed network ranges.
     */
    private boolean isAllowed(String clientIp) {
        try {
            InetAddress clientAddress = InetAddress.getByName(clientIp);
            for (NetworkRange range : allowedRanges) {
                if (range.contains(clientAddress)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            LOG.warn("Failed to parse client IP: {}", clientIp);
        }
        return false;
    }

    /**
     * Parses network specifications (IP or CIDR) into NetworkRange objects.
     */
    private List<NetworkRange> parseNetworks(List<String> networks) {
        List<NetworkRange> ranges = new ArrayList<>();
        for (String network : networks) {
            try {
                ranges.add(NetworkRange.parse(network));
                LOG.debug("Added allowed network: {}", network);
            } catch (IllegalArgumentException e) {
                LOG.error("Invalid network specification: {}", network, e);
            }
        }
        return ranges;
    }

    private MutableHttpResponse<?> forbidden(String message) {
        return HttpResponse.status(HttpStatus.FORBIDDEN)
                .body(Map.of(
                        "success", false,
                        "error", "Forbidden",
                        "message", message
                ));
    }

    @Override
    public int getOrder() {
        return -200; // Run before BackpressureFilter (-100)
    }

    /**
     * Represents a network range for IP matching.
     * Supports both individual IPs and CIDR notation.
     */
    static class NetworkRange {
        private final byte[] networkAddress;
        private final int prefixLength;
        private final boolean isIPv6;

        private NetworkRange(byte[] networkAddress, int prefixLength, boolean isIPv6) {
            this.networkAddress = networkAddress;
            this.prefixLength = prefixLength;
            this.isIPv6 = isIPv6;
        }

        static NetworkRange parse(String spec) {
            try {
                String[] parts = spec.split("/");
                InetAddress address = InetAddress.getByName(parts[0].trim());
                byte[] addressBytes = address.getAddress();
                boolean isIPv6 = addressBytes.length == 16;

                int prefixLength;
                if (parts.length == 2) {
                    prefixLength = Integer.parseInt(parts[1].trim());
                } else {
                    // No prefix - treat as single host
                    prefixLength = isIPv6 ? 128 : 32;
                }

                // Validate prefix length
                int maxPrefix = isIPv6 ? 128 : 32;
                if (prefixLength < 0 || prefixLength > maxPrefix) {
                    throw new IllegalArgumentException("Invalid prefix length: " + prefixLength);
                }

                return new NetworkRange(addressBytes, prefixLength, isIPv6);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP address: " + spec, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] addressBytes = address.getAddress();

            // IPv4 vs IPv6 mismatch
            if ((addressBytes.length == 16) != isIPv6) {
                return false;
            }

            // Compare bits up to prefix length
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Compare full bytes
            for (int i = 0; i < fullBytes; i++) {
                if (addressBytes[i] != networkAddress[i]) {
                    return false;
                }
            }

            // Compare remaining bits in partial byte
            if (remainingBits > 0 && fullBytes < addressBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addressBytes[fullBytes] & mask) != (networkAddress[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        }
    }
}
