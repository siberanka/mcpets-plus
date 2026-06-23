package fr.nocsy.mcpets.modeler;

import fr.nocsy.mcpets.data.Pet;
import fr.nocsy.mcpets.modeler.bone.AbstractNameTag;
import fr.nocsy.mcpets.modeler.bone.BetterModelNameTag;
import fr.nocsy.mcpets.modeler.listeners.BetterModelListeners;
import fr.nocsy.mcpets.utils.debug.Debugger;
import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.bukkit.platform.BukkitEntity;
import kr.toxicity.model.api.mount.MountControllers;
import kr.toxicity.model.api.nms.HitBox;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

public class BetterModelModeler implements AbstractModeler {

    private BetterModelListeners listeners;

    public BetterModelModeler() {
        // hitBoxes() was added in BetterModel 2.2.0. Verify the minimum API
        // contract during startup so an outdated installation is rejected and
        // MCPets can fall back to ModelEngine instead of crashing on first mount.
        try {
            EntityTrackerRegistry.class.getMethod("hitBoxes");
        } catch (final NoSuchMethodException error) {
            throw new IllegalStateException("BetterModel 2.2.0 or newer is required.", error);
        }
    }

    /**
     * Finds the first mountable HitBox from the registry's trackers.
     */
    private HitBox findMountableHitBox(EntityTrackerRegistry registry) {
        return registry.hitBoxes().stream()
                .filter(hitBox -> hitBox.mountController().canMount())
                .findFirst()
                .orElse(null);
    }

    @Override
    public boolean mountDriver(UUID petUUID, Entity rider, String mountType) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry == null || registry.isClosed() || !rider.isValid() || rider.isDead())
            return false;

        HitBox hitBox = findMountableHitBox(registry);
        if (hitBox == null)
            return false;

        if (hasMount(petUUID, rider))
            return true;

        // Leave only the rider's current vehicle. Ejecting the whole vehicle
        // would unexpectedly dismount unrelated passengers.
        if (rider.getVehicle() != null)
            rider.leaveVehicle();

        MountControllers base = resolveMountController(mountType);
        hitBox.mountController(base.modifier()
                .canBeDamagedByRider(false)
                .build());
        hitBox.mount(new BukkitEntity(rider));
        Debugger.send("§a[BetterModel] Mounted " + rider.getName()
                + " on pet " + petUUID + " via HitBox (controller: " + base.name() + ")");
        return true;
    }

    @Override
    public boolean hasMount(UUID petUUID, Entity rider) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry != null) {
            return registry.mountedHitBox().containsKey(rider.getUniqueId());
        }
        // Fallback to vanilla check
        Entity petEntity = Bukkit.getEntity(petUUID);
        if (petEntity == null)
            return false;
        return petEntity.getPassengers().contains(rider);
    }

    @Override
    public void dismountRider(UUID petUUID, Entity rider) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry != null) {
            EntityTrackerRegistry.MountedHitBox mounted = registry.mountedHitBox().get(rider.getUniqueId());
            if (mounted != null) {
                mounted.dismount();
                Debugger.send("§a[BetterModel] Dismounted " + rider.getName()
                        + " from pet " + petUUID + " via HitBox");
                return;
            }
        }
        // Fallback to vanilla
        Entity petEntity = Bukkit.getEntity(petUUID);
        if (petEntity == null)
            return;
        petEntity.removePassenger(rider);
    }

    @Override
    public void dismountAll(UUID petUUID) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry != null && registry.hasPassenger()) {
            // Dismounting mutates the registry map. Work on a stable, de-duplicated
            // hitbox snapshot to avoid repeated events and weakly-consistent walks.
            new HashSet<>(registry.mountedHitBox().values().stream()
                    .map(EntityTrackerRegistry.MountedHitBox::hitBox)
                    .toList())
                    .forEach(HitBox::dismountAll);
            Debugger.send("§a[BetterModel] Dismounted all riders from pet " + petUUID);
            return;
        }
        // Fallback to vanilla
        Entity petEntity = Bukkit.getEntity(petUUID);
        if (petEntity == null)
            return;
        petEntity.eject();
    }

    @Override
    public void removeModel(UUID petUUID) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry == null)
            return;
        registry.close();
        Debugger.send("§a[BetterModel] Removed model for pet " + petUUID);
    }

    @Override
    public AbstractNameTag getNameTag(UUID petUUID) {
        EntityTrackerRegistry registry = BetterModel.registryOrNull(petUUID);
        if (registry == null)
            return null;

        for (EntityTracker tracker : registry.trackers()) {
            RenderedBone bone = tracker.bone("name");
            if (bone != null && bone.getNametag() != null) {
                return new BetterModelNameTag(bone.getNametag());
            }
        }
        Debugger.send("§e[BetterModel] No nametag bone found for pet " + petUUID);
        return null;
    }

    @Override
    public boolean supportsMount(String mountType) {
        if (mountType == null)
            return false;

        return switch (mountType.trim().toLowerCase(Locale.ROOT)) {
            case "walk", "walking", "fly", "flying" -> true;
            default -> false;
        };
    }

    @Override
    public void handleVanillaDismount(UUID petUUID, Entity rider) {
        // Intentionally empty: BetterModel's DismountModelEvent handles despawnOnDismount
        // via BetterModelListeners.onDismount(). Adding logic here would cause double triggers.
    }

    @Override
    public boolean isFlyingMount(Pet pet, UUID owner) {
        if (pet.getActiveMob() == null)
            return false;

        UUID uuid = pet.getActiveMob().getUniqueId();
        EntityTrackerRegistry registry = BetterModel.registryOrNull(uuid);
        if (registry == null || registry.isClosed())
            return false;

        EntityTrackerRegistry.MountedHitBox mounted = registry.mountedHitBox().get(owner);
        return mounted != null && mounted.hitBox().mountController().canFly();
    }

    @Override
    public void registerListeners(JavaPlugin plugin) {
        listeners = new BetterModelListeners();
        listeners.register();
    }

    @Override
    public void unregisterListeners() {
        if (listeners != null) {
            listeners.unregister();
            listeners = null;
        }
    }

    /**
     * Resolves the BetterModel MountController enum from the pet mount type string.
     */
    private static MountControllers resolveMountController(String mountType) {
        if (mountType == null || mountType.isEmpty())
            return MountControllers.WALK;
        String upper = mountType.toUpperCase(Locale.ROOT);
        if (upper.contains("FLY"))
            return MountControllers.FLY;
        return MountControllers.WALK;
    }
}
