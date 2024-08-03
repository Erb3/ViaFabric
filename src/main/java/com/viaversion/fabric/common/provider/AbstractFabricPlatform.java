/*
 * This file is part of ViaFabric - https://github.com/ViaVersion/ViaFabric
 * Copyright (C) 2018-2024 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.fabric.common.provider;

import com.viaversion.fabric.common.platform.FabricViaAPI;
import com.viaversion.fabric.common.platform.FabricViaConfig;
import com.viaversion.fabric.common.platform.NativeVersionProvider;
import com.viaversion.fabric.common.util.FutureTaskId;
import com.viaversion.fabric.common.util.JLoggerToLog4j;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaAPI;
import com.viaversion.viaversion.api.configuration.ConfigurationProvider;
import com.viaversion.viaversion.api.configuration.ViaVersionConfig;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.UnsupportedSoftware;
import com.viaversion.viaversion.api.platform.ViaPlatform;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.unsupported.UnsupportedPlugin;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.apache.logging.log4j.LogManager;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public abstract class AbstractFabricPlatform implements ViaPlatform<UserConnection> {
    private final Logger logger = new JLoggerToLog4j(LogManager.getLogger("ViaVersion"));
    private FabricViaConfig config;
    private File dataFolder;
    private final ViaAPI<UserConnection> api;

    {
        api = new FabricViaAPI();
    }

    public void init() {
        // We'll use it early for ViaInjector
        installNativeVersionProvider();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("ViaFabric");
        dataFolder = configDir.toFile();
        config = new FabricViaConfig(configDir.resolve("viaversion.yml").toFile(), logger);
    }

    protected abstract void installNativeVersionProvider();

    protected abstract ExecutorService asyncService();

    protected abstract EventLoop eventLoop();

    protected FutureTaskId runEventLoop(Runnable runnable) {
        return new FutureTaskId(eventLoop().submit(runnable).addListener(errorLogger()));
    }

    @Override
    public FutureTaskId runAsync(Runnable runnable) {
        return new FutureTaskId(CompletableFuture.runAsync(runnable, asyncService())
                .exceptionally(throwable -> {
                    if (!(throwable instanceof CancellationException)) {
                        throwable.printStackTrace();
                    }
                    return null;
                }));
    }

    @Override
    public FutureTaskId runRepeatingAsync(Runnable runnable, long ticks) {
        return new FutureTaskId(eventLoop()
                .scheduleAtFixedRate(() -> runAsync(runnable), 0, ticks * 50, TimeUnit.MILLISECONDS)
                .addListener(errorLogger())
        );
    }

    @Override
    public FutureTaskId runSync(Runnable runnable, long ticks) {
        // ViaVersion seems to not need to run delayed tasks on main thread
        return new FutureTaskId(eventLoop()
                .schedule(() -> runSync(runnable), ticks * 50, TimeUnit.MILLISECONDS)
                .addListener(errorLogger())
        );
    }

    @Override
    public FutureTaskId runRepeatingSync(Runnable runnable, long ticks) {
        // ViaVersion seems to not need to run repeating tasks on main thread
        return new FutureTaskId(eventLoop()
                .scheduleAtFixedRate(() -> runSync(runnable), 0, ticks * 50, TimeUnit.MILLISECONDS)
                .addListener(errorLogger())
        );
    }

    protected <T extends Future<?>> GenericFutureListener<T> errorLogger() {
        return future -> {
            if (!future.isCancelled() && future.cause() != null) {
                future.cause().printStackTrace();
            }
        };
    }

    @Override
    public boolean isProxy() {
        // We kinda of have all server versions
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    public void onReload() {
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public ViaVersionConfig getConf() {
        return config;
    }

    @Override
    public ViaAPI<UserConnection> getApi() {
        return api;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }

    @Override
    public String getPluginVersion() {
        return FabricLoader.getInstance().getModContainer("viaversion").map(ModContainer::getMetadata)
                .map(ModMetadata::getVersion).map(Version::getFriendlyString).orElse("UNKNOWN");
    }

    @Override
    public String getPlatformName() {
        return "ViaFabric";
    }

    @Override
    public String getPlatformVersion() {
        return FabricLoader.getInstance().getModContainer("viafabric")
                .get().getMetadata().getVersion().getFriendlyString();
    }

    @Override
    public boolean isPluginEnabled() {
        return true;
    }

    @Override
    public JsonObject getDump() {
        JsonObject platformSpecific = new JsonObject();
        JsonArray mods = new JsonArray();
        FabricLoader.getInstance().getAllMods().stream().map((mod) -> {
            JsonObject jsonMod = new JsonObject();
            jsonMod.addProperty("id", mod.getMetadata().getId());
            jsonMod.addProperty("name", mod.getMetadata().getName());
            jsonMod.addProperty("version", mod.getMetadata().getVersion().getFriendlyString());
            JsonArray authors = new JsonArray();
            mod.getMetadata().getAuthors().stream().map(it -> {
                JsonObject info = new JsonObject();
                JsonObject contact = new JsonObject();
                it.getContact().asMap().entrySet()
                        .forEach(c -> contact.addProperty(c.getKey(), c.getValue()));
                if (contact.size() != 0) {
                    info.add("contact", contact);
                }
                info.addProperty("name", it.getName());

                return info;
            }).forEach(authors::add);
            jsonMod.add("authors", authors);

            return jsonMod;
        }).forEach(mods::add);

        platformSpecific.add("mods", mods);
        NativeVersionProvider ver = Via.getManager().getProviders().get(NativeVersionProvider.class);
        if (ver != null) {
            platformSpecific.addProperty("native version", ver.getNativeServerProtocolVersion().getVersion());
        }
        return platformSpecific;
    }

    @Override
    public final boolean hasPlugin(String name) {
        return FabricLoader.getInstance().isModLoaded(name);
    }

    @Override
    public boolean couldBeReloading() {
        return false;
    }
}
