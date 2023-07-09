package com.glodblock.github.inventory;

import appeng.api.config.FuzzyMode;
import appeng.api.parts.IPart;
import appeng.fluids.helper.DualityFluidInterface;
import appeng.fluids.helper.IFluidInterfaceHost;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.misc.InterfaceTileEntity;
import appeng.tile.networking.CableBusTileEntity;
import appeng.util.InventoryAdaptor;
import appeng.util.inv.AdaptorItemHandler;
import appeng.util.inv.IInventoryDestination;
import appeng.util.inv.ItemSlot;
import com.glodblock.github.common.item.ItemFluidDrop;
import com.glodblock.github.common.item.ItemFluidPacket;
import com.glodblock.github.common.tile.TileDualInterface;
import com.glodblock.github.coreutil.ExtendedInterface;
import com.glodblock.github.util.Ae2Reflect;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;

public class FluidConvertingInventoryAdaptor extends InventoryAdaptor {

    public static InventoryAdaptor wrap(ICapabilityProvider capProvider, Direction face) {
        TileEntity cap = (TileEntity) capProvider;
        TileEntity inter = Objects.requireNonNull(cap.getWorld()).getTileEntity(cap.getPos().add(face.getDirectionVec()));
        DualityInterface dualInterface = getInterfaceTE(inter, face) == null ?
                null : Objects.requireNonNull(getInterfaceTE(inter, face)).getInterfaceDuality();
        boolean onmi = false;
        if (inter instanceof InterfaceTileEntity) {
            onmi = ((InterfaceTileEntity) inter).getTargets().size() > 1;
        } else if (inter instanceof TileDualInterface) {
            onmi = ((TileDualInterface) inter).getTargets().size() > 1;
        }

        if (dualInterface == null || !((ExtendedInterface) dualInterface).getFluidPacketMode()) {
            return new FluidConvertingInventoryAdaptor(
                    capProvider.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face).resolve().orElse(null),
                    capProvider.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face).resolve().orElse(null),
                    inter,
                    onmi,
                    dualInterface);
        }
        return InventoryAdaptor.getAdaptor(cap, face);
    }

    @Nullable
    private final InventoryAdaptor invItems;
    @Nullable
    private final IFluidHandler invFluids;
    private final boolean onmi;
    @Nullable
    private final TileEntity posInterface;
    @Nullable
    private final DualityInterface self;

    public FluidConvertingInventoryAdaptor(@Nullable IItemHandler invItems, @Nullable IFluidHandler invFluids,
                                           @Nullable TileEntity pos, boolean isOnmi, @Nullable DualityInterface interSelf) {
        this.invItems = invItems != null ? new AdaptorItemHandler(invItems) : null;
        this.invFluids = invFluids;
        this.posInterface = pos;
        this.onmi = isOnmi;
        this.self = interSelf;
    }

    @Override
    public ItemStack addItems(@Nonnull ItemStack toBeAdded) {
        if (toBeAdded.getItem() instanceof ItemFluidPacket || toBeAdded.getItem() instanceof ItemFluidDrop) {
            if (onmi) {
                FluidStack fluid;
                if (toBeAdded.getItem() instanceof ItemFluidPacket) {
                    fluid = ItemFluidPacket.getFluidStack(toBeAdded);
                } else {
                    fluid = ItemFluidDrop.getFluidStack(toBeAdded);
                }

                // First try to output to the same side
                if (invFluids != null) {
                    if (!fluid.isEmpty()) {
                        int filled = invFluids.fill(fluid, IFluidHandler.FluidAction.EXECUTE);
                        if (filled > 0) {
                            fluid.shrink(filled);
                            return ItemFluidPacket.newStack(fluid);
                        }
                    }
                }

                if (!fluid.isEmpty() && posInterface != null && posInterface.getWorld() != null
                        && self != null && ((ExtendedInterface) self).getSplittingMode()) {
                    for (Direction dir : Direction.values()) {
                        TileEntity te = posInterface.getWorld().getTileEntity(posInterface.getPos().add(dir.getDirectionVec()));
                        if (te != null) {
                            IInterfaceHost interTE = getInterfaceTE(te, dir);
                            if (interTE != null && isSameGrid(interTE)) {
                                continue;
                            }
                            IFluidInterfaceHost interFTE = getFluidInterfaceTE(te, dir);
                            if (interFTE != null && isSameGrid(interFTE)) {
                                continue;
                            }
                            IFluidHandler fh = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, dir.getOpposite())
                                    .resolve().orElse(null);
                            if (fh != null) {
                                int filled = fh.fill(fluid, IFluidHandler.FluidAction.SIMULATE);
                                if (filled == fluid.getAmount()) {
                                    fh.fill(fluid, IFluidHandler.FluidAction.EXECUTE);
                                    return ItemStack.EMPTY;
                                }
                            }
                        }
                    }
                }
                return ItemFluidPacket.newStack(fluid);
            }
            if (invFluids != null) {
                FluidStack fluid;
                if(toBeAdded.getItem() instanceof ItemFluidPacket)
                    fluid = ItemFluidPacket.getFluidStack(toBeAdded);
                else
                    fluid = ItemFluidDrop.getFluidStack(toBeAdded);
                if (!fluid.isEmpty()) {
                    int filled = invFluids.fill(fluid, IFluidHandler.FluidAction.EXECUTE);
                    if (filled > 0) {
                        fluid.shrink(filled);
                        return ItemFluidPacket.newStack(fluid);
                    }
                }
            }
            return toBeAdded;
        }
        return invItems != null ? invItems.addItems(toBeAdded) : toBeAdded;
    }

    @Override
    public ItemStack simulateAdd(ItemStack toBeSimulated) {
        if (toBeSimulated.getItem() instanceof ItemFluidPacket || toBeSimulated.getItem() instanceof ItemFluidDrop) {
            if (onmi) {
                boolean sus = false;
                FluidStack fluid;
                if (toBeSimulated.getItem() instanceof ItemFluidPacket) {
                    fluid = ItemFluidPacket.getFluidStack(toBeSimulated);
                } else {
                    fluid = ItemFluidDrop.getFluidStack(toBeSimulated);
                }

                // First try to output to the same side
                if (invFluids != null) {
                    if (!fluid.isEmpty()) {
                        int filled = invFluids.fill(fluid, IFluidHandler.FluidAction.SIMULATE);
                        if (filled > 0) {
                            fluid.shrink(filled);
                            return ItemFluidPacket.newStack(fluid);
                        }
                    }
                }

                if (!fluid.isEmpty() && posInterface != null && posInterface.getWorld() != null && self != null) {
                    if (((ExtendedInterface) self).getSplittingMode()) {
                        for (Direction dir : Direction.values()) {
                            TileEntity te = posInterface.getWorld().getTileEntity(posInterface.getPos().add(dir.getDirectionVec()));
                            if (te != null) {
                                IInterfaceHost interTE = getInterfaceTE(te, dir);
                                if (interTE != null && isSameGrid(interTE)) {
                                    continue;
                                }
                                IFluidInterfaceHost interFTE = getFluidInterfaceTE(te, dir);
                                if (interFTE != null && isSameGrid(interFTE)) {
                                    continue;
                                }
                                IFluidHandler fh = te.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, dir.getOpposite())
                                        .resolve().orElse(null);
                                if (fh != null) {
                                    int filled = fh.fill(fluid, IFluidHandler.FluidAction.SIMULATE);
                                    if (filled == fluid.getAmount()) {
                                        sus = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    sus = true;
                }
                return sus ? ItemStack.EMPTY : toBeSimulated;
            }
            if (invFluids != null) {
                FluidStack fluid;
                if(toBeSimulated.getItem() instanceof ItemFluidPacket)
                    fluid = ItemFluidPacket.getFluidStack(toBeSimulated);
                else
                    fluid = ItemFluidDrop.getFluidStack(toBeSimulated);
                if (!fluid.isEmpty()) {
                    int filled = invFluids.fill(fluid, IFluidHandler.FluidAction.SIMULATE);
                    if (filled > 0) {
                        fluid.shrink(filled);
                        return ItemFluidPacket.newStack(fluid);
                    }
                }
            }
            return toBeSimulated;
        }
        return invItems != null ? invItems.simulateAdd(toBeSimulated) : toBeSimulated;
    }

    @Override
    public ItemStack removeItems(int amount, ItemStack filter, IInventoryDestination destination) {
        return invItems != null ? invItems.removeItems(amount, filter, destination) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack simulateRemove(int amount, ItemStack filter, IInventoryDestination destination) {
        return invItems != null ? invItems.simulateRemove(amount, filter, destination) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeSimilarItems(int amount, ItemStack filter, FuzzyMode fuzzyMode, IInventoryDestination destination) {
        return invItems != null ? invItems.removeSimilarItems(amount, filter, fuzzyMode, destination) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode fuzzyMode, IInventoryDestination destination) {
        return invItems != null ? invItems.simulateSimilarRemove(amount, filter, fuzzyMode, destination) : ItemStack.EMPTY;
    }

    @Override
    public boolean containsItems() {
        int blockMode = 0;
        if (this.self != null) {
            blockMode = ((ExtendedInterface) this.self).getExtendedBlockMode();
        }
        boolean checkFluid = blockMode != 1;
        boolean checkItem = blockMode != 2;
        if (invFluids != null && checkFluid) {
            for (int i = 0; i < invFluids.getTanks(); i ++) {
                FluidStack fluid = invFluids.getFluidInTank(i);
                if (!fluid.isEmpty()) {
                    return true;
                }
            }
        }
        if (invItems != null && checkItem) {
            return invItems.containsItems();
        }
        return false;
    }

    @Override
    public boolean hasSlots() {
        return (invFluids != null && invFluids.getTanks() > 0)
                || (invItems != null && invItems.hasSlots());
    }

    @Nullable
    protected static IInterfaceHost getInterfaceTE(TileEntity te, Direction face) {
        if (te instanceof IInterfaceHost) {
            return (IInterfaceHost) te;
        } else if (te instanceof CableBusTileEntity) {
            IPart part = ((CableBusTileEntity) te).getPart(face.getOpposite());
            if (part instanceof IInterfaceHost) {
                return (IInterfaceHost) part;
            }
        }
        return null;
    }

    @Nullable
    protected static IFluidInterfaceHost getFluidInterfaceTE(TileEntity te, Direction face) {
        if (te instanceof IFluidInterfaceHost) {
            return (IFluidInterfaceHost) te;
        } else if (te instanceof CableBusTileEntity) {
            IPart part = ((CableBusTileEntity) te).getPart(face.getOpposite());
            if (part instanceof IFluidInterfaceHost) {
                return (IFluidInterfaceHost) part;
            }
        }
        return null;
    }

    private boolean isSameGrid(IInterfaceHost target) {
        if (this.self != null && target != null) {
            DualityInterface other = target.getInterfaceDuality();
            try {
                AENetworkProxy proxy1 = Ae2Reflect.getInterfaceProxy(other);
                AENetworkProxy proxy2 = Ae2Reflect.getInterfaceProxy(this.self);
                if (proxy1.getGrid() == proxy2.getGrid()) {
                    return true;
                }
            } catch (GridAccessException e) {
                return false;
            }
        }
        return false;
    }

    private boolean isSameGrid(IFluidInterfaceHost target) {
        if (this.self != null && target != null) {
            DualityFluidInterface other = target.getDualityFluidInterface();
            try {
                AENetworkProxy proxy1 = Ae2Reflect.getInterfaceProxy(other);
                AENetworkProxy proxy2 = Ae2Reflect.getInterfaceProxy(this.self);
                if (proxy1.getGrid() == proxy2.getGrid()) {
                    return true;
                }
            } catch (GridAccessException e) {
                return false;
            }
        }
        return false;
    }

    @Override
    @Nonnull
    public Iterator<ItemSlot> iterator() {
        return new SlotIterator(
                invFluids, invItems != null ? invItems.iterator() : Collections.emptyIterator());
    }

    private static class SlotIterator implements Iterator<ItemSlot> {

        private final IFluidHandler tanks;
        private final Iterator<ItemSlot> itemSlots;
        private int nextSlotIndex = 0;

        SlotIterator(IFluidHandler tanks, Iterator<ItemSlot> itemSlots) {
            this.tanks = tanks;
            this.itemSlots = itemSlots;
        }

        @Override
        public boolean hasNext() {
            return nextSlotIndex < tanks.getTanks() || itemSlots.hasNext();
        }

        @Override
        public ItemSlot next() {
            if (nextSlotIndex < tanks.getTanks()) {
                FluidStack fluid = tanks.getFluidInTank(nextSlotIndex);
                ItemSlot slot = new ItemSlot();
                slot.setSlot(nextSlotIndex++);
                slot.setItemStack(!fluid.isEmpty() ? ItemFluidPacket.newStack(fluid) : ItemStack.EMPTY);
                Ae2Reflect.setItemSlotExtractable(slot, false);
                return slot;
            } else {
                ItemSlot slot = itemSlots.next();
                slot.setSlot(nextSlotIndex++);
                return slot;
            }
        }

    }

}
