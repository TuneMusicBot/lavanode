package com.github.WearifulCupid0.lavanode;

import com.github.WearifulCupid0.lavanode.config.KoeConfig;
import com.github.WearifulCupid0.lavanode.config.AudioSourceConfig;
import com.github.WearifulCupid0.lavanode.player.PlayerManager;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.PlayerSessionManager;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.crossfade.CrossfadeFrameProvider;
import com.github.WearifulCupid0.lavanode.player.frame.gapless.GaplessFrameProvider;
import com.github.WearifulCupid0.lavanode.server.RestHandler;
import com.github.WearifulCupid0.lavanode.server.websocket.PlayerUpdateBroadcaster;
import com.github.WearifulCupid0.lavanode.server.websocket.WebsocketManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import com.sedmelluq.lavaplayer.extensions.cache.AudioLoadCache;
import com.sedmelluq.lavaplayer.extensions.cache.CachingAudioPlayerManager;
import com.sedmelluq.lavaplayer.extensions.cache.RedisAudioLoadCache;
import com.sedmelluq.lavaplayer.extensions.cache.policy.CachePolicy;
import com.sedmelluq.lavaplayer.extensions.cache.policy.CachePolicyBuilder;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import io.vertx.core.Vertx;
import moe.kyokobot.koe.Koe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public class Main {
    public static final long TRACK_STUCK_THRESHOLD = 15_000;

    private final WebsocketManager websocketManager;

    private final AudioPlayerManager audioPlayerManager;
    private final AudioPlayerManager pcmAudioPlayerManager;

    private final Vertx vertx = Vertx.vertx();
    private final Koe koe;
    private final String tokenSecret;
    private final PlayerManager playerManager;
    private final PlayerUpdateBroadcaster updateBroadcaster;

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public Main() {
        String tokenSecret = SourceTools.getPropertyOrEnv("TOKEN_SECRET");
        if (SourceTools.isBlank(tokenSecret)) {
            throw new RuntimeException("Token secret is not set! Stopping application");
        }

        this.tokenSecret = tokenSecret;

        log.debug("Starting audio source manager");

        String redisUrl = SourceTools.getPropertyOrEnv("REDIS_URL");
        boolean useRedis = !SourceTools.isBlank(redisUrl) && redisUrl.startsWith("redis://");

        audioPlayerManager = useRedis ? new CachingAudioPlayerManager() : new DefaultAudioPlayerManager();
        pcmAudioPlayerManager = useRedis ? new CachingAudioPlayerManager() : new DefaultAudioPlayerManager();

        if (useRedis) {
            log.info("Setting up redis cache for load tracks...");

            CachePolicy cachePolicy =
                    new CachePolicyBuilder()
                            .setNoMatchesTtl(Duration.ofSeconds(45))
                            .setPlaylistTtl(Duration.ofHours(1))
                            .setSearchTtl(Duration.ofMinutes(15))
                            .setTrackTtl(Duration.ofHours(12))
                            .build();

            AudioLoadCache redisCache = new RedisAudioLoadCache(redisUrl, cachePolicy);
            ((CachingAudioPlayerManager) audioPlayerManager).setAudioLoadCache(redisCache);
            ((CachingAudioPlayerManager) pcmAudioPlayerManager).setAudioLoadCache(redisCache);
        } else {
            log.info("Redis cache not configured, skipping...");
        }

        log.debug("Using non allocating frame buffer");
        audioPlayerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        pcmAudioPlayerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);

        log.debug("Setting track stuck threshold to 15s");
        audioPlayerManager.setTrackStuckThreshold(TRACK_STUCK_THRESHOLD);
        pcmAudioPlayerManager.setTrackStuckThreshold(TRACK_STUCK_THRESHOLD);

        log.debug("Setting fram buffer duration to 1.5s");
        audioPlayerManager.setFrameBufferDuration(1500);
        pcmAudioPlayerManager.setFrameBufferDuration(1500);

        String highQuality = SourceTools.getPropertyOrEnv("HIGH_QUALITY");
        if (!SourceTools.isBlank(highQuality)) {
            log.info("Oh, high quality node! Setting up max encoding and resampling quality");

            audioPlayerManager.getConfiguration().setOpusEncodingQuality(10);
            audioPlayerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);

            pcmAudioPlayerManager.getConfiguration().setOpusEncodingQuality(10);
            pcmAudioPlayerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);

            log.debug("Setting frame duration to 400ms");
            audioPlayerManager.setFrameBufferDuration(400);
            pcmAudioPlayerManager.setFrameBufferDuration(400);
        } else {
            log.info("Setting up medium encoding and resampling quality");

            audioPlayerManager.getConfiguration().setOpusEncodingQuality(6);
            audioPlayerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM);

            pcmAudioPlayerManager.getConfiguration().setOpusEncodingQuality(6);
            pcmAudioPlayerManager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.MEDIUM);

            log.debug("Setting frame duration to 1500ms");
            audioPlayerManager.setFrameBufferDuration(1_500);
            pcmAudioPlayerManager.setFrameBufferDuration(1_500);
        }

        log.debug("Disabling seek ghosting");
        audioPlayerManager.setUseSeekGhosting(false);
        pcmAudioPlayerManager.setUseSeekGhosting(false);

        log.debug("Enabling filter hot swap");
        audioPlayerManager.getConfiguration().setFilterHotSwapEnabled(true);
        pcmAudioPlayerManager.getConfiguration().setFilterHotSwapEnabled(true);

        log.debug("Loading native audio source managers...");
        AudioSourceConfig.loadNativeSourceManagers();

        log.debug("Loading third party/non-native source managers...");
        AudioSourceConfig.loadThirdPartySourceManager(audioPlayerManager);

        log.debug("Loading main source managers...");
        AudioSourceConfig.loadMainSourceManager();

        AudioSourceConfig.registerToSourceManager(audioPlayerManager);
        AudioSourceConfig.registerToSourceManager(pcmAudioPlayerManager);

        log.debug("Setting up koe voice library");
        koe = KoeConfig.createKoe();

        log.debug("Setting up PCM Audio player manager own configs...");
        pcmAudioPlayerManager.setPlayerCleanupThreshold(Long.MAX_VALUE);
        pcmAudioPlayerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_BE);

        playerManager = new PlayerManager(this);

        log.debug("Creating websocket manager before starting webserver...");
        websocketManager = new WebsocketManager(this);

        log.debug("Setting up player update broadcaster...");
        updateBroadcaster = new PlayerUpdateBroadcaster(
                vertx,
                playerManager,
                audioPlayerManager,
                1_000
        );

        log.debug("Starting web server...");
        RestHandler.setup(this);

        vertx.setPeriodic(500, timerId -> {
            for (PlayerSessionManager sessionManager : playerManager.getAll()) {
                for (PlayerSession session : sessionManager.getAll()) {
                    PlayerFrameProvider frameProvider = session.getFrameProvider();
                    if (frameProvider instanceof GaplessFrameProvider)
                        ((GaplessFrameProvider) frameProvider).tick();
                    else if (frameProvider instanceof CrossfadeFrameProvider)
                        ((CrossfadeFrameProvider) frameProvider).tick();
                }
            }
        });
    }

    public PlayerUpdateBroadcaster getUpdateBroadcaster() { return updateBroadcaster; }

    public WebsocketManager getWebsocketManager() { return websocketManager; }

    public String getTokenSecret() {
        return tokenSecret;
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public AudioPlayerManager getPcmAudioPlayerManager() { return pcmAudioPlayerManager; }

    public Koe getKoe() {
        return koe;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public Vertx getVertx() {
        return vertx;
    }

    public static void main(String[] args) {
        log.info("Starting LavaNode...");

        new Main();
    }
}
