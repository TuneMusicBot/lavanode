rootProject.name = "LavaNode"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://m2.dv8tion.net/releases")
        maven("https://maven.lavalink.dev/releases")
        maven("https://maven.lavalink.dev/snapshots")
        maven("https://jitpack.io")

        versionCatalogs {
            create("libs") {
                version("project", "0.1.0")
                version("java", "17")

                voice()
                common()
            }
        }
    }
}

fun VersionCatalogBuilder.voice() {
    version("lavaplayer", "1c9b0785e2")
    version("koe", "3.0.0-pre6")
    version("libdave", "0.1.3")
    version("netty", "4.2.15.Final")

    library("lavaplayer-common", "com.github.WearifulCupid0.lavaplayer", "lava-common").versionRef("lavaplayer")
    library("lavaplayer-main", "com.github.WearifulCupid0.lavaplayer", "main").versionRef("lavaplayer")
    library("lavaplayer-natives", "com.github.WearifulCupid0.lavaplayer", "natives-publish").versionRef("lavaplayer")
    library("lavaplayer-third-party", "com.github.WearifulCupid0.lavaplayer", "third-party-sources").versionRef("lavaplayer")
    library("lavaplayer-format-xm", "com.github.WearifulCupid0.lavaplayer", "format-xm").versionRef("lavaplayer")
    library("lavaplayer-source", "com.github.WearifulCupid0.lavaplayer", "source-module").versionRef("lavaplayer")
    library("lavaplayer-redis-cache", "com.github.WearifulCupid0.lavaplayer", "redis-cache").versionRef("lavaplayer")

    library("lavadsp", "dev.arbjerg", "lavadsp").version("0.7.8")
    library("lavadspx", "com.github.Devoxin", "LavaDSPX").version("2.0.1")

    library("koe", "moe.kyokobot.koe", "core").versionRef("koe")
    library("koe-udpqueue", "moe.kyokobot.koe", "ext-udpqueue").versionRef("koe")

    val libDavePlatforms = listOf("linux-x86-64", "linux-x86", "linux-aarch64", "linux-arm", /*"linux-musl-x86-64", "linux-musl-aarch64",*/ "win-x86-64", "win-x86", "darwin")
    libDavePlatforms.forEach {
        library("libdave-natives-$it", "moe.kyokobot.libdave", "natives-$it").versionRef("libdave")
    }
    bundle("libdave-natives", libDavePlatforms.map { "libdave-natives-$it" })

    version("udpqueue", "0.2.12")

    val platforms = listOf("linux-x86-64", "linux-x86", "linux-aarch64", "linux-arm", /*"linux-musl-x86-64", "linux-musl-aarch64",*/ "win-x86-64", "win-x86", "win-aarch64", "darwin")
    platforms.forEach {
        library("udpqueue-native-$it", "club.minnced", "udpqueue-native-$it").versionRef("udpqueue")
    }
    bundle("udpqueue-natives", platforms.map { "udpqueue-native-$it" })

    library("netty-epoll", "io.netty", "netty-transport-native-epoll").versionRef("netty")
    library("netty-kqueue", "io.netty", "netty-transport-native-kqueue").versionRef("netty")
}

fun VersionCatalogBuilder.common() {

    library("vertx", "io.vertx", "vertx-stack-depchain").version("5.1.3")
    library("junit", "org.junit", "junit-bom").version("5.10.0")

    library("sentry-logback", "io.sentry", "sentry-logback").version("7.10.0")
    library("logger", "ch.qos.logback", "logback-classic").version("1.5.37")
}