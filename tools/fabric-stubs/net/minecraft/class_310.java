/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.minecraft;

/**
 * Compile-time stand-in for Fabric's intermediary name of the client class
 * (obfuscated {@code class_310}, Mojang name {@code net.minecraft.client.Minecraft}).
 *
 * <p>Fabric mods are compiled against <b>intermediary</b> names, and Fabric Loader remaps the
 * game to those names at runtime.  Building without Gradle/Loom means there is no
 * intermediary-mapped Minecraft jar on this machine, so the only type the bridge needs -
 * the parameter of {@code ClientTickEvents.EndTick} - is declared here.  The stub is
 * <b>only</b> put on the compile classpath; it is never packaged into the mod jar, and it
 * declares no members because the bridge never touches the client instance.</p>
 */
public class class_310 {
    private class_310() {
    }
}
