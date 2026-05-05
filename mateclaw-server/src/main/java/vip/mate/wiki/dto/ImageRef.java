package vip.mate.wiki.dto;

/**
 * One image reference parsed out of a markdown body.
 *
 * <p>Maps to a single {@code ![alt](url)} occurrence. Search results carry
 * a list of these so the UI can render thumbnails alongside the page hit
 * without re-scanning the markdown on the client.
 *
 * @param fullMatch verbatim {@code ![alt](url)} as it appeared in the source
 * @param alt       alt text (may be empty if the markdown wrote {@code ![](url)})
 * @param url       resource URL — local path, absolute http(s) URL, or data URI
 *
 * @author MateClaw Team
 */
public record ImageRef(
        String fullMatch,
        String alt,
        String url
) {}
