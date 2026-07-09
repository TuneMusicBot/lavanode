package com.github.WearifulCupid0.lavanode.server.auth;

import com.github.WearifulCupid0.lavanode.Main;
import io.vertx.core.Vertx;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

public final class JWTAuthFactory {
    private JWTAuthFactory() {
    }

    public static JWTAuth create(Main main, String secret) {
        return create(main.getVertx(), secret);
    }

    public static JWTAuth create(Vertx vertx, String secret) {
        return JWTAuth.create(vertx,
                new JWTAuthOptions()
                        .addPubSecKey(
                                new PubSecKeyOptions()
                                        .setAlgorithm("HS256")
                                        .setBuffer(secret)
                        )
        );
    }
}
