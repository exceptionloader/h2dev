package de.minesmp.core.world;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

/**
 * Spricht BetterModel (getestet mit der Schnittstelle von Version 3.6.0) ueber Reflection an. So braucht MineSMPCore
 * BetterModel weder zum Bauen noch zum Starten; fehlt eine Methode (andere Version), schaltet ModelService die
 * 3D-Modelle mit einer Warnung ab, statt abzustuerzen.
 * Genutzte Aufrufe: BetterModel.modelOrNull(String), BetterModel.registryOrNull(UUID), BetterModel.modelKeys(),
 * BukkitAdapter.adapt(Entity), ModelRenderer.getOrCreate(PlatformEntity), Tracker.scaler(ModelScaler),
 * ModelScaler.value(float), Tracker.animate(String, AnimationModifier), EntityTrackerRegistry.trackers()/isClosed()/close().
 */
final class BetterModelBridge {
    private final Method modelOrNull, registryOrNull, modelKeys, adapt, getOrCreate, scaler, scalerValue, animate, trackers, isClosed, close;
    private final Object playOnce;

    BetterModelBridge() throws ReflectiveOperationException {
        Plugin bm = Bukkit.getPluginManager().getPlugin("BetterModel");
        if (bm == null) throw new ClassNotFoundException("BetterModel");
        ClassLoader cl = bm.getClass().getClassLoader();
        Class<?> api = Class.forName("kr.toxicity.model.api.BetterModel", true, cl);
        Class<?> adapter = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter", true, cl);
        Class<?> platformEntity = Class.forName("kr.toxicity.model.api.platform.PlatformEntity", true, cl);
        Class<?> renderer = Class.forName("kr.toxicity.model.api.data.renderer.ModelRenderer", true, cl);
        Class<?> tracker = Class.forName("kr.toxicity.model.api.tracker.Tracker", true, cl);
        Class<?> modelScaler = Class.forName("kr.toxicity.model.api.tracker.ModelScaler", true, cl);
        Class<?> modifier = Class.forName("kr.toxicity.model.api.animation.AnimationModifier", true, cl);
        Class<?> registry = Class.forName("kr.toxicity.model.api.tracker.EntityTrackerRegistry", true, cl);
        modelOrNull = api.getMethod("modelOrNull", String.class);
        registryOrNull = api.getMethod("registryOrNull", UUID.class);
        modelKeys = api.getMethod("modelKeys");
        adapt = adapter.getMethod("adapt", Entity.class);
        getOrCreate = renderer.getMethod("getOrCreate", platformEntity);
        scaler = tracker.getMethod("scaler", modelScaler);
        scalerValue = modelScaler.getMethod("value", float.class);
        animate = tracker.getMethod("animate", String.class, modifier);
        trackers = registry.getMethod("trackers");
        isClosed = registry.getMethod("isClosed");
        close = registry.getMethod("close");
        playOnce = modifier.getField("DEFAULT_WITH_PLAY_ONCE").get(null);
    }

    int count() throws ReflectiveOperationException {
        return ((Collection<?>) modelKeys.invoke(null)).size();
    }

    boolean has(String model) throws ReflectiveOperationException {
        return modelOrNull.invoke(null, model) != null;
    }

    private Object registry(Entity e) throws ReflectiveOperationException {
        return registryOrNull.invoke(null, e.getUniqueId());
    }

    boolean attached(Entity e) throws ReflectiveOperationException {
        Object r = registry(e);
        return r != null && !(Boolean) isClosed.invoke(r) && !((Collection<?>) trackers.invoke(r)).isEmpty();
    }

    /** Haengt das Modell an das Wesen (das Wesen selbst blendet BetterModel aus). */
    boolean attach(Entity e, String model, float scale) throws ReflectiveOperationException {
        Object r = modelOrNull.invoke(null, model);
        if (r == null) return false;
        Object t = getOrCreate.invoke(r, adapt.invoke(null, e));
        scaler.invoke(t, scalerValue.invoke(null, scale));
        return true;
    }

    void animate(Entity e, String animation) throws ReflectiveOperationException {
        Object r = registry(e);
        if (r == null) return;
        for (Object t : (Collection<?>) trackers.invoke(r)) animate.invoke(t, animation, playOnce);
    }

    void detach(Entity e) throws ReflectiveOperationException {
        Object r = registry(e);
        if (r != null) close.invoke(r);
    }
}
