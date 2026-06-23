package fr.nocsy.mcpets.compat;

import fr.nocsy.mcpets.MCPets;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Version-adaptive, optional CraftEngine item bridge.
 *
 * CraftEngine 0.0.x exposes byId(Key) and buildItemStack(), while 26.x also
 * exposes byId(String) and returns a definition with buildBukkitItem(). Keeping
 * every CraftEngine type behind reflection prevents an absent or incompatible
 * installation from breaking MCPets class loading.
 */
public final class CraftEngineCompat {

    private static final String PLUGIN_NAME = "CraftEngine";
    private static final String ITEMS_API = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    private static final String KEY_API = "net.momirealms.craftengine.core.util.Key";
    private static final String RELOAD_EVENT_API = "net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent";
    private static final int MAX_ITEM_ID_LENGTH = 256;
    private static final Pattern ITEM_ID_PATTERN = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private static final AtomicBoolean runtimeFailureLogged = new AtomicBoolean();
    private static final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private static final AtomicBoolean reloadObserved = new AtomicBoolean();
    private static final Set<String> missingItemWarnings = ConcurrentHashMap.newKeySet();

    private static volatile Api api;
    private static Listener reloadListener;

    private CraftEngineCompat() {
    }

    public static boolean initialize() {
        final Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }

        if (api != null) {
            return true;
        }

        synchronized (CraftEngineCompat.class) {
            if (api != null) {
                return true;
            }

            try {
                final ClassLoader loader = plugin.getClass().getClassLoader();
                final Class<?> itemsClass = Class.forName(ITEMS_API, true, loader);

                Method keyFactory = null;
                Method byId;
                try {
                    byId = itemsClass.getMethod("byId", String.class);
                } catch (final NoSuchMethodException ignored) {
                    final Class<?> keyClass = Class.forName(KEY_API, true, loader);
                    keyFactory = keyClass.getMethod("of", String.class);
                    byId = itemsClass.getMethod("byId", keyClass);
                }

                if (!Modifier.isStatic(byId.getModifiers())
                        || (keyFactory != null && !Modifier.isStatic(keyFactory.getModifiers()))) {
                    throw new IllegalStateException("Unexpected non-static CraftEngine API method.");
                }

                final Method buildItem = resolveBuildMethod(byId.getReturnType());

                api = new Api(byId, keyFactory, buildItem);
                registerReloadListener(loader);
                MCPets.getLog().info("CraftEngine found. CraftEngine custom items are available.");
                return true;
            } catch (final ReflectiveOperationException | LinkageError | RuntimeException error) {
                MCPets.getLog().log(Level.WARNING,
                        "CraftEngine is enabled but its item API is incompatible. CraftEngine custom items will be disabled.",
                        error);
                return false;
            }
        }
    }

    public static ItemStack createItem(final String itemId) {
        if (!isSafeItemId(itemId)) {
            MCPets.getLog().warning("Rejected an invalid CraftEngine item id in an MCPets configuration.");
            return null;
        }

        final Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled() || (!initialize() && api == null)) {
            return null;
        }

        final Api current = api;
        if (current == null) {
            return null;
        }

        try {
            final Object lookupKey = current.keyFactory() == null
                    ? itemId
                    : current.keyFactory().invoke(null, itemId);
            final Object definition = current.byId().invoke(null, lookupKey);
            if (definition == null) {
                if (reloadObserved.get() && missingItemWarnings.add(itemId)) {
                    MCPets.getLog().warning("CraftEngine item '" + itemId + "' was not found.");
                }
                return null;
            }

            final Object built = current.buildItem().invoke(definition);
            if (built instanceof final ItemStack itemStack) {
                return itemStack.clone();
            }

            logRuntimeFailure(new IllegalStateException("CraftEngine returned a non-Bukkit item stack."));
        } catch (final IllegalAccessException | InvocationTargetException | LinkageError | RuntimeException error) {
            final Throwable cause = error instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause()
                    : error;
            logRuntimeFailure(cause);
        }
        return null;
    }

    public static void reset() {
        synchronized (CraftEngineCompat.class) {
            if (reloadListener != null) {
                HandlerList.unregisterAll(reloadListener);
                reloadListener = null;
            }
            api = null;
            runtimeFailureLogged.set(false);
            refreshScheduled.set(false);
            reloadObserved.set(false);
            missingItemWarnings.clear();
        }
    }

    private static boolean isSafeItemId(final String itemId) {
        return itemId != null
                && !itemId.isBlank()
                && itemId.length() <= MAX_ITEM_ID_LENGTH
                && ITEM_ID_PATTERN.matcher(itemId).matches();
    }

    private static Method resolveBuildMethod(final Class<?> definitionType) throws NoSuchMethodException {
        try {
            return definitionType.getMethod("buildBukkitItem");
        } catch (final NoSuchMethodException ignored) {
            return definitionType.getMethod("buildItemStack");
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerReloadListener(final ClassLoader loader) throws ReflectiveOperationException {
        if (reloadListener != null) {
            return;
        }

        final Class<?> rawEventClass = Class.forName(RELOAD_EVENT_API, false, loader);
        if (!Event.class.isAssignableFrom(rawEventClass)) {
            throw new IllegalStateException("CraftEngineReloadEvent is not a Bukkit event.");
        }

        final Listener listener = new Listener() {
        };
        Bukkit.getPluginManager().registerEvent(
                (Class<? extends Event>) rawEventClass,
                listener,
                EventPriority.MONITOR,
                (ignored, event) -> {
                    reloadObserved.set(true);
                    schedulePetDefinitionRefresh();
                },
                MCPets.getInstance(),
                true
        );
        reloadListener = listener;
    }

    private static void schedulePetDefinitionRefresh() {
        final MCPets plugin = MCPets.getInstance();
        if (plugin == null || !plugin.isEnabled() || !refreshScheduled.compareAndSet(false, true)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (plugin.isEnabled()) {
                    MCPets.refreshCustomItemDefinitions();
                }
            } finally {
                refreshScheduled.set(false);
            }
        });
    }

    private static void logRuntimeFailure(final Throwable error) {
        if (runtimeFailureLogged.compareAndSet(false, true)) {
            MCPets.getLog().log(Level.WARNING,
                    "CraftEngine failed to create a custom item. Further identical failures will be suppressed until reload.",
                    error);
        }
    }

    private record Api(Method byId, Method keyFactory, Method buildItem) {
    }
}
