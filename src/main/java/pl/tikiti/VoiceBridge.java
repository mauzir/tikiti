package pl.tikiti;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

/** Optional Simple Voice Chat integration. The video player also works without it. */
public final class VoiceBridge implements VoicechatPlugin {
    static volatile VoicechatServerApi api;

    @Override public String getPluginId() { return "tikiti"; }
    @Override public void initialize(VoicechatApi api) { }
    @Override public void registerEvents(EventRegistration events) {
        events.registerEvent(VoicechatServerStartedEvent.class, event -> api = event.getVoicechat());
    }

    static void register(TikitiPlugin plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) throw new IllegalStateException("Brak usługi BukkitVoicechatService");
        service.registerPlugin(new VoiceBridge());
    }
}
