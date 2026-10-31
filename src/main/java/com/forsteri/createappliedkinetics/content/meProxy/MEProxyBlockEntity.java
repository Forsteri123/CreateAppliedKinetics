package com.forsteri.createappliedkinetics.content.meProxy;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridHelper;
import appeng.blockentity.grid.AENetworkedPoweredBlockEntity;
import com.forsteri.createappliedkinetics.entry.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import java.util.EnumSet;

public class MEProxyBlockEntity extends AENetworkedPoweredBlockEntity {
    private final MEProxyInventoryHandler inventory;
    private boolean pendingReconnect = false;
    private int reconnectCountdown = -1;

    public MEProxyBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState blockState) {
        super(blockEntityType, pos, blockState);
        this.getMainNode().setIdlePowerUsage(0.0F);
        this.inventory = new MEProxyInventoryHandler(this);
    }

    @Override
    public void onReady() {
        super.onReady();
        if (level != null && !level.isClientSide) {
            pendingReconnect = true;
            reconnectCountdown = 1;
        }
    }

    public void scheduleReconnect() {
        if (level == null || level.isClientSide || pendingReconnect) return;
        pendingReconnect = true;
        reconnectCountdown = 1;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, MEProxyBlockEntity be) {
        if (!be.pendingReconnect) return;

        int connectableNeighbors = 0;
        for (Direction dir : Direction.values()) {
            if (GridHelper.getExposedNode(level, pos.relative(dir), dir.getOpposite()) != null) {
                connectableNeighbors++;
            }
        }

        int currentConnections = 0;
        if (be.getMainNode().getNode() != null) {
            currentConnections = be.getMainNode().getNode().getConnections().size();
        }

        if (connectableNeighbors == 0) {
            be.reconnectCountdown++;
            if (be.reconnectCountdown > 40) {
                be.pendingReconnect = false;
                be.reconnectCountdown = -1;
            }
            return;
        }

        if (currentConnections < connectableNeighbors) {

            be.getMainNode().setExposedOnSides(EnumSet.noneOf(Direction.class));
            ((ServerLevel) level).getServer().execute(() -> {
                if (!be.isRemoved()) {
                    be.pendingReconnect = false;
                    be.reconnectCountdown = -1;
                    be.getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
                }
            });
        } else {
            be.pendingReconnect = false;
            be.reconnectCountdown = -1;
        }
    }

    @Override
    public InternalInventory getInternalInventory() {
        return InternalInventory.empty();
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                Registration.meProxyBlockEntity.get(),
                (be, context) -> be.inventory
        );
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                Registration.meProxyBlockEntity.get(),
                (be, context) -> be.inventory
        );
    }
}