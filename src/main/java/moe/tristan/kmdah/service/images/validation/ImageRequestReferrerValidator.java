package moe.tristan.kmdah.service.images.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class ImageRequestReferrerValidator {

    private static final Pattern MANGADEX_HOST_MATCHER = Pattern.compile(
        // a subdomain followed by a dot (if any), then either mangadex.org, mangadex.network or mdah.tristan.moe
        "^((.+[.])?mangadex(\\.org|\\.network))|(mdah\\.tristan\\.moe)$"
    );

    public void validate(String referrer) {
        if (referrer == null || "".equals(referrer)) {
            return;
        }

        try {
            URI referrerUri = new URI(referrer);
            String host = referrerUri.getHost();
            if (host == null) {
                throw new InvalidImageRequestReferrerException("Invalid referrer didn't have a host for " + referrer);
            }

            if (!MANGADEX_HOST_MATCHER.matcher(host).find()) {
                throw new InvalidImageRequestReferrerException("Invalid Referrer header had unexpected host for " + referrer);
            }
        } catch (URISyntaxException e) {
            throw new InvalidImageRequestReferrerException("Invalid Referrer header was present but not a URI for " + referrer, e);
        }
    }

}
