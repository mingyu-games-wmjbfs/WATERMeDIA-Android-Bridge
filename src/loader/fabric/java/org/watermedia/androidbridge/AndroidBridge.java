/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.watermedia.androidbridge;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Mod entry point for Minecraft 1.20.1 / Fabric.
 *
 * <p>Fabric has no load-order attribute, so this build uses <b>two</b> entrypoints:</p>
 *
 * <ul>
 *   <li>{@code preLaunch} - runs before any mod's initialiser and before the game starts.
 *       That is where the bundled VLC has to be extracted, because videolan4j's
 *       {@code NativeDiscovery.start()} is <b>one-shot</b>: its first attempt sets an
 *       internal {@code attempted} flag and every later call returns {@code false} without
 *       trying again.  If WATERMeDIA (a dependency, therefore initialised <i>before</i> this
 *       mod on Fabric) ran discovery first, no amount of retrying would ever load VLC.</li>
 *   <li>{@code client} - installs the client tick safety net, which registers the Android
 *       VLC argument set once WATERMeDIA is ready, exactly like the NeoForge and Forge
 *       builds do.</li>
 * </ul>
 *
 * <p>{@link AndroidVlc#bootstrapEarly()} is idempotent, so calling it from both
 * entrypoints is harmless.</p>
 */
public final class AndroidBridge implements ClientModInitializer, PreLaunchEntrypoint {

    /** Payload first: this must happen before WATERMeDIA initialises (see the class comment). */
    @Override
    public void onPreLaunch() {
        AndroidVlc.bootstrapEarly();
    }

    @Override
    public void onInitializeClient() {
        // in a plain JVM harness (or a Fabric install without the API) this is a no-op
        AndroidVlc.bootstrapEarly();
        try {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                    .register(client -> AndroidVlc.clientTick());
        } catch (final Throwable t) {
            BridgeLog.warn("could not install the client tick safety net: {}. Android VLC arguments "
                    + "will only be applied if WATERMeDIA is constructed after this mod.", t.toString());
        }
    }
}
