package moe.tristan.kmdah.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import moe.tristan.kmdah.cache.CacheMode;
import moe.tristan.kmdah.mangadex.image.ImageMode;
import moe.tristan.kmdah.model.ImageContent;
import moe.tristan.kmdah.model.ImageSpec;
import moe.tristan.kmdah.service.images.ImageService;

@WebFluxTest(ImageController.class)
class ImageControllerTest {

    @MockBean
    private ImageService imageService;

    @MockBean
    private ImageRequestTokenValidator imageRequestTokenValidator;

    @MockBean
    private ImageRequestReferrerValidator imageRequestReferrerValidator;

    @Autowired
    private WebTestClient webTestClient;

    @ParameterizedTest
    @EnumSource(CacheMode.class)
    void onSuccess(CacheMode cacheMode) {
        String token = "sampletoken";
        String referrer = "referrer";

        ImageSpec sample = new ImageSpec(ImageMode.DATA, "chapter", "file");

        String expectedContent = UUID.randomUUID().toString();
        MediaType mediaType = MediaType.IMAGE_PNG;

        ImageContent sampleContent = sampleContent(expectedContent.getBytes(), mediaType, OptionalLong.empty(), cacheMode);

        when(imageService.findOrFetch(eq(sample))).thenReturn(Mono.just(sampleContent));

        webTestClient
            .get()
            .uri("/{token}/{mode}/{chapter}/{file}", token, sample.mode().getPathFragment(), sample.chapter(), sample.file())
            .header(HttpHeaders.REFERER, referrer)
            .exchange()
            .expectHeader().valueEquals("X-Cache-Mode", cacheMode.name())
            .expectBody(String.class)
            .consumeWith(result -> {
                validateMangadexHeadersPresent(result.getResponseHeaders());
                String content = result.getResponseBody();
                assertThat(content).isEqualTo(expectedContent);
            });

        verify(imageRequestTokenValidator).validate(eq(token), eq(sample.chapter()));
        verify(imageRequestReferrerValidator).validate(eq(referrer));
    }

    @Test
    void onErrorFromImageService() {
        String token = "sampletoken";
        String referrer = "referrer";

        ImageSpec sample = new ImageSpec(ImageMode.DATA, "chapter", "file");

        when(imageService.findOrFetch(eq(sample))).thenReturn(Mono.error(new IllegalStateException("Underlying error")));

        webTestClient
            .get()
            .uri("/{token}/{mode}/{chapter}/{file}", token, sample.mode().getPathFragment(), sample.chapter(), sample.file())
            .header(HttpHeaders.REFERER, referrer)
            .exchange()
            .expectStatus().is5xxServerError();
    }

    @Test
    void onInvalidToken() {
        String token = "sampletoken";

        ImageSpec sample = new ImageSpec(ImageMode.DATA, "chapter", "file");

        doThrow(new InvalidImageRequestTokenException(token))
            .when(imageRequestTokenValidator).validate(eq(token), eq(sample.chapter()));

        webTestClient
            .get()
            .uri("/{token}/{mode}/{chapter}/{file}", token, sample.mode().getPathFragment(), sample.chapter(), sample.file())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void onInvalidReferrerHeader() {
        String referrer = "referrer";

        ImageSpec sample = new ImageSpec(ImageMode.DATA, "chapter", "file");

        doThrow(new InvalidImageRequestReferrerException(referrer))
            .when(imageRequestReferrerValidator).validate(eq(referrer));

        webTestClient
            .get()
            .uri("/{token}/{mode}/{chapter}/{file}", "sometoken", sample.mode().getPathFragment(), sample.chapter(), sample.file())
            .header(HttpHeaders.REFERER, referrer)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void validateMangadexHeadersPresent(HttpHeaders headers) {
        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
            .containsExactly("https://mangadex.org");

        assertThat(headers.get(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
            .containsExactly("*");

        assertThat(headers.get(HttpHeaders.CACHE_CONTROL))
            .containsExactly("public/ max-age=1209600");

        assertThat(headers.get("Timing-Allow-Origin"))
            .containsExactly("https://mangadex.org");

        assertThat(headers.get("X-Content-Type-Options"))
            .containsExactly("nosniff");

        assertThat(headers.get("X-Content-Type-Options"))
            .containsExactly("nosniff");
    }

    private ImageContent sampleContent(byte[] contentBytes, MediaType mediaType, OptionalLong contentLength, CacheMode cacheMode) {
        Flux<DataBuffer> content = DataBufferUtils.readInputStream(
            () -> new ByteArrayInputStream(contentBytes),
            DefaultDataBufferFactory.sharedInstance,
            contentBytes.length
        );

        return new ImageContent(
            content,
            mediaType,
            contentLength,
            cacheMode
        );
    }

}