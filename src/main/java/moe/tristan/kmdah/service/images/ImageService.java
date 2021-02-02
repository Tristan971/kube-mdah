package moe.tristan.kmdah.service.images;

import static moe.tristan.kmdah.service.metrics.CacheSearchResult.ABORTED;
import static moe.tristan.kmdah.service.metrics.CacheSearchResult.FOUND;
import static moe.tristan.kmdah.service.metrics.CacheSearchResult.NOT_FOUND;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import moe.tristan.kmdah.mangadex.image.ImageMode;
import moe.tristan.kmdah.mangadex.image.MangadexImageService;
import moe.tristan.kmdah.service.gossip.messages.LeaderImageServerEvent;
import moe.tristan.kmdah.service.images.cache.CacheSettings;
import moe.tristan.kmdah.service.images.cache.CachedImageService;
import moe.tristan.kmdah.service.metrics.CacheSearchResult;
import moe.tristan.kmdah.service.metrics.ImageMetrics;
import moe.tristan.kmdah.util.ContentCallbackInputStream;

@Service
public class ImageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageService.class);

    private final CachedImageService cachedImageService;
    private final MangadexImageService mangadexImageService;
    private final ImageMetrics imageMetrics;
    private final long abortLookupThresholdMillis;

    private String upstreamServerUri = "https://s2.mangadex.org";

    public ImageService(
        CachedImageService cachedImageService,
        MangadexImageService mangadexImageService,
        ImageMetrics imageMetrics,
        CacheSettings cacheSettings
    ) {
        this.cachedImageService = cachedImageService;
        this.mangadexImageService = mangadexImageService;
        this.imageMetrics = imageMetrics;
        this.abortLookupThresholdMillis = cacheSettings.abortLookupThresholdMillis();
    }

    public ImageContent findOrFetch(ImageSpec imageSpec) {
        long startSearch = System.nanoTime();

        boolean aborted = false;
        Optional<ImageContent> cacheLookup;
        try {
            cacheLookup = CompletableFuture.<Optional<ImageContent>>supplyAsync(() -> {
                try {
                    return cachedImageService.findImage(imageSpec);
                } catch (Exception e) {
                    LOGGER.error("Failed searching image {} in cache", imageSpec, e);
                    return Optional.empty();
                }
            }).get(abortLookupThresholdMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("Aborted cache lookup for {} after 300ms.", imageSpec);
            cacheLookup = Optional.empty();
            aborted = true;
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Uncaught exception during cache lookup of {}", imageSpec, e);
            cacheLookup = Optional.empty();
        }

        CacheSearchResult searchResult = aborted
            ? ABORTED
            : cacheLookup.isPresent() ? FOUND : NOT_FOUND;

        // do not schedule saving of cache misses if they're due to aborted storage lookup
        // as it would only hurt a presumably-overloaded underlying storage system
        boolean saveMissToCache = ABORTED != searchResult;

        imageMetrics.recordSearchFromCache(startSearch, searchResult);

        ImageContent imageContent = cacheLookup.orElseGet(() -> {
            long startUptreamFetch = System.nanoTime();
            ImageContent upstreamResponseContent = fetchFromUpstream(imageSpec, saveMissToCache);
            imageMetrics.recordSearchFromUpstream(startUptreamFetch);
            return upstreamResponseContent;
        });

        LOGGER.info("Cache {} for {} (content-length: {})", imageContent.cacheMode(), imageSpec, imageContent.contentLength().orElse(-1L));
        imageMetrics.recordSearch(startSearch, imageContent.cacheMode());

        return imageContent;
    }

    private ImageContent fetchFromUpstream(ImageSpec imageSpec, boolean saveToCache) {
        ImageContent upstreamContent = mangadexImageService.download(imageSpec, upstreamServerUri);
        if (!saveToCache) {
            return upstreamContent;
        }

        try {
            Consumer<byte[]> cacheSaveCallback = bytes -> {
                if (validateImage(imageSpec, upstreamContent.contentLength(), bytes)) {
                    LOGGER.debug("Content of {} fully read from upstream. Triggering cache saving.", imageSpec);
                    cachedImageService.saveImage(imageSpec, upstreamContent.contentType(), new ByteArrayInputStream(bytes));
                }
            };

            return new ImageContent(
                new InputStreamResource(new ContentCallbackInputStream(upstreamContent.resource().getInputStream(), cacheSaveCallback)),
                upstreamContent.contentType(),
                upstreamContent.contentLength(),
                upstreamContent.lastModified(),
                upstreamContent.cacheMode()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot open upstream response for reading!", e);
        }
    }

    public void preload(ImageSpec imageSpec) {
        try {
            ImageContent content = findOrFetch(imageSpec);
            StreamUtils.drain(content.resource().getInputStream());
        } catch (IOException e) {
            LOGGER.error("Failed preloading of {}", imageSpec, e);
        }
    }

    public void delete(ImageSpec imageSpec) {
        LOGGER.info("DELETE {}", imageSpec);
        cachedImageService.deleteChapter(imageSpec);
        LOGGER.info("DELETE of {} succeeded", imageSpec);
    }

    public boolean validateImage(ImageSpec imageSpec, OptionalLong expectedLength, byte[] bytes) {
        if (expectedLength.isPresent()) {
            if (expectedLength.getAsLong() == bytes.length) {
                LOGGER.error("Mismatched length for {} ; expected {} bytes but got {} bytes", imageSpec, expectedLength.getAsLong(), bytes.length);
                return false;
            } else {
                return true;
            }
        }

        if (ImageMode.DATA_SAVER.equals(imageSpec.mode()) && imageSpec.file().contains("-")) {
            String expectedShasum = imageSpec.file().split("-")[1].split("\\.")[0];
            String actualShasum = DigestUtils.sha256Hex(bytes);
            if (!expectedShasum.equals(actualShasum)) {
                LOGGER.error("Mismatched shasum for {} ; expected {} but got {}", imageSpec, expectedLength, actualShasum);
                return false;
            }
        }

        return true;
    }

    @EventListener(LeaderImageServerEvent.class)
    public void onLeaderImageServerEvent(LeaderImageServerEvent leaderImageServerEvent) {
        if (!upstreamServerUri.equals(leaderImageServerEvent.imageServer())) {
            LOGGER.info("Changed upstream server uri to: {}", leaderImageServerEvent.imageServer());
            this.upstreamServerUri = leaderImageServerEvent.imageServer();
        }
    }

}
