package moe.tristan.kmdah.mangadex.ping;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import moe.tristan.kmdah.mangadex.MangadexApi;
import moe.tristan.kmdah.mangadex.MangadexSettings;
import moe.tristan.kmdah.service.images.cache.CacheSettings;

@Service
public class PingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PingService.class);

    private final WebClient webClient;

    private final MangadexApi mangadexApi;
    private final int mangadexSpecVersion;

    private final CacheSettings cacheSettings;
    private final MangadexSettings mangadexSettings;

    public PingService(
        WebClient.Builder webClient,
        MangadexApi mangadexApi,
        @Value("${spring.application.spec}") int mangadexSpecVersion,
        CacheSettings cacheSettings,
        MangadexSettings mangadexSettings
    ) {
        this.webClient = webClient.build();
        this.mangadexApi = mangadexApi;
        this.mangadexSpecVersion = mangadexSpecVersion;
        this.cacheSettings = cacheSettings;
        this.mangadexSettings = mangadexSettings;
    }

    public Mono<PingResponse> ping(Optional<LocalDateTime> lastCreatedAt, DataSize poolSpeed) {
        long networkSpeedBytesPerSecond = poolSpeed.toBytes();
        if (networkSpeedBytesPerSecond == 0L) {
            LOGGER.info("Worker pool is empty, requesting 1B/s network speed");
            networkSpeedBytesPerSecond = 1L;
        }

        PingRequest request = new PingRequest(
            mangadexSettings.clientSecret(),
            mangadexSettings.loadBalancerIp().getHostAddress(),
            443,
            DataSize.ofGigabytes(cacheSettings.maxSizeGb()).toBytes(),
            networkSpeedBytesPerSecond,
            lastCreatedAt.map(ldt -> ldt.atZone(ZoneOffset.UTC)),
            mangadexSpecVersion
        );

        return webClient
            .post()
            .uri(mangadexApi.getApiUrl() + "/ping")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .onStatus(status -> HttpStatus.OK != status, this::onError)
            .bodyToMono(PingResponse.class)
            .doFirst(() -> LOGGER.info("Ping {}", request))
            .doOnSuccess(response -> LOGGER.info("Pong {}", response))
            .doOnError(error -> LOGGER.info("Error during heartbeat", error));
    }

    private Mono<? extends Throwable> onError(ClientResponse clientResponse) {
        return clientResponse
            .createException()
            .map(error -> switch (clientResponse.statusCode()) {
                case UNAUTHORIZED -> new IllegalStateException(
                    "Unauthorized! Either your secret is wrong, or your server was marked as compromised!", error
                );

                case UNSUPPORTED_MEDIA_TYPE -> new IllegalStateException(
                    "Content-Type was not set to application/json", error
                );

                case BAD_REQUEST -> new IllegalStateException(
                    "Json body was malformed!", error
                );

                case FORBIDDEN -> new IllegalStateException(
                    "Secret is not valid anymore!", error
                );

                default -> new RuntimeException(
                    "Unexpected server error response!", error
                );
            });
    }

}
