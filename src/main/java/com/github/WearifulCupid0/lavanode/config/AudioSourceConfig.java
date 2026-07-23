package com.github.WearifulCupid0.lavanode.config;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.applemusic.AppleMusicAudioSourceManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.deezer.DeezerAudioSourceManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.pandora.PandoraAudioSourceManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.source.DefaultThirdPartyAudioTrackResolver;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.spotify.SpotifyAudioSourceManager;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.tidal.TidalAudioSourceManager;
import com.sedmelluq.lavaplayer.source.audiomack.AudiomackAudioSourceManager;
import com.sedmelluq.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.lavaplayer.source.bandlab.BandlabAudioSourceManager;
import com.sedmelluq.lavaplayer.source.clyp.ClypAudioSourceManager;
import com.sedmelluq.lavaplayer.source.iheart.iHeartAudioSourceManager;
import com.sedmelluq.lavaplayer.source.jamendo.JamendoAudioSourceManager;
import com.sedmelluq.lavaplayer.source.mixcloud.MixcloudAudioSourceManager;
import com.sedmelluq.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.lavaplayer.source.ocremix.OcremixAudioSourceManager;
import com.sedmelluq.lavaplayer.source.odysee.OdyseeAudioSourceManager;
import com.sedmelluq.lavaplayer.source.reverbnation.ReverbnationAudioSourceManager;
import com.sedmelluq.lavaplayer.source.rumble.RumbleAudioSourceManager;
import com.sedmelluq.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.lavaplayer.source.soundgasm.SoundgasmAudioSourceManager;
import com.sedmelluq.lavaplayer.source.streamable.StreamableAudioSourceManager;
import com.sedmelluq.lavaplayer.source.tunein.TuneinAudioSourceManager;
import com.sedmelluq.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.lavaplayer.source.vimeo.VimeoAudioSourceManager;

import dev.lavalink.youtube.YoutubeAudioSourceManager;

import dev.lavalink.youtube.clients.*;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class AudioSourceConfig {
    private static final List<AudioSourceManager> managers = new ArrayList<>();
    private static final Logger log = LoggerFactory.getLogger(AudioSourceConfig.class);

    private static final Client[] youtubeClients = {
            new TvHtml5Simply(),
            new Tv(),
            new AndroidVr(),
            new Web(),
            new MWeb(),
            new Ios(),
            new Music()
    };

    public static void loadNativeSourceManagers() {
        try {
            managers.add(new AudiomackAudioSourceManager());
            log.debug("Audiomack audio source manager registered!");

            managers.add(new BandlabAudioSourceManager());
            log.debug("Bandlab audio source manager registered!");

            managers.add(new BandcampAudioSourceManager());
            log.debug("Bandcamp audio source manager registered!");

            managers.add(new ClypAudioSourceManager());
            log.debug("Clyp.it audio source manager registered!");

            managers.add(new iHeartAudioSourceManager());
            log.debug("iHeart Radio audio source manager registered!");

            managers.add(new JamendoAudioSourceManager());
            log.debug("Jamendo Music audio source manager registered!");

            managers.add(new MixcloudAudioSourceManager());
            log.debug("Mixcloud audio source manager registered!");

            managers.add(new NicoAudioSourceManager(SourceTools.getPropertyOrEnv("NICO_VIDEO_EMAIL"), SourceTools.getPropertyOrEnv("NICO_VIDEO_PASSWORD"), true));
            log.debug("NicoVideo audio source manager registered!");

            managers.add(new OcremixAudioSourceManager());
            log.debug("Ocremix audio source manager registered!");

            managers.add(new OdyseeAudioSourceManager());
            log.debug("Odysee audio source manager registered!");

            managers.add(new ReverbnationAudioSourceManager());
            log.debug("Reverbnation audio source manager registered!");

            managers.add(new RumbleAudioSourceManager());
            log.debug("Rumble audio source manager registered!");

            managers.add(SoundCloudAudioSourceManager.createDefault());
            log.debug("SoundCloud audio source manager registered!");

            managers.add(new SoundgasmAudioSourceManager());
            log.debug("Soundgasm audio source manager registered!");

            managers.add(new StreamableAudioSourceManager());
            log.debug("Streamable audio source manager registered!");

            managers.add(new TuneinAudioSourceManager());
            log.debug("TuneIn Radio audio source manager registered!");

            managers.add(new TwitchStreamAudioSourceManager());
            log.debug("Twitch audio source manager registered!");

            managers.add(new VimeoAudioSourceManager());
            log.debug("Vimeo audio source manager registered!");

            YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(youtubeClients);

            Web.setPoTokenAndVisitorData(SourceTools.getPropertyOrEnv("YOUTUBE_PO_TOKEN"), SourceTools.getPropertyOrEnv("YOUTUBE_VISITOR_DATA"));

            String refreshToken = SourceTools.getPropertyOrEnv("YOUTUBE_REFRESH_TOKEN");
            if (SourceTools.isBlank(refreshToken))
                youtube.useOauth2(null, false);
            else
                youtube.useOauth2(refreshToken, true);

            managers.add(youtube);
            log.debug("YouTube audio source manager registered!");
        } catch (Exception e) {
            log.error("Failed to load native audio source managers: ", e);
        }
    }

    public static void loadThirdPartySourceManager(AudioPlayerManager playerManager) {
        try {
            managers.add(new AppleMusicAudioSourceManager(playerManager));
            log.debug("AppleMusic audio source manager registered!");

            managers.add(new DeezerAudioSourceManager(SourceTools.getPropertyOrEnv("DEEZER_MASTER_KEY"), SourceTools.getPropertyOrEnv("DEEZER_ARL")));
            log.debug("Deezer audio source manager registered!");

            managers.add(new PandoraAudioSourceManager(playerManager));
            log.debug("Pandora audio source manager registered!");

            managers.add(new SpotifyAudioSourceManager(playerManager));
            log.debug("Spotify audio source manager registered!");

            managers.add(new TidalAudioSourceManager(new DefaultThirdPartyAudioTrackResolver(), playerManager, SourceTools.getPropertyOrEnv("TIDAL_CLIENT_ID"), SourceTools.getPropertyOrEnv("TIDAL_CLIENT_SECRET")));
            log.debug("TIDAL audio source manager registered!");
        } catch (Exception e) {
            log.error("Failed to load third party/non-native audio source managers: ", e);
        }
    }

    public static void loadMainSourceManager() {
        try {
            managers.add(new HttpAudioSourceManager());
            log.debug("Http audio source manager registered!");
        } catch (Exception e) {
            log.error("Failed to load main audio source managers: ", e);
        }
    }

    public static void registerToSourceManager(AudioPlayerManager playerManager) {
        managers.forEach(playerManager::registerSourceManager);
    }
}
