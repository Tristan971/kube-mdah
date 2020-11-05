package moe.tristan.kmdah.worker.api;

import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import moe.tristan.kmdah.common.mangadex.image.Image;
import moe.tristan.kmdah.common.mangadex.image.ImageMode;
import moe.tristan.kmdah.worker.model.ImageRequest;

@RestController
public class ImageController {

    @GetMapping("/{token}/{image-mode}/{chapterHash}/{fileName}")
    public byte[] tokenizedImage(
        @PathVariable String token,
        @PathVariable("image-mode") String imageMode,
        @PathVariable String chapterHash,
        @PathVariable String fileName,
        HttpServletResponse response
    ) {
        return serve(response, imageMode, chapterHash, fileName);
    }

    @GetMapping("/{image-mode}/{chapterHash}/{fileName}")
    public byte[] unTokenizedImage(
        @PathVariable("image-mode") String imageMode,
        @PathVariable String chapterHash,
        @PathVariable String fileName,
        HttpServletResponse response
    ) {
        return serve(response, imageMode, chapterHash, fileName);
    }

    private byte[] serve(HttpServletResponse response, String imageMode, String chapter, String file) {
        ImageRequest imageRequest = ImageRequest.of(
            ImageMode.fromPathFragment(imageMode),
            chapter,
            file
        );
        Image image = fetchImage(imageRequest);
        response.setContentType(image.getContentType());

        // MDAH spec headers
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://mangadex.org");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "*");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public/ max-age=1209600");
        response.setHeader("Timing-Allow-Origin", "https://mangadex.org");
        response.setHeader("X-Content-Type-Options", "nosniff");

        return image.getBytes();
    }

    private Image fetchImage(ImageRequest imageRequest) {
        return Image
            .builder()
            .bytes((byte) 0)
            .contentType(MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

}
