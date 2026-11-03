package com.mcdiscordbot.fabric;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.entities.WebhookType;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.RestAction;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Reuses the first voice-pool bot; every Discord REST operation runs on this worker. */
final class ChatBridge extends ListenerAdapter implements AutoCloseable {
    static final String WEBHOOK_NAME = "MC Link Chat";
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("DiscordLinkPlusChat");
    private final BridgeConfig config;
    private final ChatRelay relay;
    private final java.nio.file.Path serverIcon;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DiscordLinkPlus-chat"); t.setDaemon(true); return t;
    });
    private volatile JDA client;
    private volatile boolean closed;
    private volatile String state = "waiting for first bot";
    private Webhook webhook;
    private boolean checkedWebhook;
    private long lastWarning;
    private volatile String webhookFailure = "", deliveryFailure = "";
    private volatile String iconState = "not loaded";
    private final java.util.concurrent.atomic.AtomicLong discordEvents = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong channelMessages = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong ignoredBotMessages = new java.util.concurrent.atomic.AtomicLong();

    ChatBridge(BridgeConfig config) { this(config, java.nio.file.Path.of("server-icon.png")); }
    ChatBridge(BridgeConfig config, java.nio.file.Path serverIcon) {
        this.config = config; this.serverIcon = serverIcon; relay = new ChatRelay(config.guildId, config.chat);
    }
    void start() { if (config.chat.enabled) worker.scheduleWithFixedDelay(this::work, 100, 100, TimeUnit.MILLISECONDS); }
    void bind(JDA firstBot) { if (!closed) client = firstBot; }
    ChatRelay relay() { return relay; }
    String status() {
        if (!config.chat.enabled) return "Text chat disabled.";
        return "Text chat: " + state + "; " + relay.metrics()
            + "\nChat channel: " + config.chat.channelId + "; directions MC→Discord=" + config.chat.minecraftToDiscord + ", Discord→MC=" + config.chat.discordToMinecraft
            + "; Discord events=" + discordEvents.get() + ", channel matches=" + channelMessages.get() + ", ignored bots/webhooks=" + ignoredBotMessages.get()
            + "\nServer announcement avatar: " + iconState
            + (webhookFailure.isEmpty() ? "" : "\nWebhook setup: " + webhookFailure)
            + (deliveryFailure.isEmpty() ? "" : "\nLast chat delivery failure: " + deliveryFailure);
    }

    @Override public void onMessageReceived(MessageReceivedEvent event) {
        if (closed || !config.chat.enabled || !config.chat.discordToMinecraft || !event.isFromGuild()) return;
        discordEvents.incrementAndGet();
        if (!config.guildId.equals(event.getGuild().getId()) || !config.chat.channelId.equals(event.getChannel().getId())) return;
        channelMessages.incrementAndGet();
        if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) { ignoredBotMessages.incrementAndGet(); return; }
        String name = event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        String text = DiscordAttachments.format(event.getMessage().getContentDisplay(), event.getMessage().getAttachments());
        relay.discord(event.getGuild().getId(), event.getChannel().getId(), false, false, name, text);
    }

    void work() {
        if (closed) return;
        try {
            JDA current = client;
            if (current == null || current.getStatus() != JDA.Status.CONNECTED) { state = "waiting for first bot (check Message Content Intent if chat was enabled)"; return; }
            var guild = current.getGuildById(config.guildId);
            TextChannel channel = guild == null ? null : guild.getTextChannelById(config.chat.channelId);
            if (channel == null) { state = "configured text channel missing or inaccessible"; return; }
            if (config.chat.useWebhooks && config.chat.minecraftToDiscord && !checkedWebhook) setupWebhook(channel, current);
            state = config.chat.useWebhooks && config.chat.minecraftToDiscord ? (webhook == null ? "connected (bot-message fallback)" : "connected (player webhooks)") : "connected (bot messages)";
            ChatRelay.Outgoing message = relay.nextOutgoing();
            if (message == null) return;
            try {
                if (webhook != null && (message.system() || validWebhookName(message.player()))) {
                    var action = webhook.sendMessage(message.webhookText())
                        .setUsername(message.system() ? config.chat.serverName : message.player())
                        .setAllowedMentions(List.of()).setSuppressEmbeds(true);
                    // No per-message avatar override for announcements: inherit server-icon.png.
                    if (!message.system()) action.setAvatarUrl(message.avatar());
                    await(action);
                } else {
                    await(channel.sendMessage(message.botText()).setAllowedMentions(List.of()).setSuppressEmbeds(true));
                }
                relay.sent();
                deliveryFailure = "";
            } catch (Exception e) {
                // A timed-out request may already have been delivered. Do not retry the same message and duplicate it.
                relay.failed();
                deliveryFailure = ChatFailure.describe(e);
                if (webhook != null) webhook = null;
                warn("message delivery", e);
            }
        } catch (Exception e) { relay.failed(); deliveryFailure = ChatFailure.describe(e); warn("worker", e); }
    }

    private void setupWebhook(TextChannel channel, JDA current) throws InterruptedException {
        checkedWebhook = true;
        try {
            webhook = await(channel.retrieveWebhooks()).stream()
                .filter(w -> w.getType() == WebhookType.INCOMING && WEBHOOK_NAME.equals(w.getName())
                    && w.getOwnerAsUser() != null && current.getSelfUser().getId().equals(w.getOwnerAsUser().getId()))
                .findFirst().orElse(null);
            Icon icon = loadIcon();
            if (webhook == null) {
                var action = channel.createWebhook(WEBHOOK_NAME);
                if (icon != null) action.setAvatar(icon);
                webhook = await(action);
            } else if (icon != null) {
                try { await(webhook.getManager().setAvatar(icon)); }
                catch (Exception e) { iconState = "server-icon.png upload failed: " + ChatFailure.describe(e); warn("server icon update", e); }
            }
            webhookFailure = "";
        } catch (InterruptedException e) { throw e; }
        catch (Exception e) { webhook = null; webhookFailure = ChatFailure.describe(e); warn("webhook setup", e); }
    }

    private Icon loadIcon() {
        if (!java.nio.file.Files.isRegularFile(serverIcon)) { iconState = "server-icon.png missing; using webhook default"; return null; }
        try {
            if (java.nio.file.Files.size(serverIcon) > 256 * 1024) throw new java.io.IOException("Icon too large");
            byte[] png = java.nio.file.Files.readAllBytes(serverIcon);
            if (png.length < 8 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') throw new java.io.IOException("Not PNG");
            Icon result = Icon.from(png, Icon.IconType.PNG);
            iconState = "server-icon.png";
            return result;
        } catch (Exception e) { iconState = "server-icon.png unreadable; using webhook default"; warn("server icon load", e); return null; }
    }

    private static <T> T await(RestAction<T> action) throws Exception {
        var future = action.submit();
        try { return future.get(15, TimeUnit.SECONDS); }
        catch (Exception e) { future.cancel(false); throw e; }
    }

    static boolean validWebhookName(String name) {
        if (name == null || name.isBlank() || name.length() > 80) return false;
        String normalized = name.toLowerCase(java.util.Locale.ROOT);
        return !normalized.contains("discord") && !normalized.contains("clyde");
    }

    private void warn(String operation, Exception error) {
        if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
        long now = System.nanoTime();
        if (now - lastWarning < 30_000_000_000L && lastWarning != 0) return;
        lastWarning = now;
        LOG.warn("Text relay {} failed: {}. Check /discordlink status. Message content and credentials are not logged.", operation, ChatFailure.describe(error));
    }

    void close(boolean announceStop) {
        if (closed) return;
        if (announceStop && relay.lifecycle(false)) {
            try {
                // Bound shutdown time and send before VoiceEngine closes the shared Discord client.
                worker.submit(this::work).get(5, TimeUnit.SECONDS);
            } catch (Exception e) { warn("server stopped announcement", e); }
        }
        closed = true; relay.close(); worker.shutdownNow(); client = null;
    }
    @Override public void close() { close(false); }
}
