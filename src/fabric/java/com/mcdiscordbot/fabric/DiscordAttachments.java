package com.mcdiscordbot.fabric;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import net.dv8tion.jda.api.entities.Message;

/** Presentation only: never fetch user-supplied links or expose signed upload URLs. */
final class DiscordAttachments {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "webp", "avif", "bmp", "tif", "tiff");
    private static final Set<String> VIDEOS = Set.of("mp4", "webm", "mov", "mkv", "avi", "m4v");

    private DiscordAttachments() {}

    static String format(String content, List<Message.Attachment> attachments) {
        String caption = ChatText.clean(URL.matcher(content).replaceAll(match -> {
            String token = match.group();
            int end = token.length();
            while (end > 0 && ".,!?;:)]}".indexOf(token.charAt(end - 1)) >= 0) end--;
            for (String wrapper : List.of("**", "__", "~~", "*", "_", "`", "\"", "'")) {
                if (match.start() >= wrapper.length()
                    && content.regionMatches(match.start() - wrapper.length(), wrapper, 0, wrapper.length())
                    && token.substring(0, end).endsWith(wrapper)) {
                    end -= wrapper.length();
                    break;
                }
            }
            return java.util.regex.Matcher.quoteReplacement(isPickerGif(token.substring(0, end))
                ? "[GIF]" + token.substring(end) : token);
        }));
        String labels = attachments.stream().map(DiscordAttachments::label).collect(Collectors.joining(" "));
        if (labels.isEmpty()) return caption;
        // Reserve space before the relay applies its overall incoming-text limit.
        int room = ChatRelay.INCOMING_TEXT_LIMIT - labels.length() - 1;
        if (caption.isEmpty() || room < 1) return labels;
        return ChatText.limit(caption, room) + " " + labels;
    }

    private static String label(Message.Attachment attachment) {
        String type = attachment.getContentType();
        type = type == null ? "" : type.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        String extension = attachment.getFileExtension();
        extension = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        boolean useExtension = type.isEmpty() || type.equals("application/octet-stream");
        if (type.equals("image/gif") || useExtension && extension.equals("gif")) return "[GIF]";
        if (type.startsWith("image/") || useExtension && IMAGES.contains(extension)) return "[Image]";
        if (type.startsWith("video/") || useExtension && VIDEOS.contains(extension)) return "[Video]";
        String name = ChatText.limit(ChatText.clean(attachment.getFileName()).replace('[', '(').replace(']', ')'), 64);
        return name.isBlank() ? "[File]" : "[File: " + name + "]";
    }

    private static boolean isPickerGif(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() != -1) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (host.equals("tenor.com") || host.equals("www.tenor.com")) return path.matches("/view/[^/]+");
            if (host.equals("giphy.com") || host.equals("www.giphy.com")) return path.matches("/gifs/[^/]+");
            if (host.equals("media.tenor.com")) return path.matches("(?i)/.+\\.(gif|mp4|webm)");
            if (host.equals("i.giphy.com") || host.matches("media[0-9]?\\.giphy\\.com"))
                return path.matches("(?i)/.+\\.(gif|mp4|webp)");
        } catch (IllegalArgumentException ignored) { /* Leave malformed or unfamiliar URLs intact. */ }
        return false;
    }
}
