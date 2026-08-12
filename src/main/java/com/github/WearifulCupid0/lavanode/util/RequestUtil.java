package com.github.WearifulCupid0.lavanode.util;
import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerFrameLossCounter;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.filters.PlayerFilters;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import moe.kyokobot.koe.MediaConnection;
import moe.kyokobot.koe.VoiceServerInfo;
import moe.kyokobot.koe.gateway.MediaGatewayConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;

public class RequestUtil {
    private static final Logger log = LoggerFactory.getLogger(RequestUtil.class);
    private static final int MAX_ENCODED_TRACK_LENGTH = 128 * 1024;
    private static final Class<?> INTERNAL_BEAN_CLASS;
    private static final BiConsumer<AudioPlayer, AudioTrackEndReason> STOP_PLAYER_WITH_REASON;
    static {
        Class<?> c;
        try {
            c = Class.forName("com.sun.management.OperatingSystemMXBean");
        } catch(Exception e) {
            c = null;
            log.error("Unable to load internal OperatingSystemMXBean class. CPU usage info unavailable");
        }
        try {
            var method = DefaultAudioPlayer.class.getDeclaredMethod("stopWithReason", AudioTrackEndReason.class);
            method.setAccessible(true);
            STOP_PLAYER_WITH_REASON = (player, reason) -> {
                try {
                    method.invoke(player, reason);
                } catch(Exception e) {
                    throw new AssertionError(e);
                }
            };
        } catch(NoSuchMethodException e) {
            throw new AssertionError(e);
        }
        INTERNAL_BEAN_CLASS = c;
    }
    public static boolean convertToBoolean(String input) {
        return input != null && !input.isBlank() && input.equals("true");
    }
    public static void handleError(RoutingContext context, int code, String message) {
        if (context == null || context.response().ended()) return;
        context.response()
                .setStatusCode(code)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject().put("code", code).put("message", message).toBuffer());
    }
    public static JsonObject playlistToJson(AudioPlayerManager playerManager, AudioPlaylist playlist) {
        JsonObject jsonObject = new JsonObject()
                .put("className", playlist.getClass().getName())
                .put("name", playlist.getName())
                .put("creator", playlist.getCreator())
                .put("artworkUrl", playlist.getImage())
                .put("type", playlist.getType())
                .put("uri", playlist.getURI())
                .put("size", playlist.getSize());
        List<AudioTrack> playlistTracks = playlist.getTracks();
        JsonArray tracks = new JsonArray();
        for (int i = 0; i < playlistTracks.size(); i++) tracks.add(trackToJson(playerManager, playlistTracks.get(i)).put("index", i));
        jsonObject.put("tracks", tracks);
        AudioTrack selectedTrack = playlist.getSelectedTrack();
        if (selectedTrack != null) jsonObject.put("selectedTrack", trackToJson(playerManager, selectedTrack).put("index", playlistTracks.indexOf(selectedTrack)));
        return jsonObject;
    }
    public static JsonObject trackToJson(AudioPlayerManager playerManager, AudioTrack track) {
        return new JsonObject().put("encoded", encodeTrack(playerManager, track)).put("info", trackInfoToJson(track));
    }
    public static JsonObject trackInfoToJson(AudioTrack track) {
        AudioTrackInfo trackInfo = track.getInfo();
        JsonArray artists = new JsonArray();
        for (AudioTrackAuthorInfo authorInfo : track.getInfo().artists) artists.add(new JsonObject().put("name", authorInfo.name).put("uri", authorInfo.uri));
        return new JsonObject()
                .put("className", track.getClass().getName()).put("title", trackInfo.title).put("artists", artists)
                .put("length", trackInfo.length).put("identifier", trackInfo.identifier).put("uri", trackInfo.uri)
                .put("stream", trackInfo.isStream).put("seekable", track.isSeekable()).put("position", track.getPosition())
                .put("explicit", trackInfo.explicit).put("artworkUrl", trackInfo.artworkUrl).put("isrc", trackInfo.isrc)
                .put("sourceName", track.getSourceManager().getSourceName());
    }
    public static AudioTrack decodeTrack(AudioPlayerManager playerManager, String base64) {
        if (playerManager == null || base64 == null || base64.isBlank() || base64.length() > MAX_ENCODED_TRACK_LENGTH) return null;
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            DecodedTrackHolder decoded = playerManager.decodeTrack(new MessageInput(new ByteArrayInputStream(encoded)));
            return decoded == null ? null : decoded.decodedTrack;
        } catch (IllegalArgumentException | IOException exception) {
            log.debug("Rejected malformed encoded track: {}", exception.getMessage());
            return null;
        }
    }
    public static String encodeTrack(AudioPlayerManager playerManager, AudioTrack track) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try { playerManager.encodeTrack(new MessageOutput(outputStream), track); }
        catch(IOException e) { throw new AssertionError(e); }
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
    public static JsonObject encodeThrowable(Throwable throwable) {
        if (throwable == null) return new JsonObject().put("type", "UnknownError").put("message", "Unknown error");
        String message = throwable.getMessage();
        if (message != null && message.length() > 512) message = message.substring(0, 512);
        return new JsonObject().put("type", throwable.getClass().getSimpleName()).put("message", message);
    }
    public static JsonObject nodeStats(Main main, String userId) {
        JsonObject root = new JsonObject();
        Collection<PlayerSession> players = main.getPlayerManager().getOrCreate(userId).getAll();
        int[] playerStats = players.stream().reduce(new int[2], (ints, player) -> {
            ints[0]++; if(player.isPlaying()) ints[1]++; return ints;
        }, (ints1, ints2) -> { ints1[0] += ints2[0]; ints1[1] += ints2[1]; return ints1; });
        root.put("players", new JsonObject().put("total", playerStats[0]).put("playing", playerStats[1]));
        var runtime = ManagementFactory.getRuntimeMXBean();
        var version = Runtime.version();
        root.put("runtime", new JsonObject()
                .put("uptime", runtime.getUptime()).put("pid", runtime.getPid())
                .put("managementSpecVersion", runtime.getManagementSpecVersion()).put("name", runtime.getName())
                .put("vm", new JsonObject().put("name", runtime.getVmName()).put("vendor", runtime.getVmVendor()).put("version", runtime.getVmVersion()))
                .put("spec", new JsonObject().put("name", runtime.getSpecName()).put("vendor", runtime.getSpecVendor()).put("version", runtime.getSpecVersion()))
                .put("version", new JsonObject().put("feature", version.feature()).put("interim", version.interim()).put("update", version.update())
                        .put("patch", version.patch()).put("pre", version.pre().orElse(null)).put("build", version.build().orElse(null)).put("optional", version.optional().orElse(null))));
        var os = ManagementFactory.getOperatingSystemMXBean();
        root.put("os", new JsonObject().put("processors", os.getAvailableProcessors()).put("name", os.getName()).put("arch", os.getArch()).put("version", os.getVersion()));
        if(INTERNAL_BEAN_CLASS != null && INTERNAL_BEAN_CLASS.isInstance(os)) {
            var internalBean = (com.sun.management.OperatingSystemMXBean) os;
            root.put("cpu", new JsonObject().put("lavanode", internalBean.getProcessCpuLoad()).put("system", systemCpuLoad(internalBean)));
        } else root.putNull("cpu");
        var classLoading = ManagementFactory.getClassLoadingMXBean();
        root.put("classLoading", new JsonObject().put("loaded", classLoading.getLoadedClassCount()).put("totalLoaded", classLoading.getTotalLoadedClassCount()).put("unloaded", classLoading.getUnloadedClassCount()));
        var thread = ManagementFactory.getThreadMXBean();
        root.put("thread", new JsonObject().put("running", thread.getThreadCount()).put("daemon", thread.getDaemonThreadCount()).put("peak", thread.getPeakThreadCount()).put("totalStarted", thread.getTotalStartedThreadCount()));
        var compilation = ManagementFactory.getCompilationMXBean();
        root.put("compilation", new JsonObject().put("name", compilation.getName()).put("totalTime", compilation.getTotalCompilationTime()));
        var memoryBean = ManagementFactory.getMemoryMXBean();
        root.put("memory", new JsonObject().put("pendingFinalization", memoryBean.getObjectPendingFinalizationCount()).put("heap", toJson(memoryBean.getHeapMemoryUsage())).put("nonHeap", toJson(memoryBean.getNonHeapMemoryUsage())));
        var gc = ManagementFactory.getGarbageCollectorMXBeans();
        root.put("gc", gc.stream().map(bean -> new JsonObject().put("name", bean.getName()).put("collectionCount", bean.getCollectionCount()).put("collectionTime", bean.getCollectionTime())
                .put("pools", Arrays.stream(bean.getMemoryPoolNames()).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll))).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));
        var pools = ManagementFactory.getMemoryPoolMXBeans();
        root.put("memoryPools", pools.stream().map(bean -> {
            var json = new JsonObject().put("name", bean.getName()).put("type", bean.getType().name()).put("collectionUsage", toJson(bean.getCollectionUsage()))
                    .putNull("collectionUsageThreshold").putNull("collectionUsageThresholdCount").put("peakUsage", toJson(bean.getPeakUsage())).put("usage", toJson(bean.getUsage()))
                    .putNull("usageThreshold").putNull("usageThresholdCount").put("managers", Arrays.stream(bean.getMemoryManagerNames()).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));
            if(bean.isCollectionUsageThresholdSupported()) json.put("collectionUsageThreshold", bean.getCollectionUsageThreshold()).put("collectionUsageThresholdCount", bean.getCollectionUsageThresholdCount());
            if(bean.isUsageThresholdSupported()) json.put("usageThreshold", bean.getUsageThreshold()).put("usageThresholdCount", bean.getUsageThreshold());
            return json;
        }).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));
        var managers = ManagementFactory.getMemoryManagerMXBeans();
        root.put("memoryManagers", managers.stream().map(bean -> new JsonObject().put("name", bean.getName()).put("pools", Arrays.stream(bean.getMemoryPoolNames()).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll))).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));
        root.put("frameStats", players.stream().reduce(new JsonArray(), (array, player) -> {
            var counter = player.getFrameLossCounter();
            if(counter.isDataUsable()) {
                int totalSent = counter.lastMinuteSuccess().sum(), totalLost = counter.lastMinuteLoss().sum();
                int totalDeficit = PlayerFrameLossCounter.EXPECTED_PACKET_COUNT_PER_MIN - (totalSent + totalLost);
                array.add(new JsonObject().put("user", player.getUserId()).put("guild", player.getId()).put("success", totalSent).put("loss", totalLost).put("deficit", totalDeficit));
            }
            return array;
        }, JsonArray::addAll));
        return root;
    }
    public static JsonObject mediaToJson(MediaConnection connection) {
        JsonObject json = new JsonObject().put("version", connection.getClient().getGatewayVersion().name())
                .put("options", new JsonObject().put("deaf", connection.getOptions().isDeafened()).put("dave", connection.getOptions().isEnableDAVE())
                        .put("daveLogsink", connection.getOptions().isEnableDAVELogSink()).put("verifyWSSHostname", connection.getOptions().isVerifyWSSHostname())
                        .put("highPacketPriority", connection.getOptions().isHighPacketPriority()).put("experimental", connection.getOptions().isExperimental())
                        .put("enableWSSPortOverride", connection.getOptions().isEnableWSSPortOverride()));
        MediaGatewayConnection gateway = connection.getGatewayConnection();
        if (gateway != null) json.put("gateway", new JsonObject().put("open", gateway.isOpen()).put("ping", gateway.getPing()));
        VoiceServerInfo serverInfo = connection.getVoiceServerInfo();
        if (serverInfo != null) json.put("voiceInfo", new JsonObject().put("channelId", Long.toString(serverInfo.getChannelId())).put("endpoint", serverInfo.getEndpoint()).put("token", serverInfo.getToken()).put("sessionId", serverInfo.getSessionId()));
        return json;
    }
    private static JsonObject toJson(MemoryUsage usage) {
        if(usage == null) return null;
        return new JsonObject().put("init", usage.getInit()).put("used", usage.getUsed()).put("committed", usage.getCommitted()).put("max", usage.getMax());
    }
    @SuppressWarnings("deprecation")
    private static double systemCpuLoad(Object bean) { return ((com.sun.management.OperatingSystemMXBean)bean).getSystemCpuLoad(); }
    public static void updateFilters(PlayerSession playerSession, JsonObject json) {
        PlayerFilters filterConfig = playerSession.getPlayerFilters();
        if(json.containsKey("channelmix")) {
            var c = json.getJsonObject("channelmix"); var f = filterConfig.channelMix();
            f.setLeftToLeft(c.getFloat("leftToLeft", f.leftToLeft())); f.setLeftToRight(c.getFloat("leftToRight", f.leftToRight()));
            f.setRightToLeft(c.getFloat("rightToLeft", f.rightToLeft())); f.setRightToRight(c.getFloat("rightToRight", f.rightToRight()));
        }
        if(json.containsKey("echo")) { var c=json.getJsonObject("echo"); var f=filterConfig.echo(); f.setEchoLength(c.getFloat("echoLength",f.echoLength())); f.setDecay(c.getFloat("decay",f.decay())); }
        if(json.containsKey("equalizer")) { var a=json.getJsonObject("equalizer").getJsonArray("bands"); var f=filterConfig.equalizer(); for(var i=0;i<a.size();i++){var b=a.getJsonObject(i);f.setBand(b.getInteger("band"),b.getFloat("gain"));} }
        if(json.containsKey("highpass")) { var c=json.getJsonObject("highpass"); var f=filterConfig.highpass(); f.setBoostFactor(c.getFloat("boostFactor",f.boostFactor())); f.setCutoffFrequency(c.getInteger("cutoffFrequency",f.cutoffFrequency())); }
        if(json.containsKey("karaoke")) { var c=json.getJsonObject("karaoke"); var f=filterConfig.karaoke(); f.setLevel(c.getFloat("level",f.level())); f.setMonoLevel(c.getFloat("monoLevel",f.monoLevel())); f.setFilterBand(c.getFloat("filterBand",f.filterBand())); f.setFilterWidth(c.getFloat("filterWidth",f.filterWidth())); }
        if(json.containsKey("lowpass")) { var c=json.getJsonObject("lowpass"); var f=filterConfig.lowPass(); f.setSmoothing(c.getFloat("smoothing",f.smoothing())); }
        if(json.containsKey("normalization")) { var c=json.getJsonObject("normalization"); var f=filterConfig.normalization(); f.setMaxAmplitude(c.getFloat("maxAplitude",f.maxAmplitude())); f.setAdaptive(c.getBoolean("adaptive",f.adaptive())); }
        if(json.containsKey("rotation")) { var c=json.getJsonObject("rotation"); var f=filterConfig.rotation(); f.setRotationHz(c.getFloat("rotationHz",f.rotationHz())); }
        if(json.containsKey("timescale")) { var c=json.getJsonObject("timescale"); var f=filterConfig.timescale(); f.setSpeed(c.getFloat("speed",f.speed())); f.setPitch(c.getFloat("pitch",f.pitch())); f.setRate(c.getFloat("rate",f.rate())); }
        if(json.containsKey("tremolo")) { var c=json.getJsonObject("tremolo"); var f=filterConfig.tremolo(); f.setFrequency(c.getFloat("frequency",f.frequency())); f.setDepth(c.getFloat("depth",f.depth())); }
        if(json.containsKey("vibrato")) { var c=json.getJsonObject("vibrato"); var f=filterConfig.vibrato(); f.setFrequency(c.getFloat("frequency",f.frequency())); f.setDepth(c.getFloat("depth",f.depth())); }
        if(json.containsKey("volume")) {
            var f=filterConfig.volume(); var val=json.getValue("volume"); float volume;
            if(val instanceof JsonObject) volume=((JsonObject)val).getFloat("volume",f.volume());
            else if(val instanceof Number) volume=((Number)val).floatValue();
            else throw new IllegalArgumentException("Invalid volume value: " + val);
            f.setVolume(volume);
        }
        playerSession.setFilterFactory(filterConfig.factory());
    }
}
