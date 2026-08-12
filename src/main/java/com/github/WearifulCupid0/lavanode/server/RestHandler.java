package com.github.WearifulCupid0.lavanode.server;

import com.github.WearifulCupid0.lavanode.Main;
import com.github.WearifulCupid0.lavanode.player.PlayerSession;
import com.github.WearifulCupid0.lavanode.player.PlayerSessionManager;
import com.github.WearifulCupid0.lavanode.player.PlayerSettings;
import com.github.WearifulCupid0.lavanode.player.frame.PlayerFrameProviderMode;
import com.github.WearifulCupid0.lavanode.player.queue.QueueEntry;
import com.github.WearifulCupid0.lavanode.server.auth.JWTAuthFactory;
import com.github.WearifulCupid0.lavanode.server.auth.JWTUserHandler;
import com.github.WearifulCupid0.lavanode.server.handlers.JsonBodyHandler;
import com.github.WearifulCupid0.lavanode.server.handlers.PlayerSessionHandler;
import com.github.WearifulCupid0.lavanode.server.handlers.PlayerIdHandler;
import com.github.WearifulCupid0.lavanode.server.handlers.RestFailureHandler;
import com.github.WearifulCupid0.lavanode.player.connections.http.PlayerStreamHandler;
import com.github.WearifulCupid0.lavanode.util.RequestUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.lavaplayer.extensions.thirdpartysources.SourceTools;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import moe.kyokobot.koe.VoiceServerInfo;
import com.github.WearifulCupid0.lavanode.player.connections.ConnectionType;
import com.github.WearifulCupid0.lavanode.player.connections.PlayerConnection;
import com.github.WearifulCupid0.lavanode.player.connections.discord.DiscordPlayerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class RestHandler {
    private static final Logger log = LoggerFactory.getLogger(RestHandler.class);

    public static void setup(Main main) {
        Router router = Router.router(main.getVertx());

        JWTAuth jwtAuth = JWTAuthFactory.create(
                main.getVertx(),
                main.getTokenSecret()
        );

        router.route().handler(BodyHandler.create());

        router.route().handler(context -> {
            log.debug(
                    "Received request {} {} from {}",
                    context.request().method(),
                    context.normalizedPath(),
                    context.request().remoteAddress()
            );

            context.next();
        });

        router.route().failureHandler(RestFailureHandler::handle);

        router.get("/ping").handler(context -> context.response().setStatusCode(204).send());

        PlayerIdHandler playerIdHandler = new PlayerIdHandler();
        PlayerSessionHandler playerSessionHandler = new PlayerSessionHandler(main);

        router.get("/v1/players/:playerId/stream")
                .handler(playerIdHandler)
                .handler(new PlayerStreamHandler(main));

        router.route("/v1/*").handler(JWTAuthHandler.create(jwtAuth));
        router.route("/v1/*").handler(new JWTUserHandler());

        WebsocketHandler.setup(main, router);

        router.route("/v1/players/:playerId").handler(playerIdHandler);
        router.route("/v1/players/:playerId/*").handler(playerIdHandler);

        router.route("/v1/players/:playerId").handler(playerSessionHandler);
        router.route("/v1/players/:playerId/*").handler(playerSessionHandler);

        log.debug("Registering server routes...");

        router.get("/v1/loadtracks").handler(context -> {
            List<String> identifiers = context.queryParam("identifier");
            String identifier = identifiers.isEmpty() ? null : identifiers.get(0);

            if (SourceTools.isBlank(identifier)) {
                RequestUtil.handleError(context, 400, "Missing identifier parameter");
                return;
            }

            List<String> playlistLoadLimits = context.queryParam("playlistLoadLimit");
            String playlistLoadLimit = playlistLoadLimits.isEmpty() ? null : playlistLoadLimits.get(0);

            List<String> playlistOffsets = context.queryParam("playlistOffset");
            String playlistOffset = playlistOffsets.isEmpty() ? null : playlistOffsets.get(0);

            List<String> countryCodes = context.queryParam("countryCode");
            String countryCode = countryCodes.isEmpty() ? null : countryCodes.get(0);

            if (SourceTools.isBlank(countryCode)) countryCode = "US";

            AudioReference reference = new AudioReference(identifier.trim(), null, getIntOrDefault(playlistLoadLimit, 5), getIntOrDefault(playlistOffset, 0), countryCode);

            loadTracks(reference, main)
                    .thenAccept(json -> context.response().setStatusCode(200).send(json.toBuffer()));
        });

        router.post("/v1/players/:playerId/gapless-playback").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.setFrameProviderMode(PlayerFrameProviderMode.GAPLESS);

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/normal-playback").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.setFrameProviderMode(PlayerFrameProviderMode.NORMAL);

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/crossfade-playback").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.setFrameProviderMode(PlayerFrameProviderMode.CROSSFADE);

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/pause").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.pause();

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/resume").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.resume();

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/skip").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            QueueEntry entry = playerSession.getQueue().peek();

            if (entry == null) {
                RequestUtil.handleError(context, 400, "There are no more tracks in the queue");
                return;
            }

            playerSession.skip();

            context.response().send(RequestUtil.trackToJson(main.getAudioPlayerManager(), entry.getTrack()).toBuffer());
        });

        router.post("/v1/players/:playerId/previous").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            QueueEntry entry = playerSession.getQueue().peekPrevious();

            if (entry == null) {
                RequestUtil.handleError(context, 400, "There are no more tracks in the queue history");
                return;
            }

            playerSession.previous();

            context.response().send(RequestUtil.trackToJson(main.getAudioPlayerManager(), entry.getTrack()).toBuffer());
        });

        router.post("/v1/players/:playerId/queue/shuffle").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            playerSession.shuffleQueue();

            context.response().send(playerSession.getQueue().toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.route()
                .method(HttpMethod.POST)
                .method(HttpMethod.PUT)
                .method(HttpMethod.PATCH)
                .handler(new JsonBodyHandler());

        router.post("/v1/players/:playerId/seek").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            JsonObject json = JsonBodyHandler.getBody(context);

            Object rawPosition = json.getValue("position");

            if (!(rawPosition instanceof Number)) {
                RequestUtil.handleError(context, 400, "Missing or invalid position");
                return;
            }

            long position = ((Number) rawPosition).longValue();

            if (position < 0L) {
                RequestUtil.handleError(context, 400, "Position must be greater than or equal to 0");
                return;
            }

            boolean seeked = playerSession.seek(position);

            if (!seeked) {
                RequestUtil.handleError(context, 400, "Current track is not seekable or no track is playing");
                return;
            }

            context.response().send(playerSession.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.post("/v1/players/:playerId/loop/track").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            JsonObject json = JsonBodyHandler.getBody(context);

            Boolean enabled = readEnabled(json, "trackLoop");

            if (enabled == null) {
                RequestUtil.handleError(context, 400, "Missing or invalid enabled");
                return;
            }

            playerSession.setTrackLoop(enabled);

            context.response().send(playerSession.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.post("/v1/players/:playerId/loop/queue").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            JsonObject json = JsonBodyHandler.getBody(context);

            Boolean enabled = readEnabled(json, "queueLoop");

            if (enabled == null) {
                RequestUtil.handleError(context, 400, "Missing or invalid enabled");
                return;
            }

            playerSession.setQueueLoop(enabled);

            context.response().send(playerSession.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.post("/v1/players").handler(context -> {
            PlayerSessionManager sessionManager = getPlayerSession(context, main);
            JsonObject json = JsonBodyHandler.getBody(context);
            PlayerSession player = sessionManager.create(PlayerSettings.fromJson(json));

            String providerMode = json.getString("providerMode");
            if (!SourceTools.isBlank(providerMode)) {
                try {
                    player.setFrameProviderMode(PlayerFrameProviderMode.valueOf(providerMode.trim().toUpperCase()));
                } catch (IllegalArgumentException exception) {
                    sessionManager.destroy(player.getId());
                    RequestUtil.handleError(context, 400, "Invalid providerMode");
                    return;
                }
            }

            context.response()
                    .setStatusCode(201)
                    .send(player.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.get("/v1/players").handler(context -> {
            PlayerSessionManager sessionManager = getPlayerSession(context, main);
            String guildId = firstQueryParam(context, "guildId");
            String connectionId = firstQueryParam(context, "connectionId");

            if (!SourceTools.isBlank(guildId) && !SourceTools.isBlank(connectionId)) {
                RequestUtil.handleError(context, 400, "Use only guildId or connectionId");
                return;
            }

            try {
                context.response().send(sessionManager.toJson(guildId, connectionId).toBuffer());
            } catch (IllegalArgumentException exception) {
                RequestUtil.handleError(context, 400, exception.getMessage());
            }
        });

        router.get("/v1/players/:playerId").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            context.response().send(playerSession.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.get("/v1/players/:playerId/connections").handler(context -> {
            PlayerSession player = context.get("playerSession");
            JsonArray connections = new JsonArray();
            player.getConnections().forEach(connection -> connections.add(connection.toJson()));
            context.response().send(connections.toBuffer());
        });

        router.get("/v1/players/:playerId/connections/:connectionId").handler(context -> {
            PlayerSession player = context.get("playerSession");
            PlayerConnection connection = player.getConnection(context.pathParam("connectionId"));

            if (connection == null) {
                RequestUtil.handleError(context, 404, "Unknown connection");
                return;
            }

            context.response().send(connection.toJson().toBuffer());
        });

        router.post("/v1/players/:playerId/connections").handler(context -> {
            PlayerSession player = context.get("playerSession");
            PlayerSessionManager sessionManager = context.get("playerSessionManager");
            JsonObject json = JsonBodyHandler.getBody(context);

            ConnectionType type = ConnectionType.fromJson(json.getString("type", json.getString("platform")));
            if (type == null) {
                RequestUtil.handleError(context, 400, "Missing or invalid connection type");
                return;
            }

            if (type == ConnectionType.DISCORD) {
                String guildId = json.getString("guildId");
                String channelId = json.getString("channelId");
                String userId = json.getString("userId");
                String endpoint = json.getString("endpoint");
                String token = json.getString("token");
                String sessionId = json.getString("sessionId");

                if (SourceTools.isBlank(guildId)
                        || SourceTools.isBlank(channelId)
                        || SourceTools.isBlank(endpoint)
                        || SourceTools.isBlank(token)
                        || SourceTools.isBlank(sessionId)) {
                    RequestUtil.handleError(context, 400, "Missing Discord connection fields");
                    return;
                }

                try {
                    long parsedChannelId = Long.parseUnsignedLong(channelId);
                    VoiceServerInfo serverInfo = VoiceServerInfo.builder()
                            .setToken(token)
                            .setChannelId(parsedChannelId)
                            .setEndpoint(endpoint)
                            .setSessionId(sessionId)
                            .build();

                    DiscordPlayerConnection connection = sessionManager.createDiscordConnection(
                            player,
                            guildId,
                            channelId,
                            userId,
                            endpoint,
                            serverInfo
                    );

                    context.response().setStatusCode(201).send(connection.toJson().toBuffer());
                } catch (IllegalArgumentException exception) {
                    RequestUtil.handleError(context, 400, exception.getMessage());
                } catch (IllegalStateException exception) {
                    RequestUtil.handleError(context, 409, exception.getMessage());
                }
            } else if (type == ConnectionType.HTTP) {
                Object rawUserId = json.getValue("userId");
                if (!(rawUserId instanceof String userId) || userId.isBlank()) {
                    RequestUtil.handleError(context, 400, "Missing or invalid userId");
                    return;
                }

                try {
                    userId = userId.trim();
                } catch (Exception exception) {
                    RequestUtil.handleError(context, 400, "Invalid userId");
                    return;
                }

                Long expiresInMs = null;
                Object rawExpiresInMs = json.getValue("expiresInMs");

                if (rawExpiresInMs != null) {
                    if (!(rawExpiresInMs instanceof Number number)) {
                        RequestUtil.handleError(context, 400, "expiresInMs must be a number");
                        return;
                    }

                    expiresInMs = number.longValue();

                    if (expiresInMs <= 0L) {
                        RequestUtil.handleError(context, 400, "expiresInMs must be greater than 0");
                        return;
                    }
                }

                try {
                    JsonObject token = main.getStreamTokenManager()
                            .issue(player, userId, expiresInMs)
                            .toJson();

                    context.response()
                            .setStatusCode(201)
                            .putHeader("Cache-Control", "no-store")
                            .send(token.toBuffer());
                } catch (IllegalArgumentException exception) {
                    RequestUtil.handleError(context, 400, exception.getMessage());
                } catch (IllegalStateException exception) {
                    RequestUtil.handleError(context, 410, exception.getMessage());
                }
            }

            RequestUtil.handleError(context, 400, "Unknown connection type");
        });

        router.delete("/v1/players/:playerId/connections/:connectionId").handler(context -> {
            PlayerSession player = context.get("playerSession");
            boolean deleted = player.deleteConnection(context.pathParam("connectionId"), "deletedByRequest");

            if (!deleted) {
                RequestUtil.handleError(context, 404, "Unknown connection");
                return;
            }

            context.response().setStatusCode(204).send();
        });

        router.delete("/v1/players/:playerId").handler(context -> {
            PlayerSessionManager playerSessionManager = context.get("playerSessionManager");

            playerSessionManager.destroy(context.get("playerId"));
            context.response().setStatusCode(204).send();
        });

        router.get("/v1/players/:playerId/nowplaying").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            QueueEntry entry = playerSession.getCurrentEntry();

            if (entry == null) {
                context.response().setStatusCode(204).send();
                return;
            }

            context.response().send(entry.toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.post("/v1/players/:playerId/volume").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            JsonObject json = JsonBodyHandler.getBody(context);

            int volume = json.getInteger("volume", -1);

            if (volume < 0 || volume > 1000) {
                RequestUtil.handleError(context, 400, "Invalid volume");
                return;
            }

            playerSession.setVolume(volume);

            context.response().setStatusCode(204).send();
        });

        router.post("/v1/players/:playerId/filters").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            JsonObject json = JsonBodyHandler.getBody(context);

            RequestUtil.updateFilters(playerSession, json);

            context.response().send(playerSession.getPlayerFilters().toJson().toBuffer());
        });

        router.post("/v1/players/:playerId/play").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            JsonObject json = JsonBodyHandler.getBody(context);

            String trackEncoded = json.getString("track");

            if (SourceTools.isBlank(trackEncoded)) {
                RequestUtil.handleError(context, 400, "Missing encoded track");
                return;
            }

            AudioPlayerManager playerManager = main.getAudioPlayerManager();
            AudioTrack track = RequestUtil.decodeTrack(playerManager, trackEncoded);

            if (track == null) {
                RequestUtil.handleError(context, 400, "Invalid encoded track");
                return;
            }

            String requesterId = json.getString("userId", context.get("user-id"));
            JsonObject extraData = json.getJsonObject("extraData");

            playerSession.play(track, requesterId, extraData);

            context.response().send(playerSession.toJson(playerManager).toBuffer());
        });

        router.get("/v1/players/:playerId/queue").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            context.response().send(playerSession.getQueue().toJson(main.getAudioPlayerManager()).toBuffer());
        });

        router.delete("/v1/players/:playerId/queue/history").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            boolean cleared = playerSession.clearQueueHistory();

            context.response().send(new JsonObject()
                    .put("cleared", cleared)
                    .put("queue", playerSession.getQueue().toJson(main.getAudioPlayerManager()))
                    .toBuffer());
        });

        router.delete("/v1/players/:playerId/queue/all").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            boolean cleared = playerSession.clearQueuedEntriesAndHistory();

            context.response().send(new JsonObject()
                    .put("cleared", cleared)
                    .put("queue", playerSession.getQueue().toJson(main.getAudioPlayerManager()))
                    .toBuffer());
        });

        router.delete("/v1/players/:playerId/queue").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            boolean cleared = playerSession.clearQueuedEntries();

            context.response().send(new JsonObject()
                    .put("cleared", cleared)
                    .put("queue", playerSession.getQueue().toJson(main.getAudioPlayerManager()))
                    .toBuffer());
        });

        router.delete("/v1/players/:playerId/queue/:entryId").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");
            String entryId = context.pathParam("entryId");

            if (SourceTools.isBlank(entryId)) {
                RequestUtil.handleError(context, 400, "Missing queue entry id");
                return;
            }

            QueueEntry removed = playerSession.removeFromQueue(entryId);

            if (removed == null) {
                RequestUtil.handleError(context, 404, "Queue entry not found");
                return;
            }

            context.response().send(new JsonObject()
                    .put("removed", removed.toJson(main.getAudioPlayerManager()))
                    .put("queue", playerSession.getQueue().toJson(main.getAudioPlayerManager()))
                    .toBuffer());
        });

        router.post("/v1/players/:playerId/queue").handler(context -> {
            PlayerSession playerSession = context.get("playerSession");

            JsonObject json = JsonBodyHandler.getBody(context);

            JsonObject extraData = json.getJsonObject("extraData");

            String requesterId = json.getString("userId", context.get("user-id"));
            JsonArray tracks = json.getJsonArray("tracks");
            AudioPlayerManager playerManager = main.getAudioPlayerManager();

            if (tracks != null && !tracks.isEmpty()) {
                JsonArray response = new JsonArray();
                List<AudioTrack> formattedTracks = new ArrayList<>();

                for (int i = 0; i < tracks.size(); i++) {
                    String trackEncoded = tracks.getString(i);
                    if (!SourceTools.isBlank(trackEncoded)) {
                        AudioTrack track = RequestUtil.decodeTrack(playerManager, trackEncoded);

                        if (track != null)
                            formattedTracks.add(track);
                    }
                }

                List<QueueEntry> entries = playerSession.enqueueMany(formattedTracks, requesterId, extraData);

                for (QueueEntry entry : entries)
                    response.add(entry.toJson(playerManager));

                context.response().send(response.toBuffer());
                return;
            }

            String trackEncoded = json.getString("track");
            if (SourceTools.isBlank(trackEncoded)) {
                RequestUtil.handleError(context, 400, "Missing encoded track");
                return;
            }

            AudioTrack track = RequestUtil.decodeTrack(playerManager, trackEncoded);

            if (track == null) {
                RequestUtil.handleError(context, 400, "Invalid encoded track");
                return;
            }

            QueueEntry entry = playerSession.enqueue(track, requesterId, extraData);

            context.response().send(entry.toJson(playerManager).toBuffer());
        });

        String port = SourceTools.getPropertyOrEnv("PORT");

        if (SourceTools.isBlank(port)) {
            log.warn("Port not defined in system environment, using port 8080");
            port = "8080";
        }

        int realPort = Integer.parseInt(port);

        main.getVertx()
                .createHttpServer()
                .requestHandler(router)
                .listen(realPort)
                .onSuccess(server -> {
                    main.getUpdateBroadcaster().start();
                    log.info("REST server started on port {}", realPort);
                })
                .onFailure(error -> {
                    main.getUpdateBroadcaster().stop();
                    log.error("Failed to start REST server", error);
                });
    }

    private static String firstQueryParam(RoutingContext context, String name) {
        List<String> values = context.queryParam(name);
        return values.isEmpty() ? null : values.get(0);
    }

    private static Boolean readEnabled(JsonObject json, String fallbackKey) {
        Object rawEnabled = json.getValue("enabled");

        if (rawEnabled instanceof Boolean enabled) {
            return enabled;
        }

        Object rawFallback = json.getValue(fallbackKey);

        if (rawFallback instanceof Boolean enabled) {
            return enabled;
        }

        return null;
    }

    private static int getIntOrDefault(String input, int def) {
        try {
            return Integer.parseInt(input);
        } catch (Exception e) {
            return def;
        }
    }

    private static PlayerSessionManager getPlayerSession(RoutingContext context, Main main) {
        return getPlayerSession((String) context.get("user-id"), main);
    }

    private static PlayerSessionManager getPlayerSession(String userId, Main main) {
        return main.getPlayerManager().getOrCreate(userId);
    }

    private static CompletionStage<JsonObject> loadTracks(AudioReference identifier, Main main) {
        return loadTracks(identifier, main.getAudioPlayerManager());
    }

    private static CompletionStage<JsonObject> loadTracks(AudioReference identifier, AudioPlayerManager playerManager) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        playerManager.loadItem(identifier,
                new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        future.complete(new JsonObject()
                                .put("loadType", "trackLoaded")
                                .put("data", RequestUtil.trackToJson(playerManager, track)));
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        JsonObject json = new JsonObject();
                        if (playlist.isSearchResult()) {
                            JsonArray tracks = new JsonArray();
                            for (AudioTrack track : playlist.getTracks())
                                tracks.add(RequestUtil.trackToJson(playerManager, track));

                            json
                                    .put("loadType", "searchResult")
                                    .put("data", tracks);
                        } else {
                            json
                                    .put("loadType", "playlistLoad")
                                    .put("data", RequestUtil.playlistToJson(playerManager, playlist));
                        }

                        AudioTrack selectedTrack = playlist.getSelectedTrack();
                        if (selectedTrack != null)
                            json.put("selectedTrack", RequestUtil.trackToJson(playerManager, selectedTrack));

                        future.complete(json);
                    }

                    @Override
                    public void noMatches() {
                        future.complete(new JsonObject().put("loadType", "noMatches"));
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        future.complete(new JsonObject().put("loadType", "error").put("data", RequestUtil.encodeThrowable(exception)));
                    }
                });
        return future;
    }
}
