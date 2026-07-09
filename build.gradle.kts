plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.github.WearifulCupid0"
version = "1.0-SNAPSHOT"

dependencies {

    //Koe & DAVE
    implementation(libs.koe)
    implementation(libs.koe.udpqueue) {
        exclude(module = "udp-queue")
    }

    implementation(libs.netty.epoll)
    implementation(libs.netty.kqueue)

    runtimeOnly(libs.bundles.udpqueue.natives) {
        exclude(group = "com.sedmelluq", module = "lava-common")
    }
    runtimeOnly(libs.bundles.libdave.natives)

    //Lavaplayer
    implementation(libs.lavaplayer.main)
    implementation(libs.lavaplayer.third.party)
    implementation(libs.lavaplayer.redis.cache)
    implementation(libs.lavaplayer.source)
    implementation(libs.lavaplayer.format.xm)

    implementation(libs.lavadspx)
    implementation(libs.lavadsp)

    runtimeOnly("dev.arbjerg:lavaplayer-natives:2.2.6")

    //Youtube-Source
    implementation("dev.lavalink.youtube:common:1.18.1")

    //runtimeOnly(libs.lavaplayer.natives)

    implementation(platform(libs.vertx))
    implementation("io.vertx:vertx-launcher-application")
    implementation("io.vertx:vertx-auth-jwt")
    implementation("io.vertx:vertx-web")
    testImplementation("io.vertx:vertx-junit5")

    testImplementation(platform(libs.junit))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    implementation(libs.logger)
}

application {
    mainClass.set("com.github.WearifulCupid0.lavanode.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

