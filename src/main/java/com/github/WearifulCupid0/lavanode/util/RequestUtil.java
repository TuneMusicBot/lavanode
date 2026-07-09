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
import java.util.function.Function;

public class RequestUtil {
    private static final Logger log = LoggerFactory.getLogger(RequestUtil.class);

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
        context.response()
                .setStatusCode(code).setStatusMessage(message)
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                        .put("code", code)
                        .put("message", message)
                        .toBuffer()
                );
    }

    public static JsonObject playlistToJson(AudioPlayerManager playerManager, AudioPlaylist playlist) {
        JsonObject jsonObject = new JsonObject()
                .put("className", playlist.getClass().getName())
                .put("name", playlist.getName())
                .put("creator", playlist.getCreator())
                .put("artworkUrl", playlist.getImage())
                .put("type", playlist.getType())
                .put("uri", playlist.getURI());

        List<AudioTrack> playlistTracks = playlist.getTracks();
        JsonArray tracks = new JsonArray();

        for (int i = 0; i < playlistTracks.size(); i++)
            tracks.add(trackToJson(playerManager, playlistTracks.get(i)).put("index", i));

        jsonObject.put("tracks", tracks);

        AudioTrack selectedTrack = playlist.getSelectedTrack();
        if (selectedTrack != null)
            jsonObject.put("selectedTrack", trackToJson(playerManager, selectedTrack).put("index", playlistTracks.indexOf(selectedTrack)));


        return jsonObject;
    }

    public static JsonObject trackToJson(AudioPlayerManager playerManager, AudioTrack track) {
        return new JsonObject()
                .put("encoded", encodeTrack(playerManager, track))
                .put("info", trackInfoToJson(track));
    }

    public static JsonObject trackInfoToJson(AudioTrack track) {
        AudioTrackInfo trackInfo = track.getInfo();

        JsonArray artists = new JsonArray();

        for (AudioTrackAuthorInfo authorInfo : track.getInfo().artists)
            artists.add(new JsonObject().put("name", authorInfo.name).put("uri", authorInfo.uri));

        return new JsonObject()
                .put("className", track.getClass().getName())
                .put("title", trackInfo.title)
                .put("artists", artists)
                .put("length", trackInfo.length)
                .put("identifier", trackInfo.identifier)
                .put("uri", trackInfo.uri)
                .put("stream", trackInfo.isStream)
                .put("seekable", track.isSeekable())
                .put("position", track.getPosition())
                .put("explicit", trackInfo.explicit)
                .put("artworkUrl", trackInfo.artworkUrl)
                .put("isrc", trackInfo.isrc)
                .put("sourceName", track.getSourceManager().getSourceName());
    }

    public static AudioTrack decodeTrack(AudioPlayerManager playerManager, String base64) {
        try {
            DecodedTrackHolder decoded = playerManager.decodeTrack(new MessageInput(new ByteArrayInputStream(
                    Base64.getDecoder().decode(base64)
            )));
            return decoded == null ? null : decoded.decodedTrack;
        } catch(IOException e) {
            throw new AssertionError(e);
        }
    }

    public static String encodeTrack(AudioPlayerManager playerManager, AudioTrack track) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            playerManager.encodeTrack(new MessageOutput(outputStream), track);
        } catch(IOException e) {
            throw new AssertionError(e);
        }
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    public static JsonObject encodeThrowable(Throwable throwable) {
        JsonObject json = new JsonObject()
                .put("class", throwable.getClass().getName())
                .put("message", throwable.getMessage())
                .put("suppressed", encodeArray(throwable.getSuppressed(), RequestUtil::encodeThrowable))
                .put("stack", encodeArray(throwable.getStackTrace(), RequestUtil::encodeStackFrame));
        Throwable cause = throwable.getCause();

        if(cause != null) {
            json.put("cause", encodeThrowable(cause));
        } else {
            json.putNull("cause");
        }

        return json;
    }

    private static JsonObject encodeStackFrame(StackTraceElement element) {
        return new JsonObject()
                .put("classLoader", element.getClassLoaderName())
                .put("moduleName", element.getModuleName())
                .put("moduleVersion", element.getModuleVersion())
                .put("className", element.getClassName())
                .put("methodName", element.getMethodName())
                .put("fileName", element.getFileName())
                .put("lineNumber", element.getLineNumber() < 0 ? null : element.getLineNumber())
                .put("pretty", element.toString());
    }

    private static <T> JsonArray encodeArray(T[] array, Function<T, JsonObject> serializeFunction) {
        JsonArray json = new JsonArray();
        for(T t : array) {
            json.add(serializeFunction.apply(t));
        }
        return json;
    }

    public static JsonObject nodeStats(Main main, String userId) {
        JsonObject root = new JsonObject();
        Collection<PlayerSession> players = main.getPlayerManager().getOrCreate(userId).getAll();

        int[] playerStats = players.stream().reduce(new int[2], (ints, player) -> {
            ints[0]++;
            if(player.isPlaying()) ints[1]++;
            return ints;
        }, (ints1, ints2) -> {
            ints1[0] += ints2[0];
            ints1[1] += ints2[1];
            return ints1;
        });
        root.put("players", new JsonObject()
                .put("total", playerStats[0])
                .put("playing", playerStats[1]));

        var runtime = ManagementFactory.getRuntimeMXBean();
        var version = Runtime.version();
        root.put("runtime", new JsonObject()
                .put("uptime", runtime.getUptime())
                .put("pid", runtime.getPid())
                .put("managementSpecVersion", runtime.getManagementSpecVersion())
                .put("name", runtime.getName())
                .put("vm", new JsonObject()
                        .put("name", runtime.getVmName())
                        .put("vendor", runtime.getVmVendor())
                        .put("version", runtime.getVmVersion())
                )
                .put("spec", new JsonObject()
                        .put("name", runtime.getSpecName())
                        .put("vendor", runtime.getSpecVendor())
                        .put("version", runtime.getSpecVersion())
                )
                .put("version", new JsonObject()
                        .put("feature", version.feature())
                        .put("interim", version.interim())
                        .put("update", version.update())
                        .put("patch", version.patch())
                        .put("pre", version.pre().orElse(null))
                        .put("build", version.build().orElse(null))
                        .put("optional", version.optional().orElse(null))
                )
        );

        var os = ManagementFactory.getOperatingSystemMXBean();
        root.put("os", new JsonObject()
                .put("processors", os.getAvailableProcessors())
                .put("name", os.getName())
                .put("arch", os.getArch())
                .put("version", os.getVersion())
        );

        //INTERNAL_BEAN_CLASS is a Class<?> object to the com.sun.management.OperatingSystemMXBean class
        if(INTERNAL_BEAN_CLASS != null && INTERNAL_BEAN_CLASS.isInstance(os)) {
            var internalBean = (com.sun.management.OperatingSystemMXBean) os;
            root.put("cpu", new JsonObject()
                    .put("lavanode", internalBean.getProcessCpuLoad())
                    .put("system", systemCpuLoad(internalBean))
            );
        } else {
            root.putNull("cpu");
        }

        var classLoading = ManagementFactory.getClassLoadingMXBean();
        root.put("classLoading", new JsonObject()
                .put("loaded", classLoading.getLoadedClassCount())
                .put("totalLoaded", classLoading.getTotalLoadedClassCount())
                .put("unloaded", classLoading.getUnloadedClassCount())
        );

        var thread = ManagementFactory.getThreadMXBean();
        root.put("thread", new JsonObject()
                .put("running", thread.getThreadCount())
                .put("daemon", thread.getDaemonThreadCount())
                .put("peak", thread.getPeakThreadCount())
                .put("totalStarted", thread.getTotalStartedThreadCount())
        );

        var compilation = ManagementFactory.getCompilationMXBean();
        root.put("compilation", new JsonObject()
                .put("name", compilation.getName())
                .put("totalTime", compilation.getTotalCompilationTime())
        );

        var memoryBean = ManagementFactory.getMemoryMXBean();
        root.put("memory", new JsonObject()
                .put("pendingFinalization", memoryBean.getObjectPendingFinalizationCount())
                .put("heap", toJson(memoryBean.getHeapMemoryUsage()))
                .put("nonHeap", toJson(memoryBean.getNonHeapMemoryUsage())));

        var gc = ManagementFactory.getGarbageCollectorMXBeans();
        root.put("gc", gc.stream().map(bean -> new JsonObject()
                .put("name", bean.getName())
                .put("collectionCount", bean.getCollectionCount())
                .put("collectionTime", bean.getCollectionTime())
                .put("pools", Arrays.stream(bean.getMemoryPoolNames())
                        .reduce(new JsonArray(), JsonArray::add, JsonArray::addAll))
        ).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));

        var pools = ManagementFactory.getMemoryPoolMXBeans();
        root.put("memoryPools", pools.stream().map(bean -> {
            var json = new JsonObject()
                    .put("name", bean.getName())
                    .put("type", bean.getType().name())
                    .put("collectionUsage", toJson(bean.getCollectionUsage()))
                    .putNull("collectionUsageThreshold")
                    .putNull("collectionUsageThresholdCount")
                    .put("peakUsage", toJson(bean.getPeakUsage()))
                    .put("usage", toJson(bean.getUsage()))
                    .putNull("usageThreshold")
                    .putNull("usageThresholdCount")
                    .put("managers", Arrays.stream(bean.getMemoryManagerNames())
                            .reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));
            if(bean.isCollectionUsageThresholdSupported()) {
                json.put("collectionUsageThreshold", bean.getCollectionUsageThreshold())
                        .put("collectionUsageThresholdCount", bean.getCollectionUsageThresholdCount());
            }
            if(bean.isUsageThresholdSupported()) {
                json.put("usageThreshold", bean.getUsageThreshold())
                        .put("usageThresholdCount", bean.getUsageThreshold());
            }
            return json;
        }).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));

        var managers = ManagementFactory.getMemoryManagerMXBeans();
        root.put("memoryManagers", managers.stream().map(bean -> new JsonObject()
                .put("name", bean.getName())
                .put("pools", Arrays.stream(bean.getMemoryPoolNames())
                        .reduce(new JsonArray(), JsonArray::add, JsonArray::addAll))
        ).reduce(new JsonArray(), JsonArray::add, JsonArray::addAll));

        root.put("frameStats", players.stream().reduce(new JsonArray(), (array, player) -> {
            var counter = player.getFrameLossCounter();
            if(counter.isDataUsable()) {
                int totalSent = counter.lastMinuteSuccess().sum();
                int totalLost =  counter.lastMinuteLoss().sum();

                int totalDeficit = PlayerFrameLossCounter.EXPECTED_PACKET_COUNT_PER_MIN
                        - (totalSent + totalLost);
                array.add(new JsonObject()
                        .put("user", player.getUserId())
                        .put("guild", player.getId())
                        .put("success", totalSent)
                        .put("loss", totalLost)
                        .put("deficit", totalDeficit)
                );
            }
            return array;
        }, JsonArray::addAll));

        return root;
    }

    private static JsonObject toJson(MemoryUsage usage) {
        if(usage == null) return null;
        var json = new JsonObject();
        json.put("init", usage.getInit());
        json.put("used", usage.getUsed());
        json.put("committed", usage.getCommitted());
        json.put("max", usage.getMax());
        return json;
    }

    @SuppressWarnings("deprecation")
    private static double systemCpuLoad(Object bean) {
        return ((com.sun.management.OperatingSystemMXBean)bean).getSystemCpuLoad();
    }

    public static void updateFilters(PlayerSession playerSession, JsonObject json) {
        PlayerFilters filterConfig = playerSession.getPlayerFilters();

        if(json.containsKey("channelmix")) {
            var channelMix = json.getJsonObject("channelmix");
            var channelMixConfig = filterConfig.channelMix();
            channelMixConfig.setLeftToLeft(channelMix.getFloat("leftToLeft", channelMixConfig.leftToLeft()));
            channelMixConfig.setLeftToRight(channelMix.getFloat("leftToRight", channelMixConfig.leftToRight()));
            channelMixConfig.setRightToLeft(channelMix.getFloat("rightToLeft", channelMixConfig.rightToLeft()));
            channelMixConfig.setRightToRight(channelMix.getFloat("rightToRight", channelMixConfig.rightToRight()));
        }

        if(json.containsKey("echo")) {
            var echo = json.getJsonObject("echo");
            var echoConfig = filterConfig.echo();
            echoConfig.setEchoLength(echo.getFloat("echoLength", echoConfig.echoLength()));
            echoConfig.setDecay(echo.getFloat("decay", echoConfig.decay()));
        }

        if(json.containsKey("equalizer")) {
            var array = json.getJsonObject("equalizer").getJsonArray("bands");
            var equalizerConfig = filterConfig.equalizer();
            for(var i = 0; i < array.size(); i++) {
                var band = array.getJsonObject(i);
                equalizerConfig.setBand(band.getInteger("band"), band.getFloat("gain"));
            }
        }

        if(json.containsKey("highpass")) {
            var highpass = json.getJsonObject("highpass");
            var highpassConfig = filterConfig.highpass();
            highpassConfig.setBoostFactor(highpass.getFloat("boostFactor", highpassConfig.boostFactor()));
            highpassConfig.setCutoffFrequency(highpass.getInteger("cutoffFrequency", highpassConfig.cutoffFrequency()));
        }

        if(json.containsKey("karaoke")) {
            var karaoke = json.getJsonObject("karaoke");
            var karaokeConfig = filterConfig.karaoke();
            karaokeConfig.setLevel(karaoke.getFloat("level", karaokeConfig.level()));
            karaokeConfig.setMonoLevel(karaoke.getFloat("monoLevel", karaokeConfig.monoLevel()));
            karaokeConfig.setFilterBand(karaoke.getFloat("filterBand", karaokeConfig.filterBand()));
            karaokeConfig.setFilterWidth(karaoke.getFloat("filterWidth", karaokeConfig.filterWidth()));
        }

        if(json.containsKey("lowpass")) {
            var lowPass = json.getJsonObject("lowpass");
            var lowPassConfig = filterConfig.lowPass();
            lowPassConfig.setSmoothing(lowPass.getFloat("smoothing", lowPassConfig.smoothing()));
        }

        if(json.containsKey("normalization")) {
            var normalization = json.getJsonObject("normalization");
            var normalizationConfig = filterConfig.normalization();
            normalizationConfig.setMaxAmplitude(normalization.getFloat("maxAplitude", normalizationConfig.maxAmplitude()));
            normalizationConfig.setAdaptive(normalization.getBoolean("adaptive", normalizationConfig.adaptive()));
        }

        if(json.containsKey("rotation")) {
            var rotation = json.getJsonObject("rotation");
            var rotationConfig = filterConfig.rotation();
            rotationConfig.setRotationHz(rotation.getFloat("rotationHz", rotationConfig.rotationHz()));
        }

        if(json.containsKey("timescale")) {
            var timescale = json.getJsonObject("timescale");
            var timescaleConfig = filterConfig.timescale();
            timescaleConfig.setSpeed(timescale.getFloat("speed", timescaleConfig.speed()));
            timescaleConfig.setPitch(timescale.getFloat("pitch", timescaleConfig.pitch()));
            timescaleConfig.setRate(timescale.getFloat("rate", timescaleConfig.rate()));
        }

        if(json.containsKey("tremolo")) {
            var tremolo = json.getJsonObject("tremolo");
            var tremoloConfig = filterConfig.tremolo();
            tremoloConfig.setFrequency(tremolo.getFloat("frequency", tremoloConfig.frequency()));
            tremoloConfig.setDepth(tremolo.getFloat("depth", tremoloConfig.depth()));
        }

        if(json.containsKey("vibrato")) {
            var vibrato = json.getJsonObject("vibrato");
            var vibratoConfig = filterConfig.vibrato();
            vibratoConfig.setFrequency(vibrato.getFloat("frequency", vibratoConfig.frequency()));
            vibratoConfig.setDepth(vibrato.getFloat("depth", vibratoConfig.depth()));
        }

        if(json.containsKey("volume")) {
            var volumeConfig = filterConfig.volume();
            var val = json.getValue("volume");
            float volume;
            if(val instanceof JsonObject) {
                volume = ((JsonObject) val).getFloat("volume", volumeConfig.volume());
            } else if(val instanceof Number) {
                volume = ((Number) val).floatValue();
            } else {
                throw new IllegalArgumentException("Invalid volume value: " + val);
            }
            volumeConfig.setVolume(volume);
        }

        playerSession.setFilterFactory(filterConfig.factory());
    }
}
