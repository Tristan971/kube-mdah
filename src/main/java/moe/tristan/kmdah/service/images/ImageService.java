package moe.tristan.kmdah.service.images;

import static reactor.core.publisher.Mono.defer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import moe.tristan.kmdah.cache.CachedImageService;
import moe.tristan.kmdah.mangadex.image.MangadexImageService;
import moe.tristan.kmdah.model.ImageContent;
import moe.tristan.kmdah.model.ImageSpec;
import moe.tristan.kmdah.service.metrics.CacheModeCounter;

@Service
public class ImageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageService.class);

    private final CachedImageService cachedImageService;
    private final CacheModeCounter cacheModeCounter;
    private final MangadexImageService mangadexImageService;

    public ImageService(
        CachedImageService cachedImageService,
        CacheModeCounter cacheModeCounter,
        MangadexImageService mangadexImageService
    ) {
        this.cachedImageService = cachedImageService;
        this.cacheModeCounter = cacheModeCounter;
        this.mangadexImageService = mangadexImageService;
    }

    public Mono<ImageContent> findOrFetch(ImageSpec imageSpec) {
        return fetchFromCache(imageSpec)
            .onErrorResume(error -> {
                LOGGER.error("Failed pulling {} from cache!", imageSpec, error);
                return defer(() -> fetchFromUpstream(imageSpec));
            })
            .switchIfEmpty(defer(() -> fetchFromUpstream(imageSpec)))
            .doOnNext(content -> {
                LOGGER.info("Cache {} for {}", content.cacheMode(), imageSpec);
                cacheModeCounter.record(content.cacheMode());
            });
    }

    private Mono<ImageContent> fetchFromCache(ImageSpec imageSpec) {
        return cachedImageService.findImage(imageSpec);
    }

    private Mono<ImageContent> fetchFromUpstream(ImageSpec imageSpec) {
        return mangadexImageService
            .download(imageSpec, "https://s2.mangadex.org")
            .map(content -> {
                Flux<DataBuffer> sourceImageBytes = content.bytes();

                cachedImageService.saveImage(imageSpec, new ImageContent(
                    Flux.from(sourceImageBytes),
                    content.contentType(),
                    content.contentLength(),
                    content.cacheMode()
                )).subscribe();

                return new ImageContent(
                    Flux.from(sourceImageBytes),
                    content.contentType(),
                    content.contentLength(),
                    content.cacheMode()
                );
            });
    }

}