![Project icon](https://git-assets.jellysquid.me/hotlink-ok/phosphor/icon-rounded-128px.png)

# Phosphor Ports (for Fabric)
![GitHub license](https://img.shields.io/github/license/mrmangohands/phosphor-fabric.svg)
![GitHub issues](https://img.shields.io/github/issues/mrmangohands/phosphor-fabric.svg)
![GitHub tag](https://img.shields.io/github/tag/mrmangohands/phosphor-fabric.svg)

Phosphor is a free and open-source Minecraft mod (under GNU GPLv3) aiming to save your CPU cycles and improve performance by optimizing one of Minecraft's most inefficient areas-- the lighting engine.
It works on **both the client and server**, and can be installed on servers **without requiring clients to also have the mod**.

The mod is designed to be as minimal as possible in the changes it makes, and as such, does not modify the light model or interfaces of vanilla Minecraft. Because of this, Phosphor should be compatible
with many Minecraft mods (so long as they do not make drastic changes to how the lighting engine works.) If you've ran into a compatibility problem, please open an issue!

These are unofficial ports of [JellySquid and PhiPro's original mod](https://github.com/jellysquid3/phosphor-fabric) to versions
that aren't maintained or present upstream. 1.16.1 is the current priority.

### Downloads

You can find downloads on the [GitHub releases page](https://github.com/mrmangohands/Phosphor-fabric/releases).

---

### Building from source

If you're hacking on the code or would like to compile a custom build of Phosphor from the latest sources, you'll want
to start here.

#### Prerequisites

You will need to install JDK 8 in order to build Phosphor. You can either install this through a package manager such as
[Chocolatey](https://chocolatey.org/) on Windows or [SDKMAN!](https://sdkman.io/) on other platforms. If you'd prefer to
not use a package manager, you can always grab the installers or packages directly from
[AdoptOpenJDK](https://adoptopenjdk.net/).

On Windows, the Oracle JDK/JRE builds should be avoided where possible due to their poor quality. Always prefer using
the open-source builds from AdoptOpenJDK when possible.

#### Compiling

Navigate to the directory you've cloned this repository and launch a build with Gradle using `gradlew build` (Windows)
or `./gradlew build` (macOS/Linux). If you are not using the Gradle wrapper, simply replace `gradlew` with `gradle`
or the path to it.

The initial setup may take a few minutes. After Gradle has finished building everything, you can find the resulting
artifacts in `build/libs`.

Warning, I will likely be force pushing updates.

---

### License

Phosphor is licensed under GNU LGPLv3, a free and open-source license. For more information, please see the [license file](https://github.com/mrmangohands/phosphor-fabric/blob/1.16.x/dev/LICENSE.txt).
