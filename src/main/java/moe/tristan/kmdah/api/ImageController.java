package moe.tristan.kmdah.api;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import reactor.core.publisher.Flux;

import moe.tristan.kmdah.mangadex.image.ImageMode;
import moe.tristan.kmdah.mangadex.image.MangadexHeaders;
import moe.tristan.kmdah.model.ImageSpec;
import moe.tristan.kmdah.service.images.ImageService;

@Controller
public class ImageController {

    private final ImageService imageService;
    private final MangadexHeaders mangadexHeaders;

    public ImageController(ImageService imageService, MangadexHeaders mangadexHeaders) {
        this.imageService = imageService;
        this.mangadexHeaders = mangadexHeaders;
    }

    @GetMapping("/{token}/{image-mode}/{chapterHash}/{fileName}")
    public Flux<DataBuffer> tokenizedImage(
        @SuppressWarnings("unused") @PathVariable String token,
        @PathVariable("image-mode") String imageMode,
        @PathVariable String chapterHash,
        @PathVariable String fileName,
        ServerHttpResponse response
    ) {
        return serve(response, imageMode, chapterHash, fileName);
    }

    @GetMapping("/{image-mode}/{chapterHash}/{fileName}")
    public Flux<DataBuffer> unTokenizedImage(
        @PathVariable("image-mode") String imageMode,
        @PathVariable String chapterHash,
        @PathVariable String fileName,
        ServerHttpResponse response
    ) {
        return serve(response, imageMode, chapterHash, fileName);
    }

    private Flux<DataBuffer> serve(ServerHttpResponse response, String imageMode, String chapter, String file) {
        ImageSpec imageRequest = new ImageSpec(ImageMode.fromPathFragment(imageMode), chapter, file);

        return imageService
            .findOrFetch(imageRequest)
            .flux()
            .flatMap(image -> {
                mangadexHeaders.addHeaders(response.getHeaders());

                response.getHeaders().setContentType(image.contentType());
                image.contentLength().ifPresent(response.getHeaders()::setContentLength);

                response.getHeaders().add("X-Cache-Mode", image.cacheMode().name());
                return image.bytes();
            });
    }

}
