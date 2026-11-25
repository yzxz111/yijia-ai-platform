package com.yuzhihuihe.apiplatform.gateway.security;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class RemoteUrlPolicy {

    private final AddressResolver resolver;
    private final Duration resolutionTimeout;

    public RemoteUrlPolicy(AddressResolver resolver, Duration resolutionTimeout) {
        if (resolver == null || resolutionTimeout == null
                || resolutionTimeout.isZero() || resolutionTimeout.isNegative()) {
            throw new IllegalArgumentException("invalid_remote_url_policy");
        }
        this.resolver = resolver;
        this.resolutionTimeout = resolutionTimeout;
    }

    public RemoteUrlPolicy(Duration resolutionTimeout) {
        this((host, timeout) -> Arrays.asList(InetAddress.getAllByName(host)), resolutionTimeout);
    }

    public Mono<ValidatedRemote> validate(URI uri) {
        if (!validUri(uri)) {
            return Mono.error(unsafe());
        }
        return Mono.fromCallable(() -> resolver.resolve(uri.getHost(), resolutionTimeout))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(resolutionTimeout, Mono.error(unsafe()))
                .onErrorMap(error -> error instanceof ResponseStatusException ? error : unsafe())
                .flatMap(addresses -> {
                    if (addresses == null || addresses.isEmpty()
                            || addresses.stream().anyMatch(address -> !isGlobalUnicast(address))) {
                        return Mono.error(unsafe());
                    }
                    return Mono.just(new ValidatedRemote(uri, addresses.getFirst(), 443));
                });
    }

    private boolean validUri(URI uri) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isBlank()
                && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    static boolean isGlobalUnicast(InetAddress address) {
        return GlobalUnicastAddress.isGlobalUnicast(address);
    }

    private ResponseStatusException unsafe() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsafe_remote_url");
    }

    @FunctionalInterface
    public interface AddressResolver {
        List<InetAddress> resolve(String host, Duration timeout) throws Exception;
    }

    public record ValidatedRemote(URI uri, InetAddress address, int port) { }
}
