package com.glodblock.github.client.container;

import appeng.api.AEApi;
import appeng.api.definitions.IDefinitions;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.data.IAEItemStack;
import appeng.container.guisync.GuiSync;
import appeng.container.implementations.ContainerPatternTerm;
import appeng.container.slot.SlotFakeCraftingMatrix;
import appeng.container.slot.SlotPatternOutputs;
import appeng.helpers.InventoryAction;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;
import com.glodblock.github.common.item.ItemFluidCraftEncodedPattern;
import com.glodblock.github.common.item.ItemFluidEncodedPattern;
import com.glodblock.github.common.item.ItemLargeEncodedPattern;
import com.glodblock.github.common.item.fake.FakeFluids;
import com.glodblock.github.common.item.fake.FakeItemRegister;
import com.glodblock.github.common.part.PartFluidPatternTerminal;
import com.glodblock.github.integration.mek.FCGasItems;
import com.glodblock.github.integration.mek.FakeGases;
import com.glodblock.github.interfaces.PatternConsumer;
import com.glodblock.github.loader.FCItems;
import com.glodblock.github.util.Ae2Reflect;
import com.glodblock.github.util.FluidCraftingPatternDetails;
import com.glodblock.github.util.FluidPatternDetails;
import com.glodblock.github.util.ModAndClassUtil;
import com.glodblock.github.util.Util;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import mekanism.api.gas.GasStack;
import mekanism.common.capabilities.Capabilities;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ContainerFluidPatternTerminal extends ContainerPatternTerm implements PatternConsumer {
    @GuiSync(105)
    public boolean combine = false;
    @GuiSync(106)
    public boolean fluidFirst = false;

    public ContainerFluidPatternTerminal(InventoryPlayer ip, ITerminalHost monitorable) {
        super(ip, monitorable);
    }

    @Override
    public void encode() {
        if (!checkHasFluidPattern()) {
            super.encode();
            return;
        }
        ItemStack stack = this.patternSlotOUT.getStack();
        if (stack.isEmpty()) {
            stack = this.patternSlotIN.getStack();
            if (stack.isEmpty() || !isPattern(stack)) {
                return;
            }
            if (stack.getCount() == 1) {
                this.patternSlotIN.putStack(ItemStack.EMPTY);
            } else {
                stack.shrink(1);
            }
            encodeFluidPattern();
        } else if (isPattern(stack)) {
            encodeFluidPattern();
        }
    }

    public void encodeFluidCraftPattern() {
        ItemStack output = this.patternSlotOUT.getStack();

        final ItemStack[] in = this.getInputs();
        final ItemStack[] out = this.getOutputs();
        if (in == null || out == null) {
            return;
        }

        if (!output.isEmpty() && !isPattern(output)) {
            return;
        }
        else if (output.isEmpty()) {
            output = this.patternSlotIN.getStack();
            if (output.isEmpty() || !isPattern(output)) {
                return;
            }
            output.setCount(output.getCount() - 1);
            if (output.getCount() == 0) {
                this.patternSlotIN.putStack(ItemStack.EMPTY);
            }
            Optional<ItemStack> maybePattern = AEApi.instance().definitions().items().encodedPattern().maybeStack(1);
            if (maybePattern.isPresent()) {
                output = maybePattern.get();
                this.patternSlotOUT.putStack(output);
            }
        }
        final NBTTagCompound encodedValue = new NBTTagCompound();

        final NBTTagList tagIn = new NBTTagList();
        final NBTTagList tagOut = new NBTTagList();

        for (final ItemStack i : in) {
            tagIn.appendTag(this.createItemTag(i));
        }

        for (final ItemStack i : out) {
            tagOut.appendTag(this.createItemTag(i));
        }

        encodedValue.setTag("in", tagIn);
        encodedValue.setTag("out", tagOut);
        encodedValue.setBoolean("crafting", this.isCraftingMode());
        encodedValue.setBoolean("substitute", this.substitute);
        ItemStack patternStack = new ItemStack(FCItems.DENSE_CRAFT_ENCODED_PATTERN);
        patternStack.setTagCompound(encodedValue);
        FluidCraftingPatternDetails details = FluidCraftingPatternDetails.GetFluidPattern(patternStack, getNetworkNode().getWorld());
        if (details == null || !details.isNecessary()) {
            encode();
            return;
        }
        patternSlotOUT.putStack(patternStack);
    }

    private static boolean isPattern(final ItemStack output) {
        if (output.isEmpty()) {
            return false;
        }
        if (output.getItem() instanceof ItemFluidEncodedPattern
                || output.getItem() instanceof ItemFluidCraftEncodedPattern
                || output.getItem() instanceof ItemLargeEncodedPattern) {
            return true;
        }
        final IDefinitions defs = AEApi.instance().definitions();
        return defs.items().encodedPattern().isSameAs(output) || defs.materials().blankPattern().isSameAs(output);
    }

    private boolean checkHasFluidPattern() {
        if (this.craftingMode) {
            return false;
        }
        boolean hasFluid = false, search = false;
        for (Slot craftingSlot : this.craftingSlots) {
            final ItemStack crafting = craftingSlot.getStack();
            if (crafting.isEmpty()) {
                continue;
            }
            search = true;
            if (crafting.getItem() == FCItems.FLUID_PACKET) {
                hasFluid = true;
                break;
            }
            if (ModAndClassUtil.GAS && crafting.getItem() == FCGasItems.GAS_PACKET) {
                hasFluid = true;
                break;
            }
        }
        if (!search) { // search=false -> inputs were empty
            return false;
        }
        // `search` should be true at this point
        for (Slot outputSlot : this.outputSlots) {
            final ItemStack out = outputSlot.getStack();
            if (out.isEmpty()) {
                continue;
            }
            search = false;
            if (hasFluid) {
                break;
            } else if (out.getItem() == FCItems.FLUID_PACKET) {
                hasFluid = true;
                break;
            } else if (ModAndClassUtil.GAS && out.getItem() == FCGasItems.GAS_PACKET) {
                hasFluid = true;
                break;
            }
        }
        return hasFluid && !search; // search=true -> outputs were empty
    }

    private void encodeFluidPattern() {
        ItemStack patternStack = new ItemStack(FCItems.DENSE_ENCODED_PATTERN);
        FluidPatternDetails pattern = new FluidPatternDetails(patternStack);
        pattern.setInputs(collectInventory(craftingSlots));
        pattern.setOutputs(collectInventory(outputSlots));
        pattern.setEncoder(this.getInventoryPlayer().player.getGameProfile());
        patternSlotOUT.putStack(pattern.writeToStack());
    }

    private static IAEItemStack[] collectInventory(Slot[] slots) {
        // see note at top of DensePatternDetails
        List<IAEItemStack> acc = new ArrayList<>();
        for (Slot slot : slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == FCItems.FLUID_PACKET) {
                IAEItemStack dropStack = FakeFluids.packFluid2AEDrops((FluidStack) FakeItemRegister.getStack(stack));
                if (dropStack != null) {
                    acc.add(dropStack);
                    continue;
                }
            }
            if (ModAndClassUtil.GAS && stack.getItem() == FCGasItems.GAS_PACKET) {
                IAEItemStack dropStack = FakeGases.packGas2AEDrops((GasStack) FakeItemRegister.getStack(stack));
                if (dropStack != null) {
                    acc.add(dropStack);
                    continue;
                }
            }
            IAEItemStack aeStack = AEItemStack.fromItemStack(stack);
            if (aeStack == null) {
                continue;
            }
            acc.add(aeStack);
        }
        return acc.toArray(new IAEItemStack[0]);
    }

    @Override
    public void acceptPattern(Int2ObjectMap<ItemStack[]> inputs, List<ItemStack> outputs, boolean combine) {
        if (Ae2Reflect.getPart(this) instanceof PartFluidPatternTerminal) {
            ((PartFluidPatternTerminal) Ae2Reflect.getPart(this)).onChangeCrafting(inputs, outputs, combine);
        }
    }

    @Override
    public void doAction(EntityPlayerMP player, InventoryAction action, int slotId, long id) {
        if (this.isCraftingMode()) {
            super.doAction(player, action, slotId, id);
            return;
        }
        if (slotId < 0 || slotId >= this.inventorySlots.size()) {
            super.doAction(player, action, slotId, id);
            return;
        }
        Slot slot = getSlot(slotId);
        ItemStack stack = player.inventory.getItemStack();
        if ((slot instanceof SlotFakeCraftingMatrix || slot instanceof SlotPatternOutputs) && !stack.isEmpty()
                && stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, null) && Util.getFluidFromItem(stack) != null) {
            FluidStack fluid = null;
            switch (action) {
                case PICKUP_OR_SET_DOWN:
                    fluid = Util.getFluidFromItem(stack);
                    slot.putStack(FakeFluids.packFluid2Packet(fluid));
                    break;
                case SPLIT_OR_PLACE_SINGLE:
                    fluid = Util.getFluidFromItem(ItemHandlerHelper.copyStackWithSize(stack, 1));
                    FluidStack origin = FakeItemRegister.getStack(slot.getStack());
                    if (fluid != null && fluid.equals(origin)) {
                        fluid.amount += origin.amount;
                        if (fluid.amount <= 0) fluid = null;
                    }
                    slot.putStack(FakeFluids.packFluid2Packet(fluid));
                    break;
            }
            if (fluid == null) {
                super.doAction(player, action, slotId, id);
                return;
            }
            return;
        }
        if (ModAndClassUtil.GAS && (slot instanceof SlotFakeCraftingMatrix || slot instanceof SlotPatternOutputs) && !stack.isEmpty()
                && stack.hasCapability(Capabilities.GAS_HANDLER_CAPABILITY, null) && Util.getGasFromItem(stack) != null) {
            GasStack gas = null;
            switch (action) {
                case PICKUP_OR_SET_DOWN:
                    gas = (GasStack) Util.getGasFromItem(stack);
                    slot.putStack(FakeGases.packGas2Packet(gas));
                    break;
                case SPLIT_OR_PLACE_SINGLE:
                    gas = (GasStack) Util.getGasFromItem(ItemHandlerHelper.copyStackWithSize(stack, 1));
                    GasStack origin = FakeItemRegister.getStack(slot.getStack());
                    if (gas != null && gas.equals(origin)) {
                        gas.amount += origin.amount;
                        if (gas.amount <= 0) gas = null;
                    }
                    slot.putStack(FakeGases.packGas2Packet(gas));
                    break;
            }
            if (gas == null) {
                super.doAction(player, action, slotId, id);
                return;
            }
            return;
        }
        if (action == InventoryAction.SPLIT_OR_PLACE_SINGLE) {
            if (stack.isEmpty() && !slot.getStack().isEmpty() && slot.getStack().getItem() == FCItems.FLUID_PACKET) {
                FluidStack fluid = FakeItemRegister.getStack(slot.getStack());
                if (fluid != null && fluid.amount - 1000 >= 1) {
                    fluid.amount -= 1000;
                    slot.putStack(FakeFluids.packFluid2Packet(fluid));
                }
            } else if (ModAndClassUtil.GAS && stack.isEmpty() && !slot.getStack().isEmpty() && slot.getStack().getItem() == FCGasItems.GAS_PACKET) {
                GasStack gas = FakeItemRegister.getStack(slot.getStack());
                if (gas != null && gas.amount - 1000 >= 1) {
                    gas.amount -= 1000;
                    slot.putStack(FakeGases.packGas2Packet(gas));
                }
            }
        }
        super.doAction(player, action, slotId, id);
    }

    @Override
    public void multiply(int multiple) {
        if (Util.multiplySlotCheck(this.craftingSlots, multiple) && Util.multiplySlotCheck(this.outputSlots, multiple)) {
            Util.multiplySlot(this.craftingSlots, multiple);
            Util.multiplySlot(this.outputSlots, multiple);
        }
    }

    @Override
    public void divide(int divide) {
        if (Util.divideSlotCheck(this.craftingSlots, divide) && Util.divideSlotCheck(this.outputSlots, divide)) {
            Util.divideSlot(this.craftingSlots, divide);
            Util.divideSlot(this.outputSlots, divide);
        }
    }

    @Override
    public void increase(int increase) {
        if (Util.increaseSlotCheck(this.craftingSlots, increase) && Util.increaseSlotCheck(this.outputSlots, increase)) {
            Util.increaseSlot(this.craftingSlots, increase);
            Util.increaseSlot(this.outputSlots, increase);
        }
    }

    @Override
    public void decrease(int decrease) {
        if (Util.decreaseSlotCheck(this.craftingSlots, decrease) && Util.decreaseSlotCheck(this.outputSlots, decrease)) {
            Util.decreaseSlot(this.craftingSlots, decrease);
            Util.decreaseSlot(this.outputSlots, decrease);
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (Platform.isServer()) {
            this.combine = ((PartFluidPatternTerminal) Ae2Reflect.getPart(this)).getCombineMode();
            this.fluidFirst = ((PartFluidPatternTerminal) Ae2Reflect.getPart(this)).getFluidPlaceMode();
        }
    }

    NBTBase createItemTag(ItemStack i) {
        NBTTagCompound c = new NBTTagCompound();
        if (!i.isEmpty()) {
            i.writeToNBT(c);
            if (i.getCount() > i.getMaxStackSize()) {
                c.setInteger("stackSize", i.getCount());
            }
        }
        return c;
    }

}
