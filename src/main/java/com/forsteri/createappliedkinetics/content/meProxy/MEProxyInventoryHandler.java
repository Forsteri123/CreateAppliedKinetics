package com.forsteri.createappliedkinetics.content.meProxy;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.me.storage.NetworkStorage;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.EmptyFluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Bridge between Create's slot-based inventory and AE2's keyed storage.
 *
 * IMPORTANT: Create's packager may visit a virtual slot repeatedly while
 * processing a single order. Rebuilding AE2's entire available-stack index
 * for every visit has catastrophic cost for large orders.
 * This implementation takes one snapshot of virtual slot identities per
 * server tick, without caching quantities or actual extraction results.
 * The block entity owns one handler and exposes it as both capabilities,
 * so the item and fluid views share that snapshot.
 */
public class MEProxyInventoryHandler implements IItemHandler, IFluidHandler {
    private final MEProxyBlockEntity blockEntity;
    private long snapshotTick = Long.MIN_VALUE;
    private NetworkStorage snapshotStorage;
    private List<AEItemKey> itemKeys = List.of();
    private List<AEFluidKey> fluidKeys = List.of();

    public MEProxyInventoryHandler(MEProxyBlockEntity meProxyBlockEntity) {
        this.blockEntity = meProxyBlockEntity;
    }

    private Optional<NetworkStorage> getStorage() {
        IGrid grid = blockEntity.getMainNode().getGrid();
        if (grid == null) return Optional.empty();
        return Optional.of((NetworkStorage) grid.getStorageService().getInventory());
    }

    private void refreshKeys(NetworkStorage storage) {
        var level = blockEntity.getLevel();
        if (level == null) {
            // A missing level has no tick to invalidate against, so don't keep a snapshot.
            rebuildKeys(storage);
            snapshotStorage = null;
            snapshotTick = Long.MIN_VALUE;
            return;
        }
        long tick = level.getGameTime();
        if (storage == snapshotStorage && tick == snapshotTick) return;
        snapshotStorage = storage;
        snapshotTick = tick;
        rebuildKeys(storage);
    }

    private void rebuildKeys(NetworkStorage storage) {
        var keys = storage.getAvailableStacks().keySet();
        itemKeys = keys.stream().filter(k -> k instanceof AEItemKey)
                .map(k -> (AEItemKey) k).toList();
        fluidKeys = keys.stream().filter(k -> k instanceof AEFluidKey)
                .map(k -> (AEFluidKey) k).toList();
    }

    private void clearSnapshot() {
        snapshotStorage = null;
        snapshotTick = Long.MIN_VALUE;
        itemKeys = List.of();
        fluidKeys = List.of();
    }

    private NetworkStorage storageWithSnapshot() {
        NetworkStorage storage = getStorage().orElse(null);
        if (storage == null) {
            clearSnapshot();
            return null;
        }
        refreshKeys(storage);
        return storage;
    }

    List<AEItemKey> getItemKeys() {
        return storageWithSnapshot() == null ? List.of() : itemKeys;
    }

    List<AEFluidKey> getFluidKeys() {
        return storageWithSnapshot() == null ? List.of() : fluidKeys;
    }

    @Override
    public int getTanks() {
        return getFluidKeys().size();
    }

    @NotNull
    @Override
    public FluidStack getFluidInTank(int tank) {
        NetworkStorage storage = storageWithSnapshot();
        if (storage == null || tank < 0 || tank >= fluidKeys.size()) return FluidStack.EMPTY;
        AEFluidKey key = fluidKeys.get(tank);
        long amount = storage.extract(key, Integer.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
        return amount > 0 ? key.toStack((int) Math.min(amount, Integer.MAX_VALUE)) : FluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        NetworkStorage storage = getStorage().orElse(null);
        return storage != null && !stack.isEmpty() &&
                storage.insert(AEFluidKey.of(stack), stack.getAmount(), Actionable.SIMULATE, IActionSource.empty()) > 0;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        NetworkStorage storage = getStorage().orElse(null);
        if (storage == null || resource.isEmpty()) return 0;
        return (int) storage.insert(AEFluidKey.of(resource), resource.getAmount(),
                action == FluidAction.EXECUTE ? Actionable.MODULATE : Actionable.SIMULATE, IActionSource.empty());
    }

    @NotNull
    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        NetworkStorage storage = getStorage().orElse(null);
        if (storage == null || resource.isEmpty() || resource.getFluid() instanceof EmptyFluid) return FluidStack.EMPTY;
        long removed = storage.extract(AEFluidKey.of(resource), resource.getAmount(),
                action == FluidAction.EXECUTE ? Actionable.MODULATE : Actionable.SIMULATE, IActionSource.empty());
        if (removed <= 0) return FluidStack.EMPTY;
        FluidStack result = resource.copy();
        result.setAmount((int) Math.min(removed, Integer.MAX_VALUE));
        return result;
    }

    @NotNull
    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        FluidStack selected = getFluidInTank(0);
        if (selected.isEmpty()) return FluidStack.EMPTY;
        selected = selected.copy();
        selected.setAmount(Math.min(maxDrain, selected.getAmount()));
        return drain(selected, action);
    }

    @Override
    public int getSlots() {
        return getItemKeys().size() + 16;
    }

    @NotNull
    @Override
    public ItemStack getStackInSlot(int slot) {
        NetworkStorage storage = storageWithSnapshot();
        if (storage == null || slot < 0 || slot >= itemKeys.size()) return ItemStack.EMPTY;
        AEItemKey key = itemKeys.get(slot);
        long available = storage.extract(key, Integer.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
        return available > 0 ? key.toStack((int) Math.min(available, Integer.MAX_VALUE)) : ItemStack.EMPTY;
    }

    @NotNull
    @Override
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        NetworkStorage storage = getStorage().orElse(null);
        if (storage == null || stack.isEmpty() || slot < 0) return stack;
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) return stack;
        long inserted = storage.insert(key, stack.getCount(),
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, IActionSource.empty());
        if (inserted <= 0) return stack;
        ItemStack remainder = stack.copy();
        remainder.shrink((int) Math.min(inserted, stack.getCount()));
        return remainder;
    }

    @NotNull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        NetworkStorage storage = storageWithSnapshot();
        if (storage == null || amount <= 0 || slot < 0 || slot >= itemKeys.size()) return ItemStack.EMPTY;
        AEItemKey key = itemKeys.get(slot);
        long removed = storage.extract(key, amount,
                simulate ? Actionable.SIMULATE : Actionable.MODULATE, IActionSource.empty());
        return removed > 0 ? key.toStack((int) Math.min(removed, Integer.MAX_VALUE)) : ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return slot >= 0 && !stack.isEmpty() && insertItem(slot, stack, true).isEmpty();
    }
}
