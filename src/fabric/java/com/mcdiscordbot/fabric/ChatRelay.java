package com.mcdiscordbot.fabric;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded, immutable messages cross threads; Minecraft objects never enter the Discord worker. */
final class ChatRelay {
    static final int CAPACITY = 100;
    static final int INCOMING_TEXT_LIMIT = 900;
    private static final long EXPIRES = 60_000_000_000L;
    private final String guildId;
    private final ChatConfig config;
    private final LongSupplier clock;
    private final ArrayDeque<Outgoing> outgoing = new ArrayDeque<>();
    private final ArrayDeque<Incoming> incoming = new ArrayDeque<>();
    private boolean closed;
    private long dropped, sent, received, errors, minecraftEvents;

    ChatRelay(String guildId, ChatConfig config) { this(guildId, config, System::nanoTime); }
    ChatRelay(String guildId, ChatConfig config, LongSupplier clock) { this.guildId = guildId; this.config = config; this.clock = clock; }

    synchronized void minecraft(UUID id, String name, String textureProperty, String text) {
        if (closed || !config.enabled || !config.minecraftToDiscord) return;
        String content = ChatText.limit(ChatText.clean(text), 1700);
        if (content.isBlank()) return;
        String player = ChatText.limit(ChatText.clean(name), 80);
        Map<String, String> values = Map.of("player", ChatText.markdown(player), "message", content);
        offer(new Outgoing(player, ChatText.avatar(config.avatarUrl, id, player, textureProperty),
            ChatText.limit(ChatText.render(config.webhookMessageFormat, values), 1900),
            ChatText.limit(ChatText.render(config.minecraftToDiscordFormat, values), 1900), false, clock.getAsLong()));
    }

    synchronized void game(String key, String text) {
        if (closed || !config.enabled || !config.minecraftToDiscord || !ChatText.relayGameMessage(key, config)) return;
        String content = ChatText.limit(ChatText.markdown(ChatText.clean(text)), 1900);
        if (!content.isBlank()) offer(new Outgoing(null, null, content, content, true, clock.getAsLong()));
    }

    synchronized boolean lifecycle(boolean started) {
        if (closed || !config.enabled || !config.minecraftToDiscord || !config.serverMessages) return false;
        String content = ChatText.limit(ChatText.clean(started ? config.serverStartedMessage : config.serverStoppedMessage), 1900);
        if (outgoing.size() >= CAPACITY) { outgoing.removeLast(); dropped++; }
        outgoing.addFirst(new Outgoing(null, null, content, content, true, clock.getAsLong()));
        return true;
    }

    synchronized boolean discord(String guild, String channel, boolean bot, boolean webhook, String name, String text) {
        if (closed || !config.enabled || !config.discordToMinecraft || bot || webhook || !guildId.equals(guild) || !config.channelId.equals(channel)) return false;
        String content = ChatText.limit(ChatText.clean(text), INCOMING_TEXT_LIMIT);
        if (content.isBlank()) return false;
        if (incoming.size() >= CAPACITY) { dropped++; return false; }
        String formatted = ChatText.limit(ChatText.render(config.discordToMinecraftFormat,
            Map.of("user", ChatText.limit(ChatText.clean(name), 80), "message", content)), 1900);
        incoming.addLast(new Incoming(formatted, clock.getAsLong()));
        return true;
    }

    private void offer(Outgoing message) {
        if (outgoing.size() >= CAPACITY) { dropped++; return; }
        outgoing.addLast(message);
    }

    synchronized Outgoing nextOutgoing() {
        while (!outgoing.isEmpty()) {
            Outgoing message = outgoing.removeFirst();
            if (clock.getAsLong() - message.created() < EXPIRES) return message;
            dropped++;
        }
        return null;
    }

    synchronized Incoming nextIncoming() {
        while (!incoming.isEmpty()) {
            Incoming message = incoming.removeFirst();
            if (clock.getAsLong() - message.created() < EXPIRES) return message;
            dropped++;
        }
        return null;
    }

    synchronized void sent() { sent++; }
    synchronized void minecraftEvent() { minecraftEvents++; }
    synchronized void received() { received++; }
    synchronized void failed() { errors++; }
    synchronized String metrics() { return "MC→Discord=" + sent + ", Discord→MC=" + received + ", queued=" + outgoing.size() + "/" + incoming.size() + ", dropped=" + dropped + ", errors=" + errors + ", MC chat events=" + minecraftEvents; }
    synchronized void close() { closed = true; outgoing.clear(); incoming.clear(); }
    record Outgoing(String player, String avatar, String webhookText, String botText, boolean system, long created) {}
    record Incoming(String text, long created) {}
}
